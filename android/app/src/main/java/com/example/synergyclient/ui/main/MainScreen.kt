package com.example.synergyclient.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import androidx.compose.ui.platform.LocalContext
import com.example.synergyclient.network.SynergyNetworkService
import com.example.synergyclient.theme.SynergyClientTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var serverIp by remember { mutableStateOf("192.168.1.100") }
  var port by remember { mutableStateOf("24800") }
  var clientName by remember { mutableStateOf("AndroidClient") }
  var isConnected by remember { mutableStateOf(false) }
  var connectionStatus by remember { mutableStateOf("Disconnected") }
  val logs = remember { mutableStateListOf<String>("System ready. Enter Server IP to connect.") }
  val coroutineScope = rememberCoroutineScope()
  var networkService by remember { mutableStateOf<SynergyNetworkService?>(null) }
  var isAccessibilityEnabled by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    while (true) {
      isAccessibilityEnabled = com.example.synergyclient.service.SynergyAccessibilityService.instance != null
      delay(1000)
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      networkService?.stop()
    }
  }

  val backgroundColor = Color(0xFF0F1015)
  val cardColor = Color(0xFF171821)
  val accentColor = Color(0xFFFF7200)
  val textColor = Color(0xFFF1F2F6)
  val mutedTextColor = Color(0xFFA0A5B5)

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(backgroundColor)
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // Header
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
      Text(
        text = "SYNERGY",
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = accentColor,
        letterSpacing = 2.sp
      )
      Text(
        text = "Android Client",
        fontSize = 14.sp,
        color = mutedTextColor
      )
    }

    // Accessibility Offline Warning Card
    if (!isAccessibilityEnabled) {
      Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1E1E)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        Row(
          modifier = Modifier.padding(12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Accessibility Service Offline", fontWeight = FontWeight.Bold, color = Color(0xFFF78E8E))
            Text("Required to draw cursor and inject clicks.", fontSize = 12.sp, color = Color(0xFFE2A0A0))
          }
          Button(
            onClick = {
              context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
            shape = RoundedCornerShape(6.dp)
          ) {
            Text("ENABLE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
          }
        }
      }
    }

    // Config Card
    Card(
      colors = CardDefaults.cardColors(containerColor = cardColor),
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text(
          text = "CONNECTION SETTINGS",
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          color = mutedTextColor,
          letterSpacing = 1.sp
        )

        OutlinedTextField(
          value = serverIp,
          onValueChange = { serverIp = it },
          label = { Text("Server IP Address", color = mutedTextColor) },
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accentColor,
            unfocusedBorderColor = Color(0xFF2D303F),
            focusedTextColor = textColor,
            unfocusedTextColor = textColor
          ),
          modifier = Modifier.fillMaxWidth(),
          enabled = !isConnected && connectionStatus != "Connecting..."
        )

        Row(
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Port", color = mutedTextColor) },
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = accentColor,
              unfocusedBorderColor = Color(0xFF2D303F),
              focusedTextColor = textColor,
              unfocusedTextColor = textColor
            ),
            modifier = Modifier.weight(1f),
            enabled = !isConnected && connectionStatus != "Connecting..."
          )

          OutlinedTextField(
            value = clientName,
            onValueChange = { clientName = it },
            label = { Text("Client Name", color = mutedTextColor) },
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = accentColor,
              unfocusedBorderColor = Color(0xFF2D303F),
              focusedTextColor = textColor,
              unfocusedTextColor = textColor
            ),
            modifier = Modifier.weight(2.5f),
            enabled = !isConnected && connectionStatus != "Connecting..."
          )
        }

        // Connection Action Button
        Button(
          onClick = {
            if (isConnected || connectionStatus == "Connecting...") {
              networkService?.stop()
              networkService = null
            } else {
              val portNum = port.toIntOrNull() ?: 24800
              val service = SynergyNetworkService(
                host = serverIp,
                port = portNum,
                clientName = clientName,
                onLog = { logMsg ->
                  logs.add(logMsg)
                },
                onStatusChange = { status ->
                  connectionStatus = status
                  isConnected = (status == "Connected")
                }
              )
              networkService = service
              service.start()
            }
          },
          colors = ButtonDefaults.buttonColors(
            containerColor = if (isConnected) Color(0xFFB3261E) else accentColor
          ),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
          Text(
            text = when (connectionStatus) {
              "Connecting..." -> "CONNECTING..."
              "Connected" -> "DISCONNECT"
              else -> "CONNECT"
            },
            fontWeight = FontWeight.Bold,
            color = Color.White
          )
        }
      }
    }

    // Status Indicator
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(cardColor)
        .padding(12.dp)
    ) {
      Box(
        modifier = Modifier
          .size(10.dp)
          .clip(RoundedCornerShape(5.dp))
          .background(
            when (connectionStatus) {
              "Connected" -> Color(0xFF5AF78E)
              "Connecting..." -> Color(0xFFFFC107)
              else -> Color(0xFF686C80)
            }
          )
      )
      Text(
        text = "Status: $connectionStatus",
        color = textColor,
        fontWeight = FontWeight.Medium
      )
    }

    // Log Output Card
    Card(
      colors = CardDefaults.cardColors(containerColor = Color(0xFF08090C)),
      shape = RoundedCornerShape(8.dp),
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .border(1.dp, Color(0xFF1C1D24), RoundedCornerShape(8.dp))
    ) {
      Column(
        modifier = Modifier.padding(12.dp)
      ) {
        Text(
          text = "ACTIVITY LOG",
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          color = mutedTextColor,
          modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          items(logs.toList().asReversed()) { log ->
            Text(
              text = log,
              color = if (log.contains("Connected")) Color(0xFF5AF78E) else textColor,
              fontFamily = FontFamily.Monospace,
              fontSize = 12.sp
            )
          }
        }
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
  SynergyClientTheme {
    MainScreen(onItemClick = {})
  }
}

