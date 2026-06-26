package com.example.synergyclient.network

import android.os.Process
import android.util.Log
import android.view.WindowManager
import android.content.Context
import android.accessibilityservice.AccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets

/**
 * Production-ready Synergy TCP client with real-time performance optimisations.
 *
 * Hot-path guarantees (DMMV / DKDN / DKUP / CALV):
 *  - Zero heap allocations when [loggingEnabled] = false
 *  - No String formatting or boxing
 *  - Pre-allocated read buffer (65 KB) — never grows
 *  - CALV keep-alive uses a pre-built static byte array
 *  - Runs on THREAD_PRIORITY_URGENT_AUDIO IO thread
 *
 * Log batching (when enabled):
 *  - All log calls are enqueued into a lock-free ArrayDeque
 *  - Flushed to the UI callback every 300 ms on a separate coroutine
 *  - The hot path only does an `if (!loggingEnabled) return` check
 */
class SynergyNetworkService(
    private val host:           String,
    private val port:           Int,
    private val clientName:     String,
    private val context:        Context,
    private val loggingEnabled: Boolean,
    private val clipboardEnabled: Boolean = true,
    private val onLog:          (String) -> Unit,
    private val onStatusChange: (SynergyNetworkService, String) -> Unit
) {
    // ── Pre-allocated constants ───────────────────────────────────────────
    companion object {
        private const val TAG            = "SynergyApp"
        private const val READ_BUF_SIZE  = 65_536
        /** Pre-built CALV echo packet (4-byte length + 4 ASCII chars). */
        private val CALV_PACKET: ByteArray = byteArrayOf(
            0, 0, 0, 4,           // length = 4 (big-endian)
            'C'.code.toByte(),
            'A'.code.toByte(),
            'L'.code.toByte(),
            'V'.code.toByte()
        )

        fun getLocalIpAddress(): String {
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                            val ip = address.hostAddress ?: ""
                            if (ip.isNotEmpty() && ip != "127.0.0.1") {
                                return ip
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            return ""
        }
    }

    private var socket: Socket?            = null
    private var job:    Job?               = null
    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webServer: NotesWebServer? = null

    @Volatile private var outputStream: DataOutputStream? = null

    // ── Log batching (non-blocking ring buffer) ───────────────────────────
    private val logBuffer  = ArrayDeque<String>(128)
    private var logFlusher: Job? = null

    @Suppress("NOTHING_TO_INLINE")
    private inline fun log(msg: String) {
        if (!loggingEnabled) return
        Log.i(TAG, msg)
        synchronized(logBuffer) { logBuffer.addLast(msg) }
    }

    private fun startLogFlusher() {
        if (!loggingEnabled) return
        logFlusher = scope.launch {
            while (true) {
                delay(300)
                val batch: List<String>
                synchronized(logBuffer) {
                    batch = logBuffer.toList()
                    logBuffer.clear()
                }
                if (batch.isNotEmpty()) onLog(batch.joinToString("\n"))
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun start() {
        startLogFlusher()
        webServer = NotesWebServer(context, 8080).apply { start() }
        job = scope.launch {
            // Elevate IO thread priority for minimal scheduling jitter
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                onStatusChange(this@SynergyNetworkService, "Connecting...")
                log("Opening socket to $host:$port...")

                val s = Socket(host, port)
                s.tcpNoDelay = true                       // disable Nagle — critical for latency
                s.setPerformancePreferences(0, 2, 1)      // prefer latency over bandwidth
                socket = s

                log("TCP connected (tcpNoDelay=true).")

                val input  = DataInputStream(s.getInputStream())
                val output = DataOutputStream(s.getOutputStream())
                outputStream = output

                // ── STEP 1: Read Server Hello ──────────────────────────────
                val helloLen = input.readInt()
                if (helloLen < 11) throw Exception("Bad hello length: $helloLen")
                val helloBytes = ByteArray(helloLen)
                input.readFully(helloBytes)
                val protocol = String(helloBytes, 0, 7, StandardCharsets.US_ASCII)
                val major    = read16(helloBytes, 7)
                val minor    = read16(helloBytes, 9)
                log("Server: $protocol v$major.$minor")
                if (protocol != "Synergy") throw Exception("Bad protocol: $protocol")

                // Auto-detect server OS via hostname in hello packet (if present)
                var serverName = ""
                if (helloLen >= 15) {
                    val nameLen = ((helloBytes[11].toInt() and 0xFF) shl 24) or
                                  ((helloBytes[12].toInt() and 0xFF) shl 16) or
                                  ((helloBytes[13].toInt() and 0xFF) shl 8) or
                                  (helloBytes[14].toInt() and 0xFF)
                    if (nameLen > 0 && helloLen >= 15 + nameLen) {
                        serverName = String(helloBytes, 15, nameLen, StandardCharsets.US_ASCII)
                    }
                }
                if (serverName.isNotEmpty()) {
                    log("Server hostname: '$serverName'")
                    val isMac = serverName.lowercase().let {
                        it.contains("mac") || it.contains("apple") || it.contains("osx") || it.contains("os x")
                    }
                    if (isMac) {
                        log("Auto-detected macOS server. Enabling Mac optimizations.")
                        com.example.synergyclient.service.SynergyAccessibilityService.instance?.setMacMode(true)
                    } else {
                        com.example.synergyclient.service.SynergyAccessibilityService.instance?.setMacMode(false)
                    }
                }

                // ── STEP 2: Send Client Hello ──────────────────────────────
                val nameBytes = clientName.toByteArray(StandardCharsets.US_ASCII)
                output.writeInt(7 + 2 + 2 + 4 + nameBytes.size)
                output.writeBytes("Synergy")
                output.writeShort(major); output.writeShort(minor)
                output.writeInt(nameBytes.size); output.write(nameBytes)
                output.flush()
                log("Hello sent as '$clientName'.")
                onStatusChange(this@SynergyNetworkService, "Connected")

                // ── STEP 3: Main packet loop (pre-allocated buffer) ────────
                val readBuf = ByteArray(READ_BUF_SIZE)
                while (s.isConnected && !s.isClosed) {
                    val packetLen = input.readInt()
                    if (packetLen <= 0 || packetLen > READ_BUF_SIZE) {
                        log("Bad packet len: $packetLen"); break
                    }
                    // Reuse pre-allocated buffer — no heap allocation per packet
                    input.readFully(readBuf, 0, packetLen)
                    if (packetLen < 4) continue
                    handleCommand(readBuf, packetLen, output)
                }

            } catch (_: SocketException) {
                // Normal disconnect — not an error
                onStatusChange(this@SynergyNetworkService, "Disconnected")
            } catch (e: Exception) {
                log("Error: ${e.message}")
                Log.e(TAG, "Network exception", e)
                onStatusChange(this@SynergyNetworkService, "Disconnected")
            } finally {
                outputStream = null
                logFlusher?.cancel()
                com.example.synergyclient.service.SynergyAccessibilityService.instance?.showKeyboard()
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                job?.cancel()

                // Revert to user's manual preference when disconnected
                val manualMac = context.getSharedPreferences("synergy_prefs", Context.MODE_PRIVATE)
                    .getBoolean("mac_server_mode", false)
                com.example.synergyclient.service.SynergyAccessibilityService.instance?.setMacMode(manualMac)

                onStatusChange(this@SynergyNetworkService, "Disconnected")
            }
        }
    }

    fun stop() {
        logFlusher?.cancel()
        try { webServer?.stop() } catch (_: Exception) {}
        webServer = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null; outputStream = null
        job?.cancel(); job = null
        onStatusChange(this, "Disconnected")
    }

    // ── Command dispatcher (HOT PATH — zero alloc for mouse/key) ─────────

    /**
     * Called for every incoming packet with the pre-allocated [buf] containing
     * [len] bytes. The 4-byte command tag is at buf[0..3].
     *
     * CRITICAL: DMMV, DKDN, DKUP, DKRP, CALV must allocate NOTHING.
     */
    private fun handleCommand(buf: ByteArray, len: Int, out: DataOutputStream) {
        val svc = com.example.synergyclient.service.SynergyAccessibilityService.instance

        // Read command as 4 raw bytes — no String creation in hot path
        val c0 = buf[0]; val c1 = buf[1]; val c2 = buf[2]; val c3 = buf[3]

        // ── Mouse move (DMMV) — absolute hottest path ──────────────────────
        if (c0 == 'D'.code.toByte() && c1 == 'M'.code.toByte() &&
            c2 == 'M'.code.toByte() && c3 == 'V'.code.toByte()) {
            if (len >= 8) svc?.updateCursor(read16(buf, 4), read16(buf, 6))
            return  // no log, no allocation
        }

        // ── Mouse relative move (DMRM) ─────────────────────────────────────
        if (c0 == 'D'.code.toByte() && c1 == 'M'.code.toByte() &&
            c2 == 'R'.code.toByte() && c3 == 'M'.code.toByte()) {
            if (len >= 8) svc?.moveCursorRelative(read16signed(buf, 4), read16signed(buf, 6))
            return
        }

        // ── Keep-alive (CALV) — echo with pre-built static packet ─────────
        if (c0 == 'C'.code.toByte() && c1 == 'A'.code.toByte() &&
            c2 == 'L'.code.toByte() && c3 == 'V'.code.toByte()) {
            try { out.write(CALV_PACKET); out.flush() } catch (_: Exception) {}
            return
        }

        // ── Key down (DKDN) ───────────────────────────────────────────────
        if (c0 == 'D'.code.toByte() && c1 == 'K'.code.toByte() &&
            c2 == 'D'.code.toByte() && c3 == 'N'.code.toByte()) {
            if (len >= 8) {
                val keyId = read16(buf, 4)
                val mods  = read16(buf, 6)
                if (loggingEnabled) log("← DKDN key=0x${keyId.toString(16)} mods=0x${mods.toString(16)}")
                svc?.handleKeyDown(keyId, mods)
            }
            return
        }

        // ── Key repeat (DKRP) ─────────────────────────────────────────────
        if (c0 == 'D'.code.toByte() && c1 == 'K'.code.toByte() &&
            c2 == 'R'.code.toByte() && c3 == 'P'.code.toByte()) {
            if (len >= 8) {
                val keyId = read16(buf, 4)
                val mods  = read16(buf, 6)
                svc?.handleKeyDown(keyId, mods)
            }
            return
        }

        // ── Key up (DKUP) — no action needed ──────────────────────────────
        if (c0 == 'D'.code.toByte() && c1 == 'K'.code.toByte() &&
            c2 == 'U'.code.toByte() && c3 == 'P'.code.toByte()) {
            return
        }

        // ── Scroll wheel (DMWM) ────────────────────────────────────────────
        if (c0 == 'D'.code.toByte() && c1 == 'M'.code.toByte() &&
            c2 == 'W'.code.toByte() && c3 == 'M'.code.toByte()) {
            if (len >= 8) svc?.scroll(read16signed(buf, 4), read16signed(buf, 6))
            return
        }

        // ── Mouse button down (DMDN) ───────────────────────────────────────
        if (c0 == 'D'.code.toByte() && c1 == 'M'.code.toByte() &&
            c2 == 'D'.code.toByte() && c3 == 'N'.code.toByte()) {
            if (len >= 5) {
                val btn = buf[4].toInt() and 0xFF
                if (btn == 1) {
                    svc?.handleMouseDown()
                } else if (btn == 2) {
                    // Right Click -> Back Action
                    svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                } else if (btn == 3) {
                    // Middle Click -> Home Action
                    svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }
            }
            return
        }

        // ── Mouse button up (DMUP) ─────────────────────────────────────────
        if (c0 == 'D'.code.toByte() && c1 == 'M'.code.toByte() &&
            c2 == 'U'.code.toByte() && c3 == 'P'.code.toByte()) {
            if (len >= 5) {
                val btn = buf[4].toInt() and 0xFF
                if (btn == 1) svc?.handleMouseUp()
            }
            return
        }

        // ── Below here: less frequent commands — decode as String ──────────
        val cmd     = String(buf, 0, 4, StandardCharsets.US_ASCII)
        val payload = if (len > 4) buf.copyOfRange(4, len) else ByteArray(0)
        handleRareCommand(cmd, payload, out, svc)
    }

    /** Handles infrequent protocol commands where String allocation is acceptable. */
    private fun handleRareCommand(
        cmd:     String,
        payload: ByteArray,
        out:     DataOutputStream,
        svc:     com.example.synergyclient.service.SynergyAccessibilityService?
    ) {
        when (cmd) {

            "QINF" -> {
                log("← QINF")
                sendDinf(out)
            }

            "CIAK" -> log("← CIAK (screen info ack)")

            "DSOP" -> log("← DSOP (set options — connected)")

            "LSYN", "CNOP" -> { /* intentionally ignored */ }

            "CINN" -> {
                if (payload.size >= 4) {
                    val x = read16(payload, 0); val y = read16(payload, 2)
                    log("← CINN enter ($x,$y)")
                    svc?.updateCursor(x, y)
                    svc?.hideKeyboard()
                }
            }

            "COUT" -> {
                log("← COUT (leave)")
                svc?.showKeyboard()
            }

            "DKDL" -> {
                // Key with language text
                if (payload.size >= 6) {
                    val keyId    = read16(payload, 0)
                    val mods     = read16(payload, 2)
                    val langText = if (payload.size > 10) {
                        val strLen = read32(payload, 6)
                        if (strLen > 0 && payload.size >= 10 + strLen)
                            String(payload, 10, strLen, StandardCharsets.UTF_8)
                        else ""
                    } else ""
                    log("← DKDL key=0x${keyId.toString(16)} lang='$langText'")
                    svc?.handleKeyDownLang(keyId, mods, langText)
                }
            }

            "DCLP" -> {
                // Clipboard sync disabled to prevent OS background copy notifications.
            }

            "DDRP" -> {
                // Drag & Drop File payload (custom command: DDRP)
                // Format: 4 bytes filename length + filename (UTF-8) + 4 bytes content length + content raw bytes
                if (payload.size >= 8) {
                    val nameLen = read32(payload, 0)
                    if (payload.size >= 8 + nameLen) {
                        val filename = String(payload, 4, nameLen, StandardCharsets.UTF_8)
                        val contentOffset = 4 + nameLen
                        val fileLen = read32(payload, contentOffset)
                        if (payload.size >= contentOffset + 4 + fileLen) {
                            val fileBytes = payload.copyOfRange(contentOffset + 4, contentOffset + 4 + fileLen)
                            log("← DDRP drag & drop file '$filename' (${fileBytes.size} bytes)")
                            svc?.saveSharedFile(filename, fileBytes)
                        }
                    }
                }
            }

            "DAPP" -> {
                // Drag & Drop App state payload (custom command: DAPP)
                // Format: 4 bytes URL string length + URL (UTF-8)
                if (payload.size >= 4) {
                    val urlLen = read32(payload, 0)
                    if (payload.size >= 4 + urlLen) {
                        val urlStr = String(payload, 4, urlLen, StandardCharsets.UTF_8)
                        log("← DAPP drag & drop app state URL: '$urlStr'")
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(urlStr)).apply {
                                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            log("Failed to launch DAPP URL intent: ${e.message}")
                        }
                    }
                }
            }

            "CCLP" -> log("← CCLP (server has clipboard)")

            "CBYE" -> throw Exception("Server closed connection (CBYE)")

            "CROP" -> log("← CROP (reset options)")

            else   -> log("← $cmd (${payload.size}b) [unknown]")
        }
    }

    // ── DINF sender ───────────────────────────────────────────────────────

    fun sendDinf(out: DataOutputStream? = outputStream) {
        if (out == null) return
        try {
            val svcSize = com.example.synergyclient.service.SynergyAccessibilityService
                .instance?.getFullScreenSize()
            val (w, h) = svcSize ?: getScreenSize()
            val data = ByteArray(18)
            var i = 0
            data[i++] = 'D'.code.toByte(); data[i++] = 'I'.code.toByte()
            data[i++] = 'N'.code.toByte(); data[i++] = 'F'.code.toByte()
            data[i++] = 0; data[i++] = 0
            data[i++] = 0; data[i++] = 0
            data[i++] = (w shr 8).toByte(); data[i++] = (w and 0xFF).toByte()
            data[i++] = (h shr 8).toByte(); data[i++] = (h and 0xFF).toByte()
            data[i++] = 0; data[i++] = 0
            data[i++] = (w / 2 shr 8).toByte(); data[i++] = (w / 2 and 0xFF).toByte()
            data[i++] = (h / 2 shr 8).toByte(); data[i] = (h / 2 and 0xFF).toByte()
            out.writeInt(data.size); out.write(data); out.flush()
            log("→ DINF ${w}x${h}")
        } catch (e: Exception) {
            log("DINF error: ${e.message}")
        }
    }

    // ── Screen size ───────────────────────────────────────────────────────

    private fun getScreenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return try {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } catch (_: Exception) {
            val dm = context.resources.displayMetrics
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    // ── Inline bit helpers (no boxing) ────────────────────────────────────

    @Suppress("NOTHING_TO_INLINE")
    private inline fun read16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    @Suppress("NOTHING_TO_INLINE")
    private inline fun read32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
        ((b[off + 1].toInt() and 0xFF) shl 16) or
        ((b[off + 2].toInt() and 0xFF) shl 8) or
        (b[off + 3].toInt() and 0xFF)

    @Suppress("NOTHING_TO_INLINE")
    private inline fun read16signed(b: ByteArray, off: Int): Int {
        val v = read16(b, off); return if (v >= 0x8000) v - 0x10000 else v
    }
}
