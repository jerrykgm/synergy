package com.example.synergyclient.ui.main

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.example.synergyclient.network.SynergyNetworkService
import com.example.synergyclient.service.SynergyAccessibilityService
import com.example.synergyclient.theme.SynergyClientTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MAX_LOGS = 200

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefs = remember { context.getSharedPreferences("synergy_prefs", android.content.Context.MODE_PRIVATE) }

    // ── Persistent settings ────────────────────────────────────────────────
    var serverIp     by remember { mutableStateOf(prefs.getString("server_ip", "10.40.194.37") ?: "10.40.194.37") }
    var port         by remember { mutableStateOf(prefs.getString("port", "24800") ?: "24800") }
    var clientName   by remember { mutableStateOf(prefs.getString("client_name", "AndroidClient") ?: "AndroidClient") }
    var autoReconnect by remember { mutableStateOf(prefs.getBoolean("auto_reconnect", true)) }
    var loggingEnabled by remember { mutableStateOf(prefs.getBoolean("logging_enabled", true)) }

    // ── Connection state ───────────────────────────────────────────────────
    var connectionStatus by remember { mutableStateOf("Disconnected") }
    val isConnected  = connectionStatus == "Connected"
    val isConnecting = connectionStatus == "Connecting..."

    // ── Services ───────────────────────────────────────────────────────────
    var networkService by remember { mutableStateOf<SynergyNetworkService?>(null) }
    var isAccessibilityEnabled by remember { mutableStateOf(false) }

    // ── Log ring buffer (capped at MAX_LOGS) ──────────────────────────────
    val logs = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    fun addLog(msg: String) {
        val lines = msg.split("\n").filter { it.isNotBlank() }
        for (line in lines) {
            logs.add(line)
            if (logs.size > MAX_LOGS) logs.removeAt(0)
        }
    }

    // ── Poll accessibility service availability ────────────────────────────
    LaunchedEffect(Unit) {
        while (true) {
            isAccessibilityEnabled = SynergyAccessibilityService.instance != null
            delay(1000)
        }
    }

    // ── Auto-scroll log to bottom ──────────────────────────────────────────
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    // ── Cleanup on dispose ─────────────────────────────────────────────────
    DisposableEffect(Unit) {
        onDispose { networkService?.stop() }
    }

    // Save settings helper
    fun saveSettings() {
        prefs.edit().apply {
            putString("server_ip", serverIp)
            putString("port", port)
            putString("client_name", clientName)
            putBoolean("auto_reconnect", autoReconnect)
            putBoolean("logging_enabled", loggingEnabled)
            apply()
        }
    }


    // ── Connect / Disconnect logic ─────────────────────────────────────────
    fun connect() {
        saveSettings()
        // Always stop any existing service first to prevent duplicate connections
        networkService?.stop()
        networkService = null
        connectionStatus = "Connecting..."

        val portNum = port.toIntOrNull() ?: 24800
        val svc = SynergyNetworkService(
            host = serverIp,
            port = portNum,
            clientName = clientName,
            context = context,
            loggingEnabled = loggingEnabled,
            onLog = { msg -> addLog(msg) },
            onStatusChange = { status ->
                connectionStatus = status
                // Auto-reconnect only if service was not manually stopped (networkService != null)
                if (status == "Disconnected" && autoReconnect && networkService != null) {
                    scope.launch {
                        delay(3000)
                        // Re-check: user might have disconnected manually during the delay
                        if (connectionStatus == "Disconnected" && networkService != null) {
                            addLog("Auto-reconnecting...")
                            connect()
                        }
                    }
                }
            }
        )
        networkService = svc
        svc.start()
    }

    fun disconnect() {
        networkService?.stop()
        networkService = null
    }

    // ── mDNS Auto Discovery ────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        val discovery = com.example.synergyclient.network.SynergyServerDiscovery(context) { host, p ->
            scope.launch {
                addLog("Auto-discovered server at $host:$p")
                serverIp = host
                port = p.toString()
                saveSettings()
                
                // Automatically connect if not already connected/connecting
                if (connectionStatus == "Disconnected") {
                    addLog("Auto-connecting to discovered server...")
                    connect()
                }
            }
        }
        discovery.start()
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            discovery.stop()
        }
    }

    // ── Design tokens ──────────────────────────────────────────────────────
    val bg      = Color(0xFF0D0E14)
    val surface = Color(0xFF14151F)
    val card    = Color(0xFF1A1B27)
    val accent  = Color(0xFF6C63FF)
    val accentB = Color(0xFF8B85FF)
    val green   = Color(0xFF2ECC71)
    val amber   = Color(0xFFF39C12)
    val red     = Color(0xFFE74C3C)
    val text    = Color(0xFFECEFF4)
    val muted   = Color(0xFF6B7280)
    val border  = Color(0xFF252636)

    val statusColor = when (connectionStatus) {
        "Connected"    -> green
        "Connecting..." -> amber
        else            -> red
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(bg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ── Header ─────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "SYNERGY",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = text, letterSpacing = 3.sp
                )
                Text(
                    "Android Client  •  v1.0",
                    fontSize = 12.sp, color = muted
                )
            }
            // Live status pill
            AnimatedContent(
                targetState = connectionStatus,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "status"
            ) { status ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = statusColor.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(7.dp).clip(CircleShape)
                                .background(statusColor)
                        )
                        Text(status, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = statusColor)
                    }
                }
            }
        }

        // ── Accessibility warning ──────────────────────────────────────────
        AnimatedVisibility(visible = !isAccessibilityEnabled) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF2A1F1F),
                border = androidx.compose.foundation.BorderStroke(1.dp, red.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Accessibility Service Required",
                            fontWeight = FontWeight.Bold, color = red, fontSize = 13.sp)
                        Text("Needed for cursor, keyboard & clipboard.",
                            fontSize = 11.sp, color = red.copy(alpha = 0.7f))
                    }
                    Button(
                        onClick = {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = red),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("ENABLE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── Connection settings ────────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = card,
            border = androidx.compose.foundation.BorderStroke(1.dp, border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("CONNECTION", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = muted, letterSpacing = 1.5.sp)

                OutlinedTextField(
                    value = serverIp, onValueChange = { serverIp = it },
                    label = { Text("Server IP", color = muted, fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent, unfocusedBorderColor = border,
                        focusedTextColor = text, unfocusedTextColor = text,
                        focusedLabelColor = accent, unfocusedLabelColor = muted
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isConnected && !isConnecting,
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = port, onValueChange = { port = it },
                        label = { Text("Port", color = muted, fontSize = 13.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accent, unfocusedBorderColor = border,
                            focusedTextColor = text, unfocusedTextColor = text
                        ),
                        modifier = Modifier.weight(1f),
                        enabled = !isConnected && !isConnecting,
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = clientName, onValueChange = { clientName = it },
                        label = { Text("Screen Name", color = muted, fontSize = 13.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accent, unfocusedBorderColor = border,
                            focusedTextColor = text, unfocusedTextColor = text
                        ),
                        modifier = Modifier.weight(2f),
                        enabled = !isConnected && !isConnecting,
                        singleLine = true
                    )
                }
                // Auto-reconnect toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Auto-reconnect", fontSize = 13.sp, color = text)
                        Text("Reconnect after 3s if dropped", fontSize = 11.sp, color = muted)
                    }
                    Switch(
                        checked = autoReconnect,
                        onCheckedChange = { autoReconnect = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = accent
                        )
                    )
                }

                // Logging toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Connection Logs", fontSize = 13.sp, color = text)
                        Text("Disable logs for faster communication", fontSize = 11.sp, color = muted)
                    }
                    Switch(
                        checked = loggingEnabled,
                        onCheckedChange = { loggingEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = accent
                        )
                    )
                }

                // Connect button
                val btnGradient = if (isConnected)
                    Brush.horizontalGradient(listOf(red, Color(0xFFC0392B)))
                else
                    Brush.horizontalGradient(listOf(accent, accentB))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(btnGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = {
                            if (isConnected || isConnecting) disconnect() else connect()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        elevation = ButtonDefaults.buttonElevation(0.dp),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White, strokeWidth = 2.dp
                            )
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

        // ── Activity log ──────────────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF0A0B10),
            border = androidx.compose.foundation.BorderStroke(1.dp, border),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ACTIVITY LOG", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = muted, letterSpacing = 1.5.sp)
                    TextButton(
                        onClick = { logs.clear() },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("Clear", fontSize = 10.sp, color = muted)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            color = when {
                                line.contains("Connected") || line.contains("DSOP") -> green
                                line.contains("Error") || line.contains("Disconnected") -> red.copy(alpha = 0.8f)
                                line.startsWith("→") -> accentB
                                else -> text.copy(alpha = 0.7f)
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
