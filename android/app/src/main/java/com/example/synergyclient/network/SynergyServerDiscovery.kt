package com.example.synergyclient.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Production-ready UDP server discovery engine that listens for Synergy/Deskflow
 * server broadcast packets on port 24802.
 *
 * Protocol (matches desktop NetworkDiscovery.cpp):
 *   Qt DataStream (Big-endian, Qt_6_0) encoded packet:
 *     QByteArray   magic    = "SYNERGY_DISCOVER_V1"
 *     QString      hostname (UTF-16BE with 4-byte length prefix)
 *     quint16      port
 *
 * This class is lifecycle-safe: it runs entirely on [Dispatchers.IO],
 * emits [DiscoveredServer] events via a [SharedFlow], and cleans up
 * all socket resources on [stop] or scope cancellation.
 */
class SynergyServerDiscovery {

    companion object {
        private const val TAG               = "SynergyDiscovery"
        private const val DISCOVERY_PORT    = 24802
        private const val MAGIC             = "SYNERGY_DISCOVER_V1"
        private const val SOCKET_TIMEOUT_MS = 2_000
        private const val MAX_PACKET_SIZE   = 512
        /** Peer is removed after this many ms without a heartbeat. */
        private const val PEER_TIMEOUT_MS   = 12_000L
        /** How often we check for stale peers. */
        private const val CLEANUP_INTERVAL  = 4_000L
    }

    // ── Public observable state ───────────────────────────────────────────

    private val _serverFound  = MutableSharedFlow<DiscoveredServer>(extraBufferCapacity = 16)
    private val _serverLost   = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val _allServers   = MutableStateFlow<List<DiscoveredServer>>(emptyList())

    /** Fires whenever a NEW server is discovered on the network. */
    val serverFound:  SharedFlow<DiscoveredServer> = _serverFound.asSharedFlow()

    /** Fires with the IP of a server that has gone silent. */
    val serverLost:   SharedFlow<String>            = _serverLost.asSharedFlow()

    /** Current live list of all known servers (updates reactively). */
    val allServers:   StateFlow<List<DiscoveredServer>> = _allServers.asStateFlow()

    // ── Internal state ────────────────────────────────────────────────────

    /** ip -> (server, last-seen epoch ms) */
    private val peers = mutableMapOf<String, Pair<DiscoveredServer, Long>>()
    private val peerLock = Any()

    private var scope: CoroutineScope? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────

    fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope!!.launch { listenLoop() }
        scope!!.launch { cleanupLoop() }
        Log.i(TAG, "Discovery started on UDP:$DISCOVERY_PORT")
    }

    fun stop() {
        scope?.cancel()
        scope = null
        synchronized(peerLock) { peers.clear() }
        _allServers.tryEmit(emptyList())
        Log.i(TAG, "Discovery stopped")
    }

    // ── UDP listener ──────────────────────────────────────────────────────

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun listenLoop() = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(DISCOVERY_PORT))
                soTimeout = SOCKET_TIMEOUT_MS
            }
            val buf = ByteArray(MAX_PACKET_SIZE)
            val packet = DatagramPacket(buf, buf.size)

            while (isActive) {
                try {
                    packet.length = buf.size
                    socket.receive(packet)
                    val senderIp = packet.address.hostAddress ?: continue
                    parseDatagram(
                        data     = packet.data,
                        length   = packet.length,
                        senderIp = senderIp
                    )
                } catch (_: SocketTimeoutException) {
                    // Expected — just loop so we can check isActive
                } catch (e: Exception) {
                    if (isActive) Log.w(TAG, "Receive error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            if (isActive) Log.e(TAG, "Socket error: ${e.message}", e)
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    // ── Packet parser ─────────────────────────────────────────────────────

    /**
     * Parses a Qt DataStream (Qt_6_0, Big-endian) encoded discovery packet.
     *
     * Qt QByteArray on wire: Int32 length (BE) + raw bytes
     * Qt QString    on wire: Int32 length in bytes (BE) + UTF-16BE chars
     *                        (0xFFFFFFFF means null string)
     * Qt quint16    on wire: UInt16 BE
     */
    private fun parseDatagram(data: ByteArray, length: Int, senderIp: String) {
        try {
            val dis = DataInputStream(ByteArrayInputStream(data, 0, length))

            // ── Read QByteArray magic ─────────────────────────────────────
            val magicLen = dis.readInt()
            if (magicLen <= 0 || magicLen > 64) return
            val magicBytes = ByteArray(magicLen)
            dis.readFully(magicBytes)
            val magic = String(magicBytes, Charsets.US_ASCII)
            if (magic != MAGIC) return

            // ── Read QString hostname (UTF-16BE) ──────────────────────────
            val hostnameByteLen = dis.readInt()
            if (hostnameByteLen < 0) return   // -1 = null QString
            val hostnameBytes = ByteArray(hostnameByteLen)
            if (hostnameByteLen > 0) dis.readFully(hostnameBytes)
            val hostname = if (hostnameByteLen > 0)
                String(hostnameBytes, Charsets.UTF_16BE)
            else
                senderIp

            // ── Read quint16 port ─────────────────────────────────────────
            val port = dis.readUnsignedShort()
            if (port !in 1..65535) return

            onPeerSeen(DiscoveredServer(hostname = hostname, ip = senderIp, port = port))

        } catch (e: Exception) {
            Log.v(TAG, "Parse error from $senderIp: ${e.message}")
        }
    }

    // ── Peer tracking ─────────────────────────────────────────────────────

    private fun onPeerSeen(server: DiscoveredServer) {
        val now   = System.currentTimeMillis()
        val isNew: Boolean
        synchronized(peerLock) {
            isNew = !peers.containsKey(server.ip)
            peers[server.ip] = Pair(server, now)
        }
        publishServers()
        if (isNew) {
            Log.i(TAG, "New server: ${server.hostname} @ ${server.ip}:${server.port}")
            _serverFound.tryEmit(server)
        }
    }

    private suspend fun cleanupLoop() {
        while (currentCoroutineContext().isActive) {
            delay(CLEANUP_INTERVAL)
            val now    = System.currentTimeMillis()
            val stale  = mutableListOf<String>()
            synchronized(peerLock) {
                val iter = peers.iterator()
                while (iter.hasNext()) {
                    val entry = iter.next()
                    if (now - entry.value.second > PEER_TIMEOUT_MS) {
                        stale += entry.key
                        iter.remove()
                    }
                }
            }
            stale.forEach { ip ->
                Log.i(TAG, "Server lost: $ip")
                _serverLost.tryEmit(ip)
            }
            if (stale.isNotEmpty()) publishServers()
        }
    }

    private fun publishServers() {
        val snapshot = synchronized(peerLock) {
            peers.values.map { it.first }
        }
        _allServers.tryEmit(snapshot)
    }
}

/**
 * Immutable value class describing a discovered Synergy server.
 */
data class DiscoveredServer(
    val hostname: String,
    val ip:       String,
    val port:     Int
) {
    /** Human-readable label for display in the UI. */
    val displayLabel: String get() = if (hostname != ip) "$hostname ($ip)" else ip
}
