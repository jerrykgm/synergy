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
import com.example.synergyclient.theme.SynergyClientTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
) {
  var serverIp by remember { mutableStateOf("192.168.1.100") }
  var port by remember { mutableStateOf("24800") }
  var clientName by remember { mutableStateOf("AndroidClient") }
  var isConnected by remember { mutableStateOf(false) }
  var connectionStatus by remember { mutableStateOf("Disconnected") }
  val logs = remember { mutableStateListOf<String>("System ready. Enter Server IP to connect.") }
  val coroutineScope = rememberCoroutineScope()

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
              // Disconnect
              isConnected = false
              connectionStatus = "Disconnected"
              logs.add("Disconnected from server.")
            } else {
              // Connect
              coroutineScope.launch {
                connectionStatus = "Connecting..."
                logs.add("Connecting to server at $serverIp:$port...")
                delay(1000)
                logs.add("Sending client info '$clientName'...")
                delay(800)
                logs.add("Performing secure TLS handshake...")
                delay(1000)
                isConnected = true
                connectionStatus = "Connected"
                logs.add("Connected successfully! Ready to receive input.")
              }
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

