package com.example.synergyclient.network

import android.content.Context
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

class SynergyNetworkService(
    private val host: String,
    private val port: Int,
    private val clientName: String,
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onStatusChange: (String) -> Unit
) {
    private var socket: Socket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile private var outputStream: DataOutputStream? = null

    // ── Log batching — prevents UI recompose on every packet ─────────────
    private val logBuffer = ArrayDeque<String>(64)
    private var logFlusher: Job? = null

    private fun log(msg: String) {
        Log.i("SynergyApp", msg)
        synchronized(logBuffer) { logBuffer.addLast(msg) }
    }

    private fun startLogFlusher() {
        logFlusher = scope.launch {
            while (true) {
                delay(300)
                val batch: List<String>
                synchronized(logBuffer) {
                    batch = logBuffer.toList()
                    logBuffer.clear()
                }
                if (batch.isNotEmpty()) {
                    val joined = batch.joinToString("\n")
                    onLog(joined)
                }
            }
        }
    }

    fun start() {
        startLogFlusher()
        job = scope.launch {
            try {
                onStatusChange("Connecting...")
                log("Opening socket to $host:$port...")

                val s = Socket(host, port)
                socket = s
                log("TCP connected.")

                val input = DataInputStream(s.getInputStream())
                val output = DataOutputStream(s.getOutputStream())
                outputStream = output

                // ── STEP 1: Read Server Hello ──────────────────────────────
                val helloPacketLen = input.readInt()
                if (helloPacketLen < 11) throw Exception("Bad hello length: $helloPacketLen")
                val helloBytes = ByteArray(helloPacketLen)
                input.readFully(helloBytes)
                val protocol = String(helloBytes, 0, 7, StandardCharsets.US_ASCII)
                val major = read16(helloBytes, 7)
                val minor = read16(helloBytes, 9)
                log("Server: $protocol v$major.$minor")
                if (protocol != "Synergy") throw Exception("Bad protocol: $protocol")

                // ── STEP 2: Send Client Hello ──────────────────────────────
                val nameBytes = clientName.toByteArray(StandardCharsets.US_ASCII)
                output.writeInt(7 + 2 + 2 + 4 + nameBytes.size)
                output.writeBytes("Synergy")
                output.writeShort(major); output.writeShort(minor)
                output.writeInt(nameBytes.size); output.write(nameBytes)
                output.flush()
                log("Hello sent as '$clientName'.")
                onStatusChange("Connected")

                // ── STEP 3: Main packet loop ───────────────────────────────
                while (s.isConnected && !s.isClosed) {
                    val packetLen = input.readInt()
                    if (packetLen <= 0 || packetLen > 1_000_000) {
                        log("Bad packet len: $packetLen"); break
                    }
                    val data = ByteArray(packetLen)
                    input.readFully(data)
                    if (packetLen < 4) continue
                    val cmd = String(data, 0, 4, StandardCharsets.US_ASCII)
                    val payload = if (packetLen > 4) data.copyOfRange(4, packetLen) else ByteArray(0)
                    handleCommand(cmd, payload, output)
                }

            } catch (e: Exception) {
                log("Error: ${e.message}")
                Log.e("SynergyApp", "Network exception", e)
                onStatusChange("Disconnected")
            } finally {
                outputStream = null
                logFlusher?.cancel()
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                job?.cancel()
                onStatusChange("Disconnected")
            }
        }
    }

    private fun handleCommand(cmd: String, payload: ByteArray, out: DataOutputStream) {
        val svc = com.example.synergyclient.service.SynergyAccessibilityService.instance

        when (cmd) {

            "QINF" -> { log("← QINF"); sendDinf(out) }

            "CIAK" -> log("← CIAK (screen info ack)")

            "DSOP" -> log("← DSOP (set options — connected)")

            // Keep-alive — must echo immediately, no log (too frequent)
            "CALV" -> {
                try { out.writeInt(4); out.writeBytes("CALV"); out.flush() } catch (_: Exception) {}
            }

            "LSYN" -> { /* intentionally ignored */ }
            "CNOP" -> { /* no-op */ }

            // ── Screen enter / leave ───────────────────────────────────────
            "CINN" -> {
                if (payload.size >= 4) {
                    val x = read16(payload, 0); val y = read16(payload, 2)
                    log("← CINN enter ($x,$y)")
                    svc?.updateCursor(x, y)
                    svc?.hideKeyboard()        // suppress IME on screen enter
                }
            }
            "COUT" -> {
                log("← COUT (leave)")
                svc?.showKeyboard()            // restore IME when Mac takes back control
            }

            // ── Mouse ──────────────────────────────────────────────────────
            "DMMV" -> {
                if (payload.size >= 4)
                    svc?.updateCursor(read16(payload, 0), read16(payload, 2))
                // No log — too spammy
            }
            "DMRM" -> {
                if (payload.size >= 4)
                    svc?.moveCursorRelative(read16signed(payload, 0), read16signed(payload, 2))
            }
            "DMDN" -> {
                if (payload.isNotEmpty()) {
                    val btn = payload[0].toInt() and 0xFF
                    if (btn == 1) svc?.clickCursor()
                }
            }
            "DMUP" -> { /* no action */ }
            "DMWM" -> {
                if (payload.size >= 4)
                    svc?.scroll(read16signed(payload, 0), read16signed(payload, 2))
            }

            // ── Keyboard ──────────────────────────────────────────────────
            "DKDN" -> {
                if (payload.size >= 4) {
                    val keyId = read16(payload, 0)
                    val mods  = read16(payload, 2)
                    log("← DKDN key=0x${keyId.toString(16)} mods=0x${mods.toString(16)}")
                    svc?.handleKeyDown(keyId, mods)
                }
            }
            "DKDL" -> {
                // DKDL: keyId(2) + modifiers(2) + button(2) + langString(4+n)
                if (payload.size >= 6) {
                    val keyId = read16(payload, 0)
                    val mods  = read16(payload, 2)
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
            "DKUP" -> { /* key-up: no action needed */ }
            "DKRP" -> {
                // Key repeat — same as key-down for text injection
                if (payload.size >= 4) {
                    val keyId = read16(payload, 0)
                    val mods  = read16(payload, 2)
                    svc?.handleKeyDown(keyId, mods)
                }
            }

            // ── Clipboard ─────────────────────────────────────────────────
            "DCLP" -> {
                // DCLP: id(1) + sequence(4) + mark(1) + data(4+n)
                if (payload.size >= 6) {
                    val mark = payload[5].toInt() and 0xFF
                    // mark=1 (data chunk) or mark=3 (single chunk)
                    if ((mark == 1 || mark == 3) && payload.size > 10) {
                        val strLen = read32(payload, 6)
                        if (strLen > 0 && payload.size >= 10 + strLen) {
                            val text = String(payload, 10, strLen, StandardCharsets.UTF_8)
                            log("← DCLP clipboard (${text.length} chars)")
                            svc?.setClipboard(text)
                        }
                    }
                }
            }
            "CCLP" -> log("← CCLP (server has clipboard)")

            "CBYE" -> throw Exception("Server closed connection")
            "CROP" -> log("← CROP (reset options)")

            else -> log("← $cmd (${payload.size}b) [unknown]")
        }
    }

    fun sendDinf(out: DataOutputStream? = outputStream) {
        if (out == null) return
        try {
            // Prefer the accessibility service's full-screen size (same source as cursor clamping)
            val svcSize = com.example.synergyclient.service.SynergyAccessibilityService
                .instance?.getFullScreenSize()
            val (w, h) = svcSize ?: getScreenSize()
            val data = ByteArray(18)
            var i = 0
            data[i++]='D'.code.toByte(); data[i++]='I'.code.toByte()
            data[i++]='N'.code.toByte(); data[i++]='F'.code.toByte()
            data[i++]=0; data[i++]=0
            data[i++]=0; data[i++]=0
            data[i++]=(w shr 8).toByte(); data[i++]=(w and 0xFF).toByte()
            data[i++]=(h shr 8).toByte(); data[i++]=(h and 0xFF).toByte()
            data[i++]=0; data[i++]=0
            data[i++]=(w/2 shr 8).toByte(); data[i++]=(w/2 and 0xFF).toByte()
            data[i++]=(h/2 shr 8).toByte(); data[i++]=(h/2 and 0xFF).toByte()
            out.writeInt(data.size); out.write(data); out.flush()
            log("→ DINF ${w}x${h}")
        } catch (e: Exception) { log("DINF error: ${e.message}") }
    }

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

    // ── Helpers ────────────────────────────────────────────────────────────
    private fun read16(b: ByteArray, off: Int) =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off+1].toInt() and 0xFF)

    private fun read32(b: ByteArray, off: Int) =
        ((b[off].toInt() and 0xFF) shl 24) or
        ((b[off+1].toInt() and 0xFF) shl 16) or
        ((b[off+2].toInt() and 0xFF) shl 8) or
        (b[off+3].toInt() and 0xFF)

    private fun read16signed(b: ByteArray, off: Int): Int {
        val v = read16(b, off); return if (v >= 0x8000) v - 0x10000 else v
    }

    fun stop() {
        logFlusher?.cancel()
        try { socket?.close() } catch (_: Exception) {}
        socket = null; outputStream = null
        job?.cancel(); job = null
        onStatusChange("Disconnected")
    }
}
