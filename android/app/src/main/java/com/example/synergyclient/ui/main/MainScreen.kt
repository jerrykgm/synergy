package com.example.synergyclient.ui.main

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
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
    var showNotesDialog by remember { mutableStateOf(false) }
    var autoHideKeyboard by remember { mutableStateOf(prefs.getBoolean("auto_hide_keyboard", true)) }
    var autoHideApp by remember { mutableStateOf(prefs.getBoolean("auto_hide_app", false)) }
    var focusAppOnType by remember { mutableStateOf(prefs.getBoolean("focus_app_on_type", false)) }
    var forceFocusEnabled by remember { mutableStateOf(prefs.getBoolean("force_focus_client", true)) }

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
    var isKeyboardEnabled      by remember { mutableStateOf(false) }
    var isKeyboardDefault      by remember { mutableStateOf(false) }

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

    // ── Poll keyboard status ───────────────────────────────────────────────
    LaunchedEffect(Unit) {
        while (true) {
            isKeyboardEnabled = isInputMethodEnabled(context)
            isKeyboardDefault = isInputMethodDefault(context)
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
        if (connectionStatus == "Connected" && autoHideApp) {
            // Short delay so the user sees the "Connected" state, then hide
            delay(800)
            activity?.moveTaskToBack(true)
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
            putBoolean("auto_hide_app",     autoHideApp)
            putBoolean("mac_server_mode",   isMacServerMode)
            putBoolean("clipboard_sync",    clipboardSyncEnabled)
            putBoolean("focus_app_on_type",  focusAppOnType)
            putBoolean("force_focus_client", forceFocusEnabled)
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
        com.example.synergyclient.service.SynergyInputMethodService.instance?.switchToPreviousKeyboard()
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

        // ── Keyboard warning ──────────────────────────────────────────────
        AnimatedVisibility(
            visible = !isKeyboardEnabled || !isKeyboardDefault,
            enter   = slideInVertically() + fadeIn(),
            exit    = slideOutVertically() + fadeOut()
        ) {
            KeyboardWarningBanner(
                context = context,
                isKeyboardEnabled = isKeyboardEnabled,
                isKeyboardDefault = isKeyboardDefault
            )
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

        // ── Keyboard settings ──────────────────────────────────────────────
        KeyboardCard(
            isKeyboardEnabled = isKeyboardEnabled,
            isKeyboardDefault = isKeyboardDefault,
            autoHideKeyboard = autoHideKeyboard,
            onAutoHideChange = { checked ->
                autoHideKeyboard = checked
                prefs.edit().putBoolean("auto_hide_keyboard", checked).apply()
            },
            onEnableClick = {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)
                )
            },
            onSetDefaultClick = {
                val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showInputMethodPicker()
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
            autoHideApp     = autoHideApp,
            focusAppOnType  = focusAppOnType,
            forceFocusEnabled = forceFocusEnabled,
            isConnected     = isConnected,
            isConnecting    = isConnecting,
            onServerIpChange     = { serverIp = it },
            onPortChange         = { port = it },
            onClientNameChange   = { clientName = it },
            onAutoReconnectChange = { autoReconnect = it },
            onLoggingChange      = { loggingEnabled = it },
            onMacModeChange      = { isMacServerMode = it },
            onClipboardSyncChange = { clipboardSyncEnabled = it },
            onAutoHideAppChange  = { checked ->
                autoHideApp = checked
                prefs.edit().putBoolean("auto_hide_app", checked).apply()
            },
            onFocusAppChange     = { checked ->
                focusAppOnType = checked
                prefs.edit().putBoolean("focus_app_on_type", checked).apply()
            },
            onForceFocusChange   = { checked ->
                forceFocusEnabled = checked
                prefs.edit().putBoolean("force_focus_client", checked).apply()
            },
            onConnectClick       = { if (isConnected || isConnecting) disconnect() else connect() }
        )

        // ── Developer, Debugging & Pairing options ────────────────────────
        val localIp = remember { getLocalIpAddress() }
        DeveloperCard(
            context = context,
            localIp = localIp
        )

        // ── Shared Editor & Sticky Notes Action ────────────────────────────
        Surface(
            shape    = RoundedCornerShape(12.dp),
            color    = Card,
            border   = androidx.compose.foundation.BorderStroke(1.dp, Border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("SHARED TEXT EDITOR & STICKY NOTES", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Muted, letterSpacing = 1.5.sp)
                    Text("Create, edit and sync notes across devices", fontSize = 10.sp, color = TextCol.copy(alpha = 0.7f))
                }
                Button(
                    onClick = { showNotesDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("OPEN NOTES", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        if (showNotesDialog) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showNotesDialog = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    color = Card,
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border)
                ) {
                    TextEditorAndClipCard(context = context, onDismiss = { showNotesDialog = false })
                }
            }
        }

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
    autoHideApp:     Boolean,
    focusAppOnType:  Boolean,
    forceFocusEnabled: Boolean,
    isConnected:     Boolean,
    isConnecting:    Boolean,
    onServerIpChange:      (String)  -> Unit,
    onPortChange:          (String)  -> Unit,
    onClientNameChange:    (String)  -> Unit,
    onAutoReconnectChange: (Boolean) -> Unit,
    onLoggingChange:       (Boolean) -> Unit,
    onMacModeChange:       (Boolean) -> Unit,
    onClipboardSyncChange: (Boolean) -> Unit,
    onAutoHideAppChange:   (Boolean) -> Unit,
    onFocusAppChange:      (Boolean) -> Unit,
    onForceFocusChange:    (Boolean) -> Unit,
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
                    disabledTextColor     = TextCol.copy(alpha = 0.6f),
                    focusedLabelColor     = Accent,  unfocusedLabelColor = Muted,
                    disabledLabelColor    = Muted.copy(alpha = 0.6f),
                    disabledBorderColor   = Border.copy(alpha = 0.5f)
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
                        focusedTextColor = TextCol, unfocusedTextColor = TextCol,
                        disabledTextColor = TextCol.copy(alpha = 0.6f),
                        disabledLabelColor = Muted.copy(alpha = 0.6f),
                        disabledBorderColor = Border.copy(alpha = 0.5f)
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
                        focusedTextColor = TextCol, unfocusedTextColor = TextCol,
                        disabledTextColor = TextCol.copy(alpha = 0.6f),
                        disabledLabelColor = Muted.copy(alpha = 0.6f),
                        disabledBorderColor = Border.copy(alpha = 0.5f)
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
            ToggleRow("Auto-minimize App", "Send app to background when connected", autoHideApp, onAutoHideAppChange)
            ToggleRow("Focus app on typing", "Focus Synergy client when key is typed", focusAppOnType, onFocusAppChange)
            ToggleRow("Auto-Focus on Touch", "Touch or typing on this client instantly pulls focus", forceFocusEnabled, onForceFocusChange)

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

// ── Keyboard status check helpers ───────────────────────────────────────────
private fun isInputMethodEnabled(context: android.content.Context): Boolean {
    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
    val enabledIMEs = imm.enabledInputMethodList
    val pkg = context.packageName
    return enabledIMEs.any { it.id.contains(pkg) && it.id.contains("SynergyInputMethodService") }
}

private fun isInputMethodDefault(context: android.content.Context): Boolean {
    val defaultIME = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
    ) ?: return false
    val pkg = context.packageName
    return defaultIME.contains(pkg) && defaultIME.contains("SynergyInputMethodService")
}

@Composable
private fun KeyboardWarningBanner(
    context: android.content.Context,
    isKeyboardEnabled: Boolean,
    isKeyboardDefault: Boolean
) {
    val bannerColor = if (!isKeyboardEnabled) Red else Amber
    val bannerBg = if (!isKeyboardEnabled) Color(0xFF2A1F1F) else Color(0xFF2B251F)
    val text = if (!isKeyboardEnabled) "Synergy Keyboard is disabled" else "Synergy Keyboard not selected"
    val subtext = if (!isKeyboardEnabled) "Enable the virtual keyboard in system settings." else "Set Synergy Keyboard as default to type."
    val btnText = if (!isKeyboardEnabled) "ENABLE" else "SET DEFAULT"

    Surface(
        shape  = RoundedCornerShape(10.dp),
        color  = bannerBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, bannerColor.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text, fontWeight = FontWeight.Bold, color = bannerColor, fontSize = 13.sp)
                Text(subtext, fontSize = 11.sp, color = bannerColor.copy(alpha = 0.7f))
            }
            Button(
                onClick = {
                    if (!isKeyboardEnabled) {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)
                        )
                    } else {
                        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.showInputMethodPicker()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = bannerColor),
                shape  = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(btnText, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

@Composable
private fun KeyboardCard(
    isKeyboardEnabled: Boolean,
    isKeyboardDefault: Boolean,
    autoHideKeyboard: Boolean,
    onAutoHideChange: (Boolean) -> Unit,
    onEnableClick: () -> Unit,
    onSetDefaultClick: () -> Unit,
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
            Text("SYNERGY KEYBOARD", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = Muted, letterSpacing = 1.5.sp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Keyboard Status", fontSize = 13.sp, color = TextCol)
                    Text(
                        text = when {
                            !isKeyboardEnabled -> "Not Enabled in system settings"
                            !isKeyboardDefault -> "Enabled, but not set as default"
                            else -> "✓ Active and set as default keyboard"
                        },
                        fontSize = 11.sp,
                        color = when {
                            !isKeyboardEnabled -> Red
                            !isKeyboardDefault -> Amber
                            else -> Green
                        }
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isKeyboardEnabled) {
                        Button(
                            onClick = onEnableClick,
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("ENABLE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    } else if (!isKeyboardDefault) {
                        Button(
                            onClick = onSetDefaultClick,
                            colors = ButtonDefaults.buttonColors(containerColor = Amber),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("SET DEFAULT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onSetDefaultClick,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Muted),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("SWITCH IME", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(1.dp).background(Border).fillMaxWidth())

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-hide soft keyboard", fontSize = 12.sp, color = TextCol)
                    Text("Hide on-screen keyboard when Synergy typing is active", fontSize = 10.sp, color = Muted)
                }
                Switch(
                    checked = autoHideKeyboard,
                    onCheckedChange = onAutoHideChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Accent,
                        uncheckedThumbColor = Muted,
                        uncheckedTrackColor = Border
                    )
                )
            }
        }
    }
}

// ── Text Editor & Sticky Notes & Clipboard History Card ─────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextEditorAndClipCard(
    context: android.content.Context,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var files by remember { mutableStateOf(emptyList<String>()) }
    var selectedFile by remember { mutableStateOf("") }
    var editorContent by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf("yellow") } // yellow, blue, pink, green, purple
    var newFileName by remember { mutableStateOf("") }
    var clips by remember { mutableStateOf(emptyList<String>()) }

    // Colors mapping
    val noteColors = mapOf(
        "yellow" to Color(0xFFFFF9C4),
        "blue"   to Color(0xFFB3E5FC),
        "pink"   to Color(0xFFF8BBD0),
        "green"  to Color(0xFFC8E6C9),
        "purple" to Color(0xFFE1BEE7)
    )

    fun loadFiles() {
        files = com.example.synergyclient.data.NotesManager.getTextFiles(context)
        if (selectedFile.isEmpty() && files.isNotEmpty()) {
            selectedFile = files[0]
        }
    }

    fun refreshEditor() {
        if (selectedFile.isNotEmpty()) {
            val raw = com.example.synergyclient.data.NotesManager.readFile(context, selectedFile)
            if (raw.startsWith("#color:")) {
                val parts = raw.split("\n", limit = 2)
                selectedColor = parts[0].substringAfter("#color:").trim()
                editorContent = if (parts.size > 1) parts[1] else ""
            } else {
                selectedColor = "yellow"
                editorContent = raw
            }
        } else {
            editorContent = ""
        }
    }

    LaunchedEffect(Unit) {
        loadFiles()
        clips = com.example.synergyclient.data.NotesManager.getClipHistory(context)
    }

    LaunchedEffect(selectedFile) {
        refreshEditor()
    }

    LaunchedEffect(Unit) {
        while (true) {
            clips = com.example.synergyclient.data.NotesManager.getClipHistory(context)
            loadFiles()
            kotlinx.coroutines.delay(2000)
        }
    }

    Surface(
        shape    = RoundedCornerShape(12.dp),
        color    = Card,
        border   = androidx.compose.foundation.BorderStroke(1.dp, Border),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("STICKY NOTES & TEXT EDITOR", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = Muted, letterSpacing = 1.5.sp)
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Text("✕", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            val localIp = remember { com.example.synergyclient.network.SynergyNetworkService.getLocalIpAddress() }
            if (localIp.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Bg)
                        .border(1.dp, Border, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Web Shared Sync Server Active", color = Green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text("Open in browser: http://$localIp:8080", color = TextCol, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("web_url", "http://$localIp:8080")
                            clipboard.setPrimaryClip(clip)
                        },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("COPY URL", fontSize = 10.sp, color = Accent, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // ── Section 1: Sticky Notes Horizontal Desk View ────────────────
            if (files.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    files.forEachIndexed { idx, name ->
                        val rawContent = com.example.synergyclient.data.NotesManager.readFile(context, name)
                        val noteColorName = if (rawContent.startsWith("#color:")) {
                            rawContent.split("\n", limit = 2)[0].substringAfter("#color:").trim()
                        } else "yellow"
                        val noteTextClean = if (rawContent.startsWith("#color:")) {
                            rawContent.split("\n", limit = 2).getOrNull(1) ?: ""
                        } else rawContent

                        val noteColor = noteColors[noteColorName] ?: Color(0xFFFFF9C4)
                        
                        val rot = when (idx % 4) {
                            0 -> -2f
                            1 -> 1.5f
                            2 -> -1f
                            else -> 2f
                        }

                        Column(
                            modifier = Modifier
                                .size(width = 110.dp, height = 110.dp)
                                .rotate(rot)
                                .clip(RoundedCornerShape(4.dp))
                                .background(noteColor)
                                .clickable { selectedFile = name }
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("📌", fontSize = 12.sp)
                            Text(
                                text = if (noteTextClean.isBlank()) name.substringBefore(".txt") else noteTextClean,
                                color = Color.DarkGray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            // ── Section 2: Create a New Note File ──────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Bg)
                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text("New file name (e.g. todo)", color = Muted, fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Border,
                            focusedTextColor = TextCol, unfocusedTextColor = TextCol
                        ),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            var cleanName = newFileName.trim().replace("[^a-zA-Z0-9-_]".toRegex(), "")
                            if (cleanName.isNotEmpty()) {
                                if (!cleanName.endsWith(".txt")) {
                                    cleanName += ".txt"
                                }
                                val initialData = "#color:$selectedColor\n"
                                com.example.synergyclient.data.NotesManager.saveFile(context, cleanName, initialData)
                                com.example.synergyclient.network.NotesWebServer.activeInstance?.syncWithPeers(cleanName, initialData, false)
                                loadFiles()
                                selectedFile = cleanName
                                newFileName = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text("CREATE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sticky Color:", fontSize = 11.sp, color = Muted)
                    noteColors.forEach { (colorName, colorVal) ->
                        val isSelected = selectedColor == colorName
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(colorVal)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Accent else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable {
                                    selectedColor = colorName
                                    if (selectedFile.isNotEmpty()) {
                                        val toSave = "#color:$colorName\n$editorContent"
                                        com.example.synergyclient.data.NotesManager.saveFile(context, selectedFile, toSave)
                                        com.example.synergyclient.network.NotesWebServer.activeInstance?.syncWithPeers(selectedFile, toSave, false)
                                        loadFiles()
                                    }
                                }
                        )
                    }
                }
            }

            // ── Section 3: Text Editor Area ────────────────────────────────
            if (selectedFile.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Editing: ${selectedFile.substringBefore(".txt")}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextCol
                        )
                        Text(
                            text = "Delete File",
                            fontSize = 11.sp,
                            color = Red,
                            modifier = Modifier.clickable {
                                val fileToDelete = selectedFile
                                com.example.synergyclient.data.NotesManager.deleteFile(context, fileToDelete)
                                com.example.synergyclient.network.NotesWebServer.activeInstance?.syncWithPeers(fileToDelete, "", true)
                                selectedFile = ""
                                loadFiles()
                                refreshEditor()
                            }
                        )
                    }

                    val editorBg = noteColors[selectedColor]?.copy(alpha = 0.08f) ?: Bg

                    OutlinedTextField(
                        value = editorContent,
                        onValueChange = {
                            editorContent = it
                            val toSave = "#color:$selectedColor\n$it"
                            com.example.synergyclient.data.NotesManager.saveFile(context, selectedFile, toSave)
                            com.example.synergyclient.network.NotesWebServer.activeInstance?.syncWithPeers(selectedFile, toSave, false)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Border,
                            focusedTextColor = TextCol, unfocusedTextColor = TextCol,
                            unfocusedContainerColor = editorBg, focusedContainerColor = editorBg
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 220.dp),
                        singleLine = false
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Flowport Editor", editorContent))
                                com.example.synergyclient.data.NotesManager.addClipItem(context, editorContent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("SYNC TO MAC (COPY)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }

            // ── Section 4: Clipboard History List ──────────────────────────
            if (clips.isNotEmpty()) {
                HorizontalDivider(color = Border, thickness = 1.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Clipboard History (Click to Sync)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextCol)
                    Text(
                        text = "Clear History",
                        fontSize = 11.sp,
                        color = Red,
                        modifier = Modifier.clickable {
                            com.example.synergyclient.data.NotesManager.clearClipHistory(context)
                            clips = emptyList()
                        }
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    clips.take(5).forEach { clip ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Bg)
                                .border(1.dp, Border, RoundedCornerShape(8.dp))
                                .clickable {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Flowport History", clip))
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = clip,
                                fontSize = 11.sp,
                                color = Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun getLocalIpAddress(): String {
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                    val ip = address.hostAddress ?: ""
                    if (ip.isNotEmpty()) return ip
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "Unavailable"
}

@Composable
private fun DeveloperCard(
    context: android.content.Context,
    localIp: String,
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
            Text("DEVELOPER, DEBUGGING & PAIRING", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = Muted, letterSpacing = 1.5.sp)

            // IP Address Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Device IP Address", fontSize = 13.sp, color = TextCol)
                    Text("Your server can connect to this IP", fontSize = 11.sp, color = Muted)
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Bg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border)
                ) {
                    Text(
                        text = localIp,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Accent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(1.dp).background(Border).fillMaxWidth())

            // Debugging Buttons Grid/Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // USB Debugging
                Button(
                    onClick = {
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
                            } catch (_: Exception) {}
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.3f))
                ) {
                    Text("USB Debugging", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentB)
                }

                // Wireless Debugging
                Button(
                    onClick = {
                        try {
                            val intent = android.content.Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Green.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Green.copy(alpha = 0.3f))
                ) {
                    Text("Wireless Debug", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Green)
                }
            }

            // Developer options & Pairing
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Card),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border)
                ) {
                    Text("System Settings", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextCol)
                }

                Button(
                    onClick = {
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
                            } catch (_: Exception) {}
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Amber.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Amber.copy(alpha = 0.3f))
                ) {
                    Text("Device Pairing", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Amber)
                }
            }
        }
    }
}
