package com.example.synergyclient.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

class SynergyNetworkService(
    private val host: String,
    private val port: Int,
    private val clientName: String,
    private val onLog: (String) -> Unit,
    private val onStatusChange: (String) -> Unit
) {
    private var socket: Socket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        job = scope.launch {
            try {
                onStatusChange("Connecting...")
                onLog("Opening socket connection to $host:$port...")
                
                val s = Socket(host, port)
                socket = s
                onLog("TCP connection established. Waiting for server hello...")

                val input = DataInputStream(s.getInputStream())
                val output = DataOutputStream(s.getOutputStream())

                // 1. Read Server Hello ("Synergy" + major + minor)
                val helloLen = input.readInt() // 4-byte length (should be 11)
                if (helloLen < 11) {
                    throw Exception("Invalid Hello packet length: $helloLen")
                }
                
                val helloCode = ByteArray(7)
                input.readFully(helloCode)
                val helloCodeStr = String(helloCode, StandardCharsets.US_ASCII)
                onLog("Received protocol: $helloCodeStr")

                val major = input.readShort()
                val minor = input.readShort()
                onLog("Server Synergy Version: $major.$minor")

                // 2. Send Client Hello
                onLog("Sending Client Hello for '$clientName'...")
                val nameBytes = clientName.toByteArray(StandardCharsets.US_ASCII)
                // Client Hello payload: "Synergy" (7) + major (2) + minor (2) + nameLen (4) + nameBytes
                val responseLen = 7 + 2 + 2 + 4 + nameBytes.size
                
                output.writeInt(responseLen)
                output.writeBytes("Synergy")
                output.writeShort(major.toInt())
                output.writeShort(minor.toInt())
                output.writeInt(nameBytes.size)
                output.write(nameBytes)
                output.flush()

                onStatusChange("Connected")
                onLog("Client greeting sent successfully. Handshake complete.")

                // 3. Receive Packet loop
                while (s.isConnected && !s.isClosed) {
                    val packetLen = input.readInt()
                    if (packetLen <= 0) break

                    val command = ByteArray(4)
                    input.readFully(command)
                    val cmdStr = String(command, StandardCharsets.US_ASCII)

                    // Skip the rest of the payload
                    val payloadLen = packetLen - 4
                    val payload = ByteArray(payloadLen)
                    if (payloadLen > 0) {
                        input.readFully(payload)
                    }

                    handleCommand(cmdStr, payload)
                }

            } catch (e: Exception) {
                onLog("Error: ${e.message}")
                onStatusChange("Disconnected")
            } finally {
                close()
            }
        }
    }

    private fun handleCommand(cmd: String, payload: ByteArray) {
        when (cmd) {
            "Cnob" -> {
                // Heartbeat / Noop
                onLog("Heartbeat (Cnob) received.")
            }
            "Qinf" -> {
                onLog("Server queried screen info (Qinf). Sending screen specs...")
                // Reply with Dinf (Device Info)
                try {
                    val out = DataOutputStream(socket?.getOutputStream() ?: return)
                    // Dinf: "Dinf" (4 bytes) + x (2 bytes) + y (2 bytes) + w (2 bytes) + h (2 bytes) + padding (2 bytes)
                    val responseLen = 4 + 2 + 2 + 2 + 2 + 2
                    out.writeInt(responseLen)
                    out.writeBytes("Dinf")
                    out.writeShort(0) // x
                    out.writeShort(0) // y
                    out.writeShort(1080) // width
                    out.writeShort(2400) // height
                    out.writeShort(0) // dummy padding
                    out.flush()
                } catch (e: Exception) {
                    onLog("Error sending info: ${e.message}")
                }
            }
            "Cack" -> {
                onLog("Received Connection Acknowledged (Cack). Connection fully ready.")
            }
            "Dmmv" -> {
                // Mouse move
                if (payload.size >= 4) {
                    val x = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                    val y = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
                    onLog("Mouse Move -> X: $x, Y: $y")
                    com.example.synergyclient.service.SynergyAccessibilityService.instance?.updateCursor(x, y)
                } else {
                    onLog("Mouse Move Event received.")
                }
            }
            "Dbbp" -> {
                // Button press (e.g. left click)
                if (payload.isNotEmpty()) {
                    val buttonId = payload[0].toInt()
                    onLog("Button Press -> ID: $buttonId")
                    if (buttonId == 1) { // Left click
                        com.example.synergyclient.service.SynergyAccessibilityService.instance?.clickCursor()
                    }
                }
            }
            "Dbbr" -> {
                // Button release
                if (payload.isNotEmpty()) {
                    onLog("Button Release -> ID: ${payload[0].toInt()}")
                }
            }
            else -> {
                onLog("Received command: $cmd (payload size: ${payload.size} bytes)")
            }
        }
    }

    fun stop() {
        close()
    }

    private fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        job?.cancel()
        job = null
        onStatusChange("Disconnected")
    }
}
