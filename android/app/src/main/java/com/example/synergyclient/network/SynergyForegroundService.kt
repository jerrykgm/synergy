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

class SynergyForegroundService : Service() {

    companion object {
        private const val TAG = "SynergyForegroundService"
        private const val CHANNEL_ID = "synergy_service_channel"
        private const val NOTIFICATION_ID = 9918

        const val ACTION_START = "ACTION_START_SYNERGY"
        const val ACTION_STOP = "ACTION_STOP_SYNERGY"

        const val EXTRA_HOST = "EXTRA_HOST"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_CLIENT_NAME = "EXTRA_CLIENT_NAME"
        const val EXTRA_LOGGING = "EXTRA_LOGGING"

        @Volatile
        var instance: SynergyForegroundService? = null
            private set
    }

    private val binder = LocalBinder()
    var networkService: SynergyNetworkService? = null
        private set
    
    var connectionStatus = "Disconnected"
        private set

    private var onStatusChangeListener: ((String) -> Unit)? = null
    private var onLogListener: ((String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): SynergyForegroundService = this@SynergyForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopServiceAndConnection()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
            val port = intent.getIntExtra(EXTRA_PORT, 24800)
            val clientName = intent.getStringExtra(EXTRA_CLIENT_NAME) ?: "AndroidClient"
            val logging = intent.getBooleanExtra(EXTRA_LOGGING, false)

            startForegroundNotification(host, port)
            startConnection(host, port, clientName, logging)
        }

        return START_STICKY
    }

    private fun startForegroundNotification(host: String, port: Int) {
        val stopIntent = Intent(this, SynergyForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Synergy Active")
            .setContentText("Connected to $host:$port")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Service", stopPendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startConnection(host: String, port: Int, clientName: String, logging: Boolean) {
        networkService?.stop()
        val svc = SynergyNetworkService(
            host = host,
            port = port,
            clientName = clientName,
            context = this,
            loggingEnabled = logging,
            onLog = { msg ->
                onLogListener?.invoke(msg)
            },
            onStatusChange = { _, status ->
                connectionStatus = status
                onStatusChangeListener?.invoke(status)
                if (status == "Disconnected" && instance != null) {
                    // Optionally update notification
                }
            }
        )
        networkService = svc
        svc.start()
    }

    fun stopServiceAndConnection() {
        networkService?.stop()
        networkService = null
        connectionStatus = "Disconnected"
        onStatusChangeListener?.invoke("Disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun setListeners(
        onStatusChange: (String) -> Unit,
        onLog: (String) -> Unit
    ) {
        this.onStatusChangeListener = onStatusChange
        this.onLogListener = onLog
        // Immediately notify current status upon binding
        onStatusChange(connectionStatus)
    }

    fun removeListeners() {
        this.onStatusChangeListener = null
        this.onLogListener = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Synergy Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
