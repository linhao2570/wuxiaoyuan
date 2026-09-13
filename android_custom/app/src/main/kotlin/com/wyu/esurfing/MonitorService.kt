package com.wyu.esurfing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

class MonitorService : Service() {

    companion object {
        private const val TAG = "aiqin-monitor"
        private const val CHANNEL_ID = "aiqin_monitor"
        private const val NOTIFICATION_ID = 1001

        // Phase 1: first 2 minutes, check every 10 seconds
        private const val PHASE1_DURATION_MS = 2 * 60 * 1000L
        private const val PHASE1_INTERVAL_MS = 10 * 1000L

        // Phase 2: every 90 minutes
        private const val PHASE2_INTERVAL_MS = 90 * 60 * 1000L

        private var running = false
        fun isRunning(): Boolean = running
    }

    private val handler = Handler(Looper.getMainLooper())
    private var startTime = 0L
    private var lastCheck = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val checkRunnable = object : Runnable {
        override fun run() {
            doCheck()
            val elapsed = System.currentTimeMillis() - startTime
            val interval = if (elapsed < PHASE1_DURATION_MS) {
                PHASE1_INTERVAL_MS
            } else {
                PHASE2_INTERVAL_MS
            }
            handler.postDelayed(this, interval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("monitoring..."))
        Log.d(TAG, "monitor service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!handler.hasCallbacks(checkRunnable)) {
            startTime = System.currentTimeMillis()
            handler.post(checkRunnable)
            registerNetworkCallback()
        }
        return START_STICKY
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                Log.d(TAG, "network lost, triggering check")
                doCheck()
            }
        }
        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.d(TAG, "register callback failed: ${e.message}")
        }
    }

    private fun doCheck() {
        lastCheck = System.currentTimeMillis()
        if (wifiValidated()) {
            Log.d(TAG, "WiFi validated, no action needed")
            updateNotification("network OK")
            return
        }

        Log.d(TAG, "WiFi not validated, checking client status")
        updateNotification("reconnecting...")

        if (isClientRunning()) {
            Log.d(TAG, "client already running, accessibility service should handle it")
        } else {
            Log.d(TAG, "client not running, launching it")
            launchClient()
        }
    }

    private fun wifiValidated(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        }
        return true
    }

    private fun isClientRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (task in am.getRunningTasks(32)) {
            if (task.topActivity?.packageName == ClientAccessibilityService.CLIENT_PACKAGE) {
                return true
            }
        }
        // Also check running services as a fallback
        @Suppress("DEPRECATION")
        for (service in am.getRunningServices(64)) {
            if (service.service.packageName == ClientAccessibilityService.CLIENT_PACKAGE) {
                return true
            }
        }
        return false
    }

    private fun launchClient() {
        val intent = packageManager.getLaunchIntentForPackage(ClientAccessibilityService.CLIENT_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Log.d(TAG, "launched Guangdong Campus client")
        } else {
            Log.d(TAG, "client not found")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "aiqin monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("aiqin")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try {
            networkCallback?.let {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {}
        Log.d(TAG, "monitor service destroyed")
        super.onDestroy()
    }
}
