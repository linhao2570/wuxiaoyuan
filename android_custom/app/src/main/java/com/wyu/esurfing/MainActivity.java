package com.wyu.esurfing;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.provider.Settings;
import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends FlutterActivity {

    private static final String CHANNEL = "aiqin/client_control";
    static MethodChannel channel;

    @Override
    public void configureFlutterEngine(FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        channel = new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), CHANNEL);
        channel.setMethodCallHandler(
            (call, result) -> {
                switch (call.method) {
                    case "startMonitor":
                        startMonitor(result);
                        break;
                    case "stopMonitor":
                        stopMonitor(result);
                        break;
                    case "openAccessibility":
                        openAccessibility(result);
                        break;
                    case "getStatus":
                        getStatus(result);
                        break;
                    default:
                        result.notImplemented();
                }
            }
        );
    }

    private void startMonitor(MethodChannel.Result result) {
        if (MonitorService.isRunning()) {
            result.success(true);
            return;
        }
        Intent intent = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        result.success(true);
    }

    private void stopMonitor(MethodChannel.Result result) {
        Intent intent = new Intent(this, MonitorService.class);
        stopService(intent);
        result.success(true);
    }

    private void openAccessibility(MethodChannel.Result result) {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        result.success(true);
    }

    private void getStatus(MethodChannel.Result result) {
        Map<String, Object> map = new HashMap<>();
        map.put("wifiOk", wifiValidated());
        map.put("monitorRunning", MonitorService.isRunning());
        map.put("accessibilityRunning", ClientAccessibilityService.isRunning());
        result.success(map);
    }

    private boolean wifiValidated() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        if (caps == null) return false;
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false;
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false;
        }
        return true;
    }

    @Override
    protected void onDestroy() {
        channel = null;
        super.onDestroy();
    }
}
