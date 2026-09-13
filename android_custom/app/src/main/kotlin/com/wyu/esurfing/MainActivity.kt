package com.wyu.esurfing

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        var channel: MethodChannel? = null
        private const val CHANNEL = "aiqin/client_control"
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        channel = methodChannel
        methodChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "startMonitor" -> startMonitor(result)
                "stopMonitor" -> stopMonitor(result)
                "openAccessibility" -> openAccessibility(result)
                "getStatus" -> getStatus(result)
                else -> result.notImplemented()
            }
        }
    }

    private fun startMonitor(result: MethodChannel.Result) {
        if (MonitorService.isRunning()) {
            result.success(true)
            return
        }
        val intent = Intent(this, MonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        result.success(true)
    }

    private fun stopMonitor(result: MethodChannel.Result) {
        val intent = Intent(this, MonitorService::class.java)
        stopService(intent)
        result.success(true)
    }

    private fun openAccessibility(result: MethodChannel.Result) {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        result.success(true)
    }

    private fun getStatus(result: MethodChannel.Result) {
        val wifiOk = wifiValidated()
        val monitorRunning = MonitorService.isRunning()
        val accessibilityRunning = ClientAccessibilityService.isRunning()
        val map = mapOf(
            "wifiOk" to wifiOk,
            "monitorRunning" to monitorRunning,
            "accessibilityRunning" to accessibilityRunning
        )
        result.success(map)
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

    override fun onDestroy() {
        channel = null
        super.onDestroy()
    }
}
