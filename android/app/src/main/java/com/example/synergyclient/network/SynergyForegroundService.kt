package com.example.synergyclient.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.synergyclient.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Persistent foreground service that:
 *  - Runs its own UDP discovery engine
 *  - Auto-connects to the first available Synergy server
 *  - Auto-reconnects with exponential back-off when the connection drops
 *  - Remembers the last-known server for instant reconnect on restart
 */
class SynergyForegroundService : Service() {

    companion object {
        private const val TAG          = "SynergyForegroundService"
        private const val CHANNEL_ID   = "synergy_service_channel"
        private const val NOTIF_ID     = 9918

        // Intent actions
        const val ACTION_START = "ACTION_START_SYNERGY"
        const val ACTION_STOP  = "ACTION_STOP_SYNERGY"

        // Extras (still accepted for manual override from UI)
        const val EXTRA_HOST         = "EXTRA_HOST"
        const val EXTRA_PORT         = "EXTRA_PORT"
        const val EXTRA_CLIENT_NAME  = "EXTRA_CLIENT_NAME"
        const val EXTRA_LOGGING      = "EXTRA_LOGGING"
        const val EXTRA_CLIPBOARD    = "EXTRA_CLIPBOARD"

        // Reconnect back-off: 2 s → 4 s → 8 s → … capped at 30 s
        private const val RECONNECT_BASE_MS = 2_000L
        private const val RECONNECT_MAX_MS  = 30_000L

        @Volatile
        var instance: SynergyForegroundService? = null
            private set
    }

    // ── Binder ────────────────────────────────────────────────────────────

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): SynergyForegroundService = this@SynergyForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // ── State ─────────────────────────────────────────────────────────────

    var networkService: SynergyNetworkService? = null
        private set

    var connectionStatus = "Disconnected"
        private set

    private var onStatusChangeListener: ((String) -> Unit)? = null
    private var onLogListener: ((String) -> Unit)? = null

    /** Set to true when the user explicitly presses Stop — suppresses auto-reconnect. */
    @Volatile private var userStopped = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var autoConnectJob: Job? = null

    // Discovery engine (owned by this service)
    private val discovery = SynergyServerDiscovery()

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        // Show a neutral "Searching…" notification so startForeground is called ASAP
        startForeground(NOTIF_ID, buildNotification("Searching for Synergy servers…", null))
        discovery.start()
        Log.i(TAG, "Service created — discovery started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            ACTION_STOP -> {
                userStopped = true
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                // Manual connect from UI — honour the supplied params and keep auto-reconnect
                userStopped = false
                val host    = intent.getStringExtra(EXTRA_HOST)  ?: savedHost() ?: "10.40.194.37"
                val port    = intent.getIntExtra(EXTRA_PORT, 24800)
                val name    = intent.getStringExtra(EXTRA_CLIENT_NAME) ?: clientName()
                val logging = intent.getBooleanExtra(EXTRA_LOGGING,   false)
                val clip    = intent.getBooleanExtra(EXTRA_CLIPBOARD,  true)
                saveLastServer(host, port)
                launchAutoConnect(host, port, name, logging, clip)
            }

            else -> {
                // Service restarted by OS (START_STICKY) — restore last known server
                userStopped = false
                val host = savedHost()
                val port = savedPort()
                if (host != null) {
                    Log.i(TAG, "Sticky restart — reconnecting to $host:$port")
                    launchAutoConnect(host, port, clientName(), loggingEnabled(), clipboardEnabled())
                } else {
                    // No last server — wait for discovery
                    launchDiscoveryConnect()
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        discovery.stop()
        serviceScope.cancel()
        instance = null
        Log.i(TAG, "Service destroyed")
    }

    // ── Auto-connect logic ────────────────────────────────────────────────

    /**
     * Connect to [host]:[port] and keep retrying with exponential back-off
     * whenever the connection drops, until [userStopped] is set.
     */
    private fun launchAutoConnect(
        host:    String,
        port:    Int,
        name:    String,
        logging: Boolean,
        clip:    Boolean
    ) {
        autoConnectJob?.cancel()
        autoConnectJob = serviceScope.launch {
            var backoff = RECONNECT_BASE_MS
            while (!userStopped) {
                updateStatus("Connecting…")
                updateNotification("Connecting to $host:$port…", host)
                log("→ Connecting to $host:$port as '$name'")

                val connected = connectOnce(host, port, name, logging, clip)

                if (userStopped) break

                if (connected) {
                    backoff = RECONNECT_BASE_MS   // reset on clean disconnect
                } else {
                    backoff = (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
                }

                updateStatus("Disconnected")
                updateNotification("Reconnecting to $host in ${backoff / 1000}s…", null)
                log("⏳ Retry in ${backoff / 1000}s")
                delay(backoff)
            }
        }
    }

    /**
     * Watch discovery events and auto-connect to the first server seen.
     * Once connected, [launchAutoConnect] takes over for reconnects.
     */
    private fun launchDiscoveryConnect() {
        autoConnectJob?.cancel()
        autoConnectJob = serviceScope.launch {
            updateNotification("Searching for Synergy servers…", null)
            log("🔍 Waiting for server discovery…")

            // Collect the first server found
            discovery.serverFound.collect { server ->
                if (userStopped) return@collect
                log("🔍 Found: ${server.displayLabel}:${server.port} — connecting")
                saveLastServer(server.ip, server.port)
                launchAutoConnect(server.ip, server.port, clientName(), loggingEnabled(), clipboardEnabled())
                // Stop collecting — autoConnect owns reconnects from here
                return@collect
            }
        }
    }

    /**
     * Open one TCP connection. Returns true if we connected successfully
     * (even if later disconnected), false if connect itself failed.
     *
     * Blocks until disconnected.
     */
    private suspend fun connectOnce(
        host:    String,
        port:    Int,
        name:    String,
        logging: Boolean,
        clip:    Boolean
    ): Boolean {
        var didConnect = false
        val doneSignal = kotlinx.coroutines.CompletableDeferred<Unit>()

        val svc = SynergyNetworkService(
            host             = host,
            port             = port,
            clientName       = name,
            context          = this@SynergyForegroundService,
            loggingEnabled   = logging,
            clipboardEnabled = clip,
            onLog            = { msg -> log(msg) },
            onStatusChange   = { _, status ->
                connectionStatus = status
                onStatusChangeListener?.invoke(status)
                when (status) {
                    "Connected" -> {
                        didConnect = true
                        updateNotification("Connected to $host:$port", host)
                        updateStatus("Connected")
                    }
                    "Disconnected" -> {
                        updateStatus("Disconnected")
                        doneSignal.complete(Unit)
                    }
                }
            }
        )
        networkService = svc
        svc.start()
        doneSignal.await()
        networkService = null
        return didConnect
    }

    // ── Manual stop ───────────────────────────────────────────────────────

    fun stopServiceAndConnection() {
        userStopped = true
        stopEverything()
    }

    private fun stopEverything() {
        autoConnectJob?.cancel()
        autoConnectJob = null
        networkService?.stop()
        networkService = null
        connectionStatus = "Disconnected"
        onStatusChangeListener?.invoke("Disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── UI listener binding ───────────────────────────────────────────────

    fun setListeners(onStatusChange: (String) -> Unit, onLog: (String) -> Unit) {
        this.onStatusChangeListener = onStatusChange
        this.onLogListener          = onLog
        onStatusChange(connectionStatus)
    }

    fun removeListeners() {
        this.onStatusChangeListener = null
        this.onLogListener          = null
    }

    // ── Notification helpers ──────────────────────────────────────────────

    private fun stopPendingIntent() = PendingIntent.getService(
        this, 0,
        Intent(this, SynergyForegroundService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun openAppPendingIntent() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(text: String, host: String?): Notification {
        val title = if (host != null) "Flowport ● $host" else "Flowport"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            // Transparent / minimal icon — least intrusive icon possible
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentIntent(openAppPendingIntent())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent())
            .setOngoing(true)
            // MIN priority = no sound, no vibration, no heads-up, hidden from status bar on MIUI
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun updateNotification(text: String, host: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_ID, buildNotification(text, host))
    }

    private fun updateStatus(status: String) {
        connectionStatus = status
        onStatusChangeListener?.invoke(status)
    }

    private fun log(msg: String) = onLogListener?.invoke(msg)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                "Flowport Connection",
                // IMPORTANCE_MIN = completely silent, no status bar icon on most devices
                NotificationManager.IMPORTANCE_MIN
            )
            chan.description = "Flowport background connection"
            chan.setShowBadge(false)          // no app icon badge
            chan.enableLights(false)          // no LED
            chan.enableVibration(false)       // no vibration
            chan.setSound(null, null)         // no sound
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(chan)
        }
    }

    // ── SharedPreferences helpers ─────────────────────────────────────────

    private fun prefs() = getSharedPreferences("synergy_prefs", Context.MODE_PRIVATE)

    private fun savedHost()   = prefs().getString("server_ip", null)
    private fun savedPort()   = prefs().getInt("last_port", 24800)
    private fun clientName()  = prefs().getString("client_name", null)
                                    ?: Build.MODEL.replace("[^a-zA-Z0-9-_]".toRegex(), "")
    private fun loggingEnabled()   = prefs().getBoolean("logging_enabled", false)
    private fun clipboardEnabled() = prefs().getBoolean("clipboard_sync",  true)

    private fun saveLastServer(host: String, port: Int) {
        prefs().edit().putString("server_ip", host).putInt("last_port", port).apply()
    }
}
