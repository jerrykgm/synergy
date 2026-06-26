package com.example.synergyclient.network

import android.content.Context
import com.example.synergyclient.data.NotesManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class NotesWebServer(private val context: Context, private val port: Int = 8080) {
    companion object {
        @Volatile var activeInstance: NotesWebServer? = null
    }

    private var serverSocket: ServerSocket? = null
    private val peerIps = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var udpSocket: DatagramSocket? = null
    private var discoveryThread: Thread? = null
    private var broadcastThread: Thread? = null
    private var listenThread: Thread? = null
    private var executor: ExecutorService? = null
    @Volatile private var isRunning = false

    fun start() {
        activeInstance = this
        isRunning = true
        executor = Executors.newCachedThreadPool()
        startUdpDiscovery()
        
        listenThread = Thread {
            try {
                serverSocket = ServerSocket(port)
                android.util.Log.i("NotesWebServer", "Socket Server started on port $port")
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    executor?.submit {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    android.util.Log.e("NotesWebServer", "Server error: ${e.message}")
                }
            }
        }.apply { start() }
    }

    fun stop() {
        if (activeInstance == this) {
            activeInstance = null
        }
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        udpSocket = null
        discoveryThread = null
        broadcastThread = null
        listenThread = null
        executor?.shutdown()
        executor = null
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val fullPath = parts[1]
            
            val path = fullPath.substringBefore("?")
            val query = if (fullPath.contains("?")) fullPath.substringAfter("?") else ""
            
            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
                if (line!!.startsWith("content-length:", ignoreCase = true)) {
                    contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val r = reader.read(buf, read, contentLength - read)
                    if (r == -1) break
                    read += r
                }
                String(buf)
            } else ""

            val out = DataOutputStream(socket.getOutputStream())
            
            if (method == "OPTIONS") {
                sendHeaders(out, 204, "text/plain", -1)
                socket.close()
                return
            }

            val params = getQueryParams(query)

            when {
                path == "/" && method == "GET" -> {
                    val html = getHtmlContent()
                    val bytes = html.toByteArray(StandardCharsets.UTF_8)
                    sendHeaders(out, 200, "text/html; charset=utf-8", bytes.size)
                    out.write(bytes)
                }
                path == "/download/sync_script.py" && method == "GET" -> {
                    val script = getSyncScriptContent()
                    val bytes = script.toByteArray(StandardCharsets.UTF_8)
                    out.writeBytes("HTTP/1.1 200 OK\r\n")
                    out.writeBytes("Content-Type: application/octet-stream\r\n")
                    out.writeBytes("Content-Length: ${bytes.size}\r\n")
                    out.writeBytes("Content-Disposition: attachment; filename=\"sync_notes.py\"\r\n")
                    out.writeBytes("Access-Control-Allow-Origin: *\r\n")
                    out.writeBytes("\r\n")
                    out.write(bytes)
                }
                path == "/api/notes/zip" && method == "GET" -> {
                    try {
                        val files = NotesManager.getTextFiles(context)
                        val baos = java.io.ByteArrayOutputStream()
                        val zos = java.util.zip.ZipOutputStream(baos)
                        for (name in files) {
                            val rawContent = NotesManager.readFile(context, name)
                            val entry = java.util.zip.ZipEntry(name)
                            zos.putNextEntry(entry)
                            zos.write(rawContent.toByteArray(StandardCharsets.UTF_8))
                            zos.closeEntry()
                        }
                        zos.close()
                        val bytes = baos.toByteArray()
                        out.writeBytes("HTTP/1.1 200 OK\r\n")
                        out.writeBytes("Content-Type: application/zip\r\n")
                        out.writeBytes("Content-Length: ${bytes.size}\r\n")
                        out.writeBytes("Content-Disposition: attachment; filename=\"synergy_notes.zip\"\r\n")
                        out.writeBytes("Access-Control-Allow-Origin: *\r\n")
                        out.writeBytes("\r\n")
                        out.write(bytes)
                    } catch (e: Exception) {
                        val bytes = "Failed to zip: ${e.message}".toByteArray(StandardCharsets.UTF_8)
                        sendHeaders(out, 500, "text/plain", bytes.size)
                        out.write(bytes)
                    }
                }
                path == "/api/notes" && method == "GET" -> {
                    val files = NotesManager.getTextFiles(context)
                    val arr = JSONArray()
                    for (name in files) {
                        val rawContent = NotesManager.readFile(context, name)
                        val color = if (rawContent.startsWith("#color:")) {
                            rawContent.split("\n", limit = 2)[0].substringAfter("#color:").trim()
                        } else "yellow"
                        val contentClean = if (rawContent.startsWith("#color:")) {
                            rawContent.split("\n", limit = 2).getOrNull(1) ?: ""
                        } else rawContent
                        val obj = JSONObject().apply {
                            put("name", name)
                            put("content", contentClean)
                            put("color", color)
                        }
                        arr.put(obj)
                    }
                    val bytes = arr.toString().toByteArray(StandardCharsets.UTF_8)
                    sendHeaders(out, 200, "application/json", bytes.size)
                    out.write(bytes)
                }
                path == "/api/notes/save" && method == "POST" -> {
                    val filename = params["filename"]
                    if (filename.isNullOrEmpty()) {
                        val bytes = "Missing filename".toByteArray(StandardCharsets.UTF_8)
                        sendHeaders(out, 400, "text/plain", bytes.size)
                        out.write(bytes)
                    } else {
                        val isSynced = params["synced"] == "true"
                        NotesManager.saveFile(context, filename, body)
                        if (!isSynced) {
                            syncWithPeers(filename, body, false)
                        }
                        val resp = "{\"status\":\"saved\"}".toByteArray(StandardCharsets.UTF_8)
                        sendHeaders(out, 200, "application/json", resp.size)
                        out.write(resp)
                    }
                }
                path == "/api/notes/delete" && method == "POST" -> {
                    val filename = params["filename"]
                    if (filename.isNullOrEmpty()) {
                        val bytes = "Missing filename".toByteArray(StandardCharsets.UTF_8)
                        sendHeaders(out, 400, "text/plain", bytes.size)
                        out.write(bytes)
                    } else {
                        val isSynced = params["synced"] == "true"
                        NotesManager.deleteFile(context, filename)
                        if (!isSynced) {
                            syncWithPeers(filename, "", true)
                        }
                        val resp = "{\"status\":\"deleted\"}".toByteArray(StandardCharsets.UTF_8)
                        sendHeaders(out, 200, "application/json", resp.size)
                        out.write(resp)
                    }
                }
                path == "/api/clipboard" && method == "GET" -> {
                    val list = NotesManager.getClipHistory(context)
                    val bytes = JSONArray(list).toString().toByteArray(StandardCharsets.UTF_8)
                    sendHeaders(out, 200, "application/json", bytes.size)
                    out.write(bytes)
                }
                path == "/api/clipboard/clear" && method == "POST" -> {
                    NotesManager.clearClipHistory(context)
                    val resp = "{\"status\":\"cleared\"}".toByteArray(StandardCharsets.UTF_8)
                    sendHeaders(out, 200, "application/json", resp.size)
                    out.write(resp)
                }
                else -> {
                    val bytes = "Not Found".toByteArray(StandardCharsets.UTF_8)
                    sendHeaders(out, 404, "text/plain", bytes.size)
                    out.write(bytes)
                }
            }
            out.flush()
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendHeaders(out: DataOutputStream, status: Int, contentType: String, contentLength: Int) {
        val statusMsg = when (status) {
            200 -> "200 OK"
            204 -> "204 No Content"
            400 -> "400 Bad Request"
            404 -> "404 Not Found"
            500 -> "500 Internal Server Error"
            else -> "200 OK"
        }
        out.writeBytes("HTTP/1.1 $statusMsg\r\n")
        out.writeBytes("Content-Type: $contentType\r\n")
        if (contentLength >= 0) {
            out.writeBytes("Content-Length: $contentLength\r\n")
        }
        out.writeBytes("Access-Control-Allow-Origin: *\r\n")
        out.writeBytes("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        out.writeBytes("Access-Control-Allow-Headers: Content-Type\r\n")
        out.writeBytes("\r\n")
    }

    private fun getQueryParams(query: String?): Map<String, String> {
        val params = mutableMapOf<String, String>()
        if (query.isNullOrEmpty()) return params
        try {
            for (param in query.split("&")) {
                val entry = param.split("=")
                if (entry.size > 1) {
                    params[URLDecoder.decode(entry[0], "UTF-8")] = URLDecoder.decode(entry[1], "UTF-8")
                } else if (entry.isNotEmpty()) {
                    params[URLDecoder.decode(entry[0], "UTF-8")] = ""
                }
            }
        } catch (_: Exception) {}
        return params
    }

    private fun startUdpDiscovery() {
        discoveryThread = Thread {
            try {
                val socket = DatagramSocket(8082).apply {
                    broadcast = true
                }
                udpSocket = socket
                val buf = ByteArray(1024)
                while (isRunning) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                    if (msg.startsWith("SYNERGY_SYNC_PING")) {
                        val senderIp = packet.address.hostAddress
                        if (senderIp != null && senderIp != "127.0.0.1" && senderIp != getLocalIp()) {
                            if (peerIps.add(senderIp)) {
                                android.util.Log.i("NotesWebServer", "Discovered new peer tablet: $senderIp")
                            }
                            if (msg == "SYNERGY_SYNC_PING") {
                                val replyData = "SYNERGY_SYNC_PING_ACK".toByteArray(StandardCharsets.UTF_8)
                                val replyPacket = DatagramPacket(replyData, replyData.size, packet.address, 8082)
                                socket.send(replyPacket)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    android.util.Log.e("NotesWebServer", "UDP discovery error: ${e.message}")
                }
            }
        }.apply { start() }

        broadcastThread = Thread {
            while (isRunning) {
                try {
                    val socket = udpSocket
                    if (socket != null && !socket.isClosed) {
                        val data = "SYNERGY_SYNC_PING".toByteArray(StandardCharsets.UTF_8)
                        val address = InetAddress.getByName("255.255.255.255")
                        val packet = DatagramPacket(data, data.size, address, 8082)
                        socket.send(packet)
                    }
                } catch (_: Exception) {}
                try { Thread.sleep(8000) } catch (_: Exception) {}
            }
        }.apply { start() }
    }

    private fun getLocalIp(): String {
        return SynergyNetworkService.getLocalIpAddress()
    }

    fun syncWithPeers(filename: String, content: String, isDelete: Boolean) {
        if (peerIps.isEmpty()) return
        Thread {
            for (peerIp in peerIps) {
                try {
                    val urlStr = if (isDelete) {
                        "http://$peerIp:8080/api/notes/delete?filename=" + java.net.URLEncoder.encode(filename, "UTF-8") + "&synced=true"
                    } else {
                        "http://$peerIp:8080/api/notes/save?filename=" + java.net.URLEncoder.encode(filename, "UTF-8") + "&synced=true"
                    }
                    val url = URL(urlStr)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    if (!isDelete) {
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "text/plain")
                        conn.outputStream.use { os ->
                            os.write(content.toByteArray(StandardCharsets.UTF_8))
                        }
                    }
                    val code = conn.responseCode
                    android.util.Log.i("NotesWebServer", "Synced note '$filename' to peer $peerIp (response code $code)")
                    conn.disconnect()
                } catch (e: Exception) {
                    android.util.Log.e("NotesWebServer", "Failed to sync to peer $peerIp: ${e.message}")
                    peerIps.remove(peerIp)
                }
            }
        }.start()
    }

    private fun getSyncScriptContent(): String {
        val localIp = getLocalIp()
        return """
import os
import time
import urllib.request
import json
import urllib.parse

TABLET_IP = "$localIp"
PORT = "8080"
LOCAL_DIR = "./synergy_notes"

if not os.path.exists(LOCAL_DIR):
    os.makedirs(LOCAL_DIR)

print(f"Starting notes synchronization from tablet {TABLET_IP} to {LOCAL_DIR}...")
print("Press Ctrl+C to stop.")

def fetch_notes():
    try:
        url = f"http://{TABLET_IP}:{PORT}/api/notes"
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.loads(response.read().decode('utf-8'))
    except Exception as e:
        print(f"Error fetching notes: {e}")
        return []

def save_note_locally(filename, content, color):
    filepath = os.path.join(LOCAL_DIR, filename)
    full_content = f"#color:{color}\n{content}"
    if os.path.exists(filepath):
        with open(filepath, "r", encoding="utf-8") as f:
            existing = f.read()
        if existing == full_content:
            return
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(full_content)
    print(f"Synced/Updated note locally: {filename}")

while True:
    notes = fetch_notes()
    for note in notes:
        save_note_locally(note["name"], note["content"], note["color"])
    time.sleep(4)
        """.trimIndent()
    }

    private fun getHtmlContent(): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Synergy Shared Notes</title>
    <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600;700&family=JetBrains+Mono&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg: #0D0E14;
            --surface: #14151F;
            --card: #1A1B27;
            --accent: #6C63FF;
            --accent-glow: rgba(108, 99, 255, 0.4);
            --border: #252636;
            --muted: #6B7280;
            --text: #ECEFF4;
            --yellow: #FFF9C4;
            --blue: #E3F2FD;
            --pink: #FCE4EC;
            --green: #E8F5E9;
            --purple: #F3E5F5;
        }

        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
            font-family: 'Outfit', sans-serif;
            scrollbar-width: thin;
            scrollbar-color: var(--border) var(--bg);
        }

        body {
            background-color: var(--bg);
            color: var(--text);
            display: flex;
            flex-direction: column;
            height: 100vh;
            overflow: hidden;
        }

        header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 16px 24px;
            background-color: var(--surface);
            border-bottom: 1px solid var(--border);
            z-index: 10;
        }

        .logo-section {
            display: flex;
            align-items: center;
            gap: 10px;
        }

        .logo-section h1 {
            font-size: 20px;
            font-weight: 700;
            letter-spacing: 0.5px;
            background: linear-gradient(45deg, #6C63FF, #8B85FF);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
        }

        .connection-badge {
            background-color: rgba(46, 204, 113, 0.15);
            color: #2ECC71;
            padding: 4px 10px;
            border-radius: 12px;
            font-size: 11px;
            font-weight: 600;
            border: 1px solid rgba(46, 204, 113, 0.3);
        }

        .main-container {
            display: flex;
            flex: 1;
            overflow: hidden;
        }

        .sidebar {
            width: 320px;
            background-color: var(--surface);
            border-right: 1px solid var(--border);
            display: flex;
            flex-direction: column;
            overflow-y: auto;
        }

        .sidebar-section {
            padding: 20px;
            border-bottom: 1px solid var(--border);
        }

        .section-title {
            font-size: 11px;
            text-transform: uppercase;
            letter-spacing: 1.5px;
            color: var(--muted);
            margin-bottom: 12px;
            font-weight: 700;
        }

        .file-list {
            display: flex;
            flex-direction: column;
            gap: 8px;
        }

        .file-item {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 10px 12px;
            background-color: var(--card);
            border: 1px solid var(--border);
            border-radius: 8px;
            cursor: pointer;
            transition: all 0.2s ease;
        }

        .file-item:hover, .file-item.active {
            border-color: var(--accent);
            box-shadow: 0 0 8px var(--accent-glow);
        }

        .file-name {
            font-size: 14px;
            font-weight: 500;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            max-width: 200px;
        }

        .btn-delete {
            background: none;
            border: none;
            color: #E74C3C;
            cursor: pointer;
            padding: 4px;
            font-size: 14px;
            border-radius: 4px;
            opacity: 0.6;
            transition: opacity 0.2s;
        }

        .btn-delete:hover {
            opacity: 1;
            background-color: rgba(231, 76, 60, 0.1);
        }

        .new-note-form {
            display: flex;
            gap: 8px;
            margin-top: 10px;
        }

        .input-text {
            flex: 1;
            background-color: var(--card);
            border: 1px solid var(--border);
            border-radius: 6px;
            padding: 8px 12px;
            color: var(--text);
            font-size: 13px;
            outline: none;
            transition: border-color 0.2s;
        }

        .input-text:focus {
            border-color: var(--accent);
        }

        .btn-primary {
            background-color: var(--accent);
            color: white;
            border: none;
            border-radius: 6px;
            padding: 8px 14px;
            font-weight: 600;
            font-size: 13px;
            cursor: pointer;
            transition: background-color 0.2s;
        }

        .btn-primary:hover {
            background-color: #554cd8;
        }

        .clip-list {
            display: flex;
            flex-direction: column;
            gap: 8px;
        }

        .clip-item {
            padding: 10px;
            background-color: var(--card);
            border: 1px solid var(--border);
            border-radius: 6px;
            font-size: 12px;
            cursor: pointer;
            word-break: break-all;
            max-height: 80px;
            overflow: hidden;
            text-overflow: ellipsis;
            transition: all 0.2s;
        }

        .clip-item:hover {
            border-color: var(--accent);
            background-color: rgba(108, 99, 255, 0.05);
        }

        .workspace {
            flex: 1;
            display: flex;
            flex-direction: column;
            padding: 24px;
            gap: 20px;
            overflow-y: auto;
        }

        .sticky-notes-row {
            display: flex;
            gap: 16px;
            overflow-x: auto;
            padding: 10px 4px;
            min-height: 150px;
            align-items: center;
        }

        .sticky-note {
            width: 130px;
            height: 130px;
            padding: 12px;
            border-radius: 4px;
            box-shadow: 0 4px 10px rgba(0,0,0,0.3);
            display: flex;
            flex-direction: column;
            justify-content: space-between;
            cursor: pointer;
            transition: transform 0.3s ease, box-shadow 0.3s ease;
            position: relative;
        }

        .sticky-note:hover {
            transform: scale(1.05) rotate(0deg) !important;
            box-shadow: 0 8px 20px rgba(0,0,0,0.4);
            z-index: 5;
        }

        .sticky-pin {
            position: absolute;
            top: 4px;
            left: 50%;
            transform: translateX(-50%);
            font-size: 14px;
        }

        .sticky-content {
            font-size: 11px;
            color: #2F3542;
            font-weight: 500;
            line-height: 1.4;
            display: -webkit-box;
            -webkit-line-clamp: 5;
            -webkit-box-orient: vertical;
            overflow: hidden;
            margin-top: 10px;
        }

        .sticky-note.yellow { background-color: var(--yellow); }
        .sticky-note.blue { background-color: var(--blue); }
        .sticky-note.pink { background-color: var(--pink); }
        .sticky-note.green { background-color: var(--green); }
        .sticky-note.purple { background-color: var(--purple); }

        .editor-container {
            flex: 1;
            background-color: var(--surface);
            border: 1px solid var(--border);
            border-radius: 12px;
            padding: 20px;
            display: flex;
            flex-direction: column;
            gap: 16px;
            min-height: 380px;
        }

        .editor-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
        }

        .editor-title {
            font-size: 16px;
            font-weight: 600;
        }

        .color-picker {
            display: flex;
            gap: 8px;
        }

        .color-dot {
            width: 20px;
            height: 20px;
            border-radius: 50%;
            cursor: pointer;
            border: 2px solid transparent;
            transition: border-color 0.2s;
        }

        .color-dot.active {
            border-color: var(--accent);
        }

        .color-dot.yellow { background-color: var(--yellow); }
        .color-dot.blue { background-color: var(--blue); }
        .color-dot.pink { background-color: var(--pink); }
        .color-dot.green { background-color: var(--green); }
        .color-dot.purple { background-color: var(--purple); }

        .editor-textarea {
            flex: 1;
            background-color: var(--card);
            border: 1px solid var(--border);
            border-radius: 8px;
            padding: 16px;
            color: var(--text);
            font-family: 'JetBrains Mono', monospace;
            font-size: 14px;
            line-height: 1.6;
            outline: none;
            resize: none;
            transition: border-color 0.2s;
        }

        .editor-textarea:focus {
            border-color: var(--accent);
        }

        .editor-actions {
            display: flex;
            justify-content: flex-end;
            gap: 12px;
        }

        .btn-secondary {
            background-color: transparent;
            border: 1px solid var(--border);
            color: var(--text);
            border-radius: 6px;
            padding: 10px 16px;
            font-weight: 600;
            font-size: 13px;
            cursor: pointer;
            transition: all 0.2s;
        }

        .btn-secondary:hover {
            border-color: var(--accent);
            background-color: rgba(108, 99, 255, 0.05);
        }
    </style>
</head>
<body>
    <header>
        <div class="logo-section">
            <span style="font-size: 24px;">🔗</span>
            <h1>Synergy Flow</h1>
        </div>
        <div style="display: flex; gap: 12px; align-items: center;">
            <button class="btn-secondary" style="font-size: 11px; padding: 6px 12px; cursor: pointer; color: white;" onclick="window.open('/download/sync_script.py')">💾 Download Auto-Sync Script (PC/Mac)</button>
            <button class="btn-secondary" style="font-size: 11px; padding: 6px 12px; cursor: pointer; color: white;" onclick="window.open('/api/notes/zip')">📦 Download All (ZIP)</button>
            <span class="connection-badge">Active Connection</span>
        </div>
    </header>

    <div class="main-container">
        <div class="sidebar">
            <div class="sidebar-section">
                <div class="section-title">Note Files</div>
                <div class="file-list" id="fileList">
                </div>
                <div class="new-note-form">
                    <input type="text" id="newFileName" class="input-text" placeholder="new_note.txt">
                    <button class="btn-primary" onclick="createNewFile()">Create</button>
                </div>
            </div>

            <div class="sidebar-section" style="flex: 1; display: flex; flex-direction: column;">
                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
                    <div class="section-title" style="margin: 0;">Clipboard History</div>
                    <button class="btn-delete" style="font-size: 11px; font-weight: 600;" onclick="clearClipboard()">Clear</button>
                </div>
                <div class="clip-list" id="clipList" style="flex: 1; overflow-y: auto;">
                </div>
            </div>
        </div>

        <div class="workspace">
            <div class="section-title">Sticky Notes Desk</div>
            <div class="sticky-notes-row" id="stickyRow">
            </div>

            <div class="editor-container">
                <div class="editor-header">
                    <div class="editor-title" id="editorTitle">Select or Create a Note</div>
                    <div class="color-picker" id="colorPicker" style="display: none;">
                        <div class="color-dot yellow" onclick="selectColor('yellow')"></div>
                        <div class="color-dot blue" onclick="selectColor('blue')"></div>
                        <div class="color-dot pink" onclick="selectColor('pink')"></div>
                        <div class="color-dot green" onclick="selectColor('green')"></div>
                        <div class="color-dot purple" onclick="selectColor('purple')"></div>
                    </div>
                </div>
                <textarea id="editorText" class="editor-textarea" placeholder="Start typing here..." disabled></textarea>
                <div class="editor-actions">
                    <button class="btn-secondary" id="btnSync" onclick="syncClipboard()" disabled>Sync to Desktop Clipboard</button>
                    <button class="btn-primary" id="btnSave" onclick="saveActiveFile()" disabled>Save Note</button>
                </div>
            </div>
        </div>
    </div>

    <script>
        let currentFile = "";
        let selectedColor = "yellow";

        async function apiFetch(url, options = {}) {
            try {
                const response = await fetch(url, options);
                return await response.json();
            } catch (e) {
                console.error("API error", e);
                return null;
            }
        }

        async function loadNotes() {
            const notes = await apiFetch("/api/notes");
            if (!notes) return;

            const fileList = document.getElementById("fileList");
            const stickyRow = document.getElementById("stickyRow");
            
            fileList.innerHTML = "";
            stickyRow.innerHTML = "";

            notes.forEach((note, idx) => {
                const div = document.createElement("div");
                div.className = "file-item" + (currentFile === note.name ? " active" : "");
                div.onclick = () => selectFile(note.name, note.content, note.color);
                div.innerHTML = '<span class="file-name">' + note.name + '</span>' +
                                '<button class="btn-delete" onclick="event.stopPropagation(); deleteFile(\'' + note.name + '\')">🗑</button>';
                fileList.appendChild(div);

                const rot = (idx % 4 === 0) ? -2 : (idx % 4 === 1) ? 1.5 : (idx % 4 === 2) ? -1 : 2;
                const sticky = document.createElement("div");
                sticky.className = 'sticky-note ' + note.color;
                sticky.style.transform = 'rotate(' + rot + 'deg)';
                sticky.onclick = () => selectFile(note.name, note.content, note.color);
                sticky.innerHTML = '<div class="sticky-pin">📌</div>' +
                                   '<div class="sticky-content">' + escapeHtml(note.content || note.name.replace(".txt", "")) + '</div>';
                stickyRow.appendChild(sticky);
            });
        }

        async function loadClipboard() {
            const clips = await apiFetch("/api/clipboard");
            if (!clips) return;

            const clipList = document.getElementById("clipList");
            clipList.innerHTML = "";
            clips.forEach(clip => {
                const div = document.createElement("div");
                div.className = "clip-item";
                div.textContent = clip;
                div.onclick = () => {
                    document.getElementById("editorText").disabled = false;
                    document.getElementById("editorText").value = clip;
                    document.getElementById("editorTitle").textContent = "New Scratch Note (unsaved)";
                    currentFile = "";
                    document.getElementById("colorPicker").style.display = "none";
                    document.getElementById("btnSave").disabled = false;
                    document.getElementById("btnSync").disabled = false;
                };
                clipList.appendChild(div);
            });
        }

        function selectFile(name, content, color) {
            currentFile = name;
            selectedColor = color;
            document.getElementById("editorTitle").textContent = name;
            document.getElementById("editorText").value = content;
            document.getElementById("editorText").disabled = false;
            document.getElementById("colorPicker").style.display = "flex";
            document.getElementById("btnSave").disabled = false;
            document.getElementById("btnSync").disabled = false;

            document.querySelectorAll(".color-dot").forEach(dot => {
                dot.classList.remove("active");
                if (dot.classList.contains(color)) {
                    dot.classList.add("active");
                }
            });

            document.querySelectorAll(".file-item").forEach(item => {
                item.classList.remove("active");
                if (item.querySelector(".file-name").textContent === name) {
                    item.classList.add("active");
                }
            });
        }

        function selectColor(color) {
            selectedColor = color;
            document.querySelectorAll(".color-dot").forEach(dot => {
                dot.classList.remove("active");
                if (dot.classList.contains(color)) {
                    dot.classList.add("active");
                }
            });
        }

        async function saveActiveFile() {
            let filename = currentFile;
            if (!filename) {
                const proposedName = prompt("Enter a filename to save this text (e.g. log.txt):", "scratch.txt");
                if (!proposedName) return;
                filename = proposedName.endsWith(".txt") ? proposedName : proposedName + ".txt";
            }

            let text = document.getElementById("editorText").value;
            if (text.startsWith("#color:")) {
                text = text.replace(/^#color:\s*\w+/, "#color:" + selectedColor);
            } else {
                text = "#color:" + selectedColor + "\n" + text;
            }

            const response = await fetch("/api/notes/save?filename=" + encodeURIComponent(filename), {
                method: "POST",
                headers: { "Content-Type": "text/plain" },
                body: text
            });

            if (response.ok) {
                currentFile = filename;
                await loadNotes();
                selectFile(currentFile, text.split("\n").slice(1).join("\n"), selectedColor);
            }
        }

        async function createNewFile() {
            let nameInput = document.getElementById("newFileName").value.trim();
            if (!nameInput) return;
            if (!nameInput.endsWith(".txt")) nameInput += ".txt";

            const defaultText = "#color:yellow\n";
            const response = await fetch("/api/notes/save?filename=" + encodeURIComponent(nameInput), {
                method: "POST",
                headers: { "Content-Type": "text/plain" },
                body: defaultText
            });

            if (response.ok) {
                document.getElementById("newFileName").value = "";
                await loadNotes();
                selectFile(nameInput, "", "yellow");
            }
        }

        async function deleteFile(name) {
            if (!confirm("Are you sure you want to delete " + name + "?")) return;
            const response = await fetch("/api/notes/delete?filename=" + encodeURIComponent(name), {
                method: "POST"
            });
            if (response.ok) {
                if (currentFile === name) {
                    currentFile = "";
                    document.getElementById("editorTitle").textContent = "Select or Create a Note";
                    document.getElementById("editorText").value = "";
                    document.getElementById("editorText").disabled = true;
                    document.getElementById("colorPicker").style.display = "none";
                    document.getElementById("btnSave").disabled = true;
                    document.getElementById("btnSync").disabled = true;
                }
                await loadNotes();
            }
        }

        function syncClipboard() {
            const text = document.getElementById("editorText").value;
            navigator.clipboard.writeText(text);
            alert("Copied to clipboard! If Synergy connection is active, you can now paste directly on your desktop.");
        }

        async function clearClipboard() {
            if (!confirm("Clear local clipboard history list?")) return;
            const response = await fetch("/api/clipboard/clear", { method: "POST" });
            if (response.ok) {
                await loadClipboard();
            }
        }

        function escapeHtml(text) {
            if (!text) return "";
            return text
                .replace(/&/g, "&amp;")
                .replace(/</g, "&lt;")
                .replace(/>/g, "&gt;")
                .replace(/"/g, "&quot;")
                .replace(/'/g, "&#039;");
        }

        loadNotes();
        loadClipboard();

        setInterval(() => {
            loadNotes();
            loadClipboard();
        }, 4000);
    </script>
</body>
</html>
        """.trimIndent()
    }
}
