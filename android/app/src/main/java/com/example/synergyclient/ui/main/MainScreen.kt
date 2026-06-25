package com.example.synergyclient.ui.main

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.example.synergyclient.network.DiscoveredServer
import com.example.synergyclient.network.SynergyNetworkService
import com.example.synergyclient.network.SynergyServerDiscovery
import com.example.synergyclient.service.SynergyAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MAX_LOGS = 200

// ── Colour palette (single source of truth) ─────────────────────────────────
private val Bg      = Color(0xFF0D0E14)
private val Surface = Color(0xFF14151F)
private val Card    = Color(0xFF1A1B27)
private val Accent  = Color(0xFF6C63FF)
private val AccentB = Color(0xFF8B85FF)
private val Green   = Color(0xFF2ECC71)
private val Amber   = Color(0xFFF39C12)
private val Red     = Color(0xFFE74C3C)
private val TextCol = Color(0xFFECEFF4)
private val Muted   = Color(0xFF6B7280)
private val Border  = Color(0xFF252636)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val prefs = remember {
        context.getSharedPreferences("synergy_prefs", android.content.Context.MODE_PRIVATE)
    }

    val defaultClientName = remember {
        val systemDeviceName = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
                android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            } else null
        } catch (_: SecurityException) {
            null
        }
        val bluetoothName = try {
            android.provider.Settings.Secure.getString(context.contentResolver, "bluetooth_name")
        } catch (_: SecurityException) {
            null
        }
        val rawName = systemDeviceName ?: bluetoothName ?: android.os.Build.MODEL
        rawName.replace("[^a-zA-Z0-9-_]".toRegex(), "")
    }

    var serverIp      by remember { mutableStateOf(prefs.getString("server_ip",    "10.40.194.37") ?: "10.40.194.37") }
    var port          by remember { mutableStateOf(prefs.getString("port",         "24800")        ?: "24800") }
    var clientName    by remember {
        val saved = prefs.getString("client_name", null)
        val initial = if (saved == null || saved == "AndroidClient") defaultClientName else saved
        mutableStateOf(initial)
    }
    var autoReconnect by remember { mutableStateOf(prefs.getBoolean("auto_reconnect", true)) }
    var loggingEnabled by remember { mutableStateOf(prefs.getBoolean("logging_enabled", false)) }
    var isMacServerMode by remember { mutableStateOf(prefs.getBoolean("mac_server_mode", false)) }
    var clipboardSyncEnabled by remember { mutableStateOf(prefs.getBoolean("clipboard_sync", true)) }

    // ── Log ring buffer ────────────────────────────────────────────────────
    val logs      = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    fun addLog(msg: String) {
        val lines = msg.split("\n").filter { it.isNotBlank() }
        for (line in lines) {
            logs.add(line)
            if (logs.size > MAX_LOGS) logs.removeAt(0)
        }
    }

    // ── Foreground Service Connection ──────────────────────────────────────
    var foregroundService by remember { mutableStateOf<com.example.synergyclient.network.SynergyForegroundService?>(null) }
    var connectionStatus by remember { mutableStateOf("Disconnected") }

    val serviceConnection = remember {
        object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                val binder = service as? com.example.synergyclient.network.SynergyForegroundService.LocalBinder
                val svcInstance = binder?.getService()
                foregroundService = svcInstance
                svcInstance?.setListeners(
                    onStatusChange = { connectionStatus = it },
                    onLog = { msg -> addLog(msg) }
                )
            }

            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                foregroundService?.removeListeners()
                foregroundService = null
            }
        }
    }

    // ── Start the persistent service, then bind for UI status updates ────────
    DisposableEffect(Unit) {
        val intent = android.content.Intent(context, com.example.synergyclient.network.SynergyForegroundService::class.java)
        // Start independently — ensures service survives even if UI unbinds
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        // Bind for live status/log callbacks — BIND_NOT_FOREGROUND ensures
        // UI bind/unbind doesn't affect the service's foreground state or lifetime
        context.bindService(
            intent, serviceConnection,
            android.content.Context.BIND_AUTO_CREATE or android.content.Context.BIND_NOT_FOREGROUND
        )
        onDispose {
            // Only remove callbacks — do NOT unbind or stop the service
            // so it keeps running when app is hidden
            foregroundService?.removeListeners()
        }
    }

    val isConnected  = connectionStatus == "Connected"
    val isConnecting = connectionStatus == "Connecting…" || connectionStatus == "Connecting..."

    var isAccessibilityEnabled by remember { mutableStateOf(false) }

    // ── Discovery engine (singleton per composition) ───────────────────────
    val discovery = remember { SynergyServerDiscovery() }
    val discoveredServers by discovery.allServers.collectAsState()
    var showScanPanel by remember { mutableStateOf(false) }
    var isScanningAnim by remember { mutableStateOf(false) }

    // ── Start discovery when composition enters, stop when it leaves ───────
    DisposableEffect(Unit) {
        discovery.start()
        onDispose {
            discovery.stop()
            try { context.unbindService(serviceConnection) } catch (_: Exception) {}
        }
    }

    // ── React to new servers found by discovery ────────────────────────────
    LaunchedEffect(Unit) {
        discovery.serverFound.collect { server ->
            addLog("🔍 Found: ${server.displayLabel}:${server.port}")
        }
    }

    LaunchedEffect(Unit) {
        discovery.serverLost.collect { ip ->
            addLog("📡 Lost: $ip")
        }
    }

    // ── Start/stop scanning animation ─────────────────────────────────────
    LaunchedEffect(showScanPanel) {
        if (showScanPanel) { isScanningAnim = true }
        else               { isScanningAnim = false }
    }

    // ── Poll accessibility ─────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        while (true) {
            isAccessibilityEnabled = SynergyAccessibilityService.instance != null
            delay(1000)
        }
    }

    // ── Auto-scroll log ────────────────────────────────────────────────────
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    // ── Auto-hide to background when connected ────────────────────────────
    val activity = context as? android.app.Activity
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == "Connected") {
            // Short delay so the user sees the "Connected" state, then hide
            delay(800)
            // Commented out to prevent the app from auto-minimizing when connected, keeping it in the foreground for typing.
            // activity?.moveTaskToBack(true)
        }
    }

    // ── Settings persist helper ────────────────────────────────────────────
    fun saveSettings() {
        prefs.edit().apply {
            putString("server_ip",      serverIp)
            putString("port",           port)
            putString("client_name",    clientName)
            putBoolean("auto_reconnect",    autoReconnect)
            putBoolean("logging_enabled",   loggingEnabled)
            putBoolean("mac_server_mode",   isMacServerMode)
            putBoolean("clipboard_sync",    clipboardSyncEnabled)
            apply()
        }
    }

    // ── Connect / Disconnect ───────────────────────────────────────────────
    fun connect() {
        saveSettings()
        
        // Dynamically request notification permissions on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                (context as? android.app.Activity)?.requestPermissions(arrayOf(permission), 101)
            }
        }

        val portNum = port.toIntOrNull() ?: 24800
        val serviceIntent = android.content.Intent(context, com.example.synergyclient.network.SynergyForegroundService::class.java).apply {
            action = com.example.synergyclient.network.SynergyForegroundService.ACTION_START
            putExtra(com.example.synergyclient.network.SynergyForegroundService.EXTRA_HOST, serverIp)
            putExtra(com.example.synergyclient.network.SynergyForegroundService.EXTRA_PORT, portNum)
            putExtra(com.example.synergyclient.network.SynergyForegroundService.EXTRA_CLIENT_NAME, clientName)
            putExtra(com.example.synergyclient.network.SynergyForegroundService.EXTRA_LOGGING, loggingEnabled)
            putExtra(com.example.synergyclient.network.SynergyForegroundService.EXTRA_CLIPBOARD, clipboardSyncEnabled)
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        connectionStatus = "Connecting..."
    }

    fun disconnect() {
        foregroundService?.stopServiceAndConnection()
    }

    fun selectServer(server: DiscoveredServer) {
        serverIp = server.ip
        port     = server.port.toString()
        showScanPanel = false
        addLog("✓ Selected ${server.displayLabel}")
        if (!isConnected && !isConnecting) connect()
    }

    val scrollState = rememberScrollState()

    // ── Root layout ────────────────────────────────────────────────────────
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ── Header ─────────────────────────────────────────────────────────
        AppHeader(connectionStatus = connectionStatus)

        // ── Accessibility warning ──────────────────────────────────────────
        AnimatedVisibility(
            visible = !isAccessibilityEnabled,
            enter   = slideInVertically() + fadeIn(),
            exit    = slideOutVertically() + fadeOut()
        ) {
            AccessibilityWarningBanner(context = context)
        }

        // ── Network Scan Panel ─────────────────────────────────────────────
        ScanPanel(
            discovery        = discovery,
            discoveredServers = discoveredServers,
            showScanPanel    = showScanPanel,
            isScanningAnim   = isScanningAnim,
            onToggle         = { showScanPanel = !showScanPanel },
            onSelectServer   = ::selectServer,
            onRescan         = {
                addLog("🔄 Rescanning network...")
                discovery.stop()
                discovery.start()
            }
        )

        // ── Connection settings ────────────────────────────────────────────
        ConnectionCard(
            serverIp        = serverIp,
            port            = port,
            clientName      = clientName,
            autoReconnect   = autoReconnect,
            loggingEnabled  = loggingEnabled,
            isMacServerMode = isMacServerMode,
            clipboardSyncEnabled = clipboardSyncEnabled,
            isConnected     = isConnected,
            isConnecting    = isConnecting,
            onServerIpChange     = { serverIp = it },
            onPortChange         = { port = it },
            onClientNameChange   = { clientName = it },
            onAutoReconnectChange = { autoReconnect = it },
            onLoggingChange      = { loggingEnabled = it },
            onMacModeChange      = { isMacServerMode = it },
            onClipboardSyncChange = { clipboardSyncEnabled = it },
            onConnectClick       = { if (isConnected || isConnecting) disconnect() else connect() }
        )

        // ── Activity log ───────────────────────────────────────────────────
        ActivityLog(
            logs      = logs,
            listState = listState,
            onClear   = { logs.clear() },
            modifier  = Modifier.heightIn(max = 200.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AppHeader(connectionStatus: String) {
    val statusColor = when (connectionStatus) {
        "Connected"    -> Green
        "Connecting..." -> Amber
        else           -> Red
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("FLOWPORT", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = TextCol, letterSpacing = 3.sp)
            Text("Android Client  •  v1.0", fontSize = 12.sp, color = Muted)
        }
        AnimatedContent(
            targetState   = connectionStatus,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label         = "status"
        ) { status ->
            Surface(
                shape  = RoundedCornerShape(20.dp),
                color  = statusColor.copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                    Text(status, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = statusColor)
                }
            }
        }
    }
}

@Composable
private fun AccessibilityWarningBanner(context: android.content.Context) {
    Surface(
        shape  = RoundedCornerShape(10.dp),
        color  = Color(0xFF2A1F1F),
        border = androidx.compose.foundation.BorderStroke(1.dp, Red.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Accessibility Service Required",
                    fontWeight = FontWeight.Bold, color = Red, fontSize = 13.sp)
                Text("Needed for cursor, keyboard & clipboard.",
                    fontSize = 11.sp, color = Red.copy(alpha = 0.7f))
            }
            Button(
                onClick = {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                shape  = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("ENABLE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ScanPanel(
    discovery:         SynergyServerDiscovery,
    discoveredServers: List<DiscoveredServer>,
    showScanPanel:     Boolean,
    isScanningAnim:    Boolean,
    onToggle:          () -> Unit,
    onSelectServer:    (DiscoveredServer) -> Unit,
    onRescan:          () -> Unit,
) {
    // Spinning animation for scan icon
    val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue   = 0f,
        targetValue    = 360f,
        animationSpec  = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label          = "spin"
    )

    Surface(
        shape    = RoundedCornerShape(12.dp),
        color    = Card,
        border   = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            brush = if (discoveredServers.isNotEmpty())
                Brush.horizontalGradient(listOf(Accent, AccentB))
            else
                Brush.horizontalGradient(listOf(Border, Border))
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            // ── Header row ─────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Spinning radar icon (text emoji fallback)
                Text(
                    text     = "📡",
                    fontSize = 18.sp,
                    modifier = if (discoveredServers.isEmpty())
                        Modifier.rotate(rotation)
                    else
                        Modifier
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Network Scan", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextCol)
                    Text(
                        text = when {
                            discoveredServers.isEmpty() -> "Scanning for Flowport servers..."
                            discoveredServers.size == 1 -> "1 server found"
                            else                        -> "${discoveredServers.size} servers found"
                        },
                        fontSize = 11.sp,
                        color    = if (discoveredServers.isEmpty()) Muted else Green
                    )
                }
                // Found count badge
                if (discoveredServers.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text     = discoveredServers.size.toString(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color    = Color.White
                        )
                    }
                }
                // Expand chevron
                Text(
                    text     = if (showScanPanel) "▲" else "▼",
                    fontSize = 11.sp,
                    color    = Muted
                )
            }

            // ── Expandable server list ─────────────────────────────────────
            AnimatedVisibility(
                visible      = showScanPanel,
                enter        = expandVertically() + fadeIn(),
                exit         = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = Border, thickness = 1.dp)
                    Spacer(Modifier.height(8.dp))

                    if (discoveredServers.isEmpty()) {
                        // Empty state with scanning indicator
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(14.dp),
                                color       = Accent,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Waiting for broadcast packets on UDP:24802",
                                fontSize = 11.sp, color = Muted)
                        }
                    } else {
                        discoveredServers.forEach { server ->
                            ServerListItem(server = server, onClick = { onSelectServer(server) })
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Rescan button
                    OutlinedButton(
                        onClick = onRescan,
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(8.dp),
                        border   = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.5f)),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
                    ) {
                        Text("🔄  Rescan Network", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerListItem(server: DiscoveredServer, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Bg)
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Green))
        Column(modifier = Modifier.weight(1f)) {
            Text(server.hostname, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = TextCol, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${server.ip}  •  port ${server.port}", fontSize = 11.sp, color = Muted)
        }
        Text("Connect →", fontSize = 11.sp, color = Accent)
    }
}

@Composable
private fun ConnectionCard(
    serverIp:        String,
    port:            String,
    clientName:      String,
    autoReconnect:   Boolean,
    loggingEnabled:  Boolean,
    isMacServerMode: Boolean,
    clipboardSyncEnabled: Boolean,
    isConnected:     Boolean,
    isConnecting:    Boolean,
    onServerIpChange:      (String)  -> Unit,
    onPortChange:          (String)  -> Unit,
    onClientNameChange:    (String)  -> Unit,
    onAutoReconnectChange: (Boolean) -> Unit,
    onLoggingChange:       (Boolean) -> Unit,
    onMacModeChange:       (Boolean) -> Unit,
    onClipboardSyncChange: (Boolean) -> Unit,
    onConnectClick:        () -> Unit,
) {
    Surface(
        shape    = RoundedCornerShape(12.dp),
        color    = Card,
        border   = androidx.compose.foundation.BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("CONNECTION", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = Muted, letterSpacing = 1.5.sp)

            OutlinedTextField(
                value    = serverIp,
                onValueChange = onServerIpChange,
                label    = { Text("Server IP", color = Muted, fontSize = 13.sp) },
                colors   = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor    = Accent, unfocusedBorderColor = Border,
                    focusedTextColor      = TextCol, unfocusedTextColor = TextCol,
                    focusedLabelColor     = Accent,  unfocusedLabelColor = Muted
                ),
                modifier  = Modifier.fillMaxWidth(),
                enabled   = !isConnected && !isConnecting,
                singleLine = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value    = port,
                    onValueChange = onPortChange,
                    label    = { Text("Port", color = Muted, fontSize = 13.sp) },
                    colors   = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Border,
                        focusedTextColor = TextCol, unfocusedTextColor = TextCol
                    ),
                    modifier  = Modifier.weight(1f),
                    enabled   = !isConnected && !isConnecting,
                    singleLine = true
                )
                OutlinedTextField(
                    value    = clientName,
                    onValueChange = onClientNameChange,
                    label    = { Text("Screen Name", color = Muted, fontSize = 13.sp) },
                    colors   = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Border,
                        focusedTextColor = TextCol, unfocusedTextColor = TextCol
                    ),
                    modifier  = Modifier.weight(2f),
                    enabled   = !isConnected && !isConnecting,
                    singleLine = true
                )
            }

            ToggleRow("Auto-reconnect",  "Reconnect after 3s if dropped",       autoReconnect,   onAutoReconnectChange)
            ToggleRow("Connection Logs", "Disable logs for faster communication", loggingEnabled,  onLoggingChange)
            ToggleRow("Mac Server Mode", "Maps ⌘+C/V → Ctrl+C/V on this device", isMacServerMode, onMacModeChange,
                highlightColor = Accent)
            ToggleRow("Sync Clipboard",  "Sync clipboard copy-pastes between devices", clipboardSyncEnabled, onClipboardSyncChange)

            val btnGradient = if (isConnected)
                Brush.horizontalGradient(listOf(Red, Color(0xFFC0392B)))
            else
                Brush.horizontalGradient(listOf(Accent, AccentB))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(btnGradient),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick  = onConnectClick,
                    colors   = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    elevation = ButtonDefaults.buttonElevation(0.dp),
                    shape    = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp),
                            color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = when {
                            isConnecting -> "Connecting..."
                            isConnected  -> "Disconnect"
                            else         -> "Connect"
                        },
                        fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title:         String,
    subtitle:      String,
    checked:       Boolean,
    onCheckedChange: (Boolean) -> Unit,
    highlightColor: Color = Accent,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title,    fontSize = 13.sp, color = TextCol)
            Text(subtitle, fontSize = 11.sp, color = Muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor  = Color.White,
                checkedTrackColor  = highlightColor
            )
        )
    }
}

@Composable
private fun ActivityLog(
    logs:      List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onClear:   () -> Unit,
    modifier:  Modifier = Modifier,
) {
    val green = Green; val red = Red; val accentB = AccentB; val text = TextCol

    Surface(
        shape    = RoundedCornerShape(12.dp),
        color    = Color(0xFF0A0B10),
        border   = androidx.compose.foundation.BorderStroke(1.dp, Border),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ACTIVITY LOG", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = Muted, letterSpacing = 1.5.sp)
                TextButton(
                    onClick = onClear,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("Clear", fontSize = 10.sp, color = Muted)
                }
            }
            LazyColumn(
                state   = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(logs) { line ->
                    Text(
                        text   = line,
                        color  = when {
                            line.contains("Connected") || line.contains("DSOP") -> green
                            line.contains("Error") || line.contains("Disconnected") -> red.copy(alpha = 0.8f)
                            line.startsWith("→") || line.startsWith("✓") -> accentB
                            line.startsWith("🔍") || line.startsWith("📡") -> Amber
                            else -> text.copy(alpha = 0.7f)
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 11.sp,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
