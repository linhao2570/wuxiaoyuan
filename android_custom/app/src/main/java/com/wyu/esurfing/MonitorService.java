package com.wyu.esurfing;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.net.InetAddress;

public class MonitorService extends Service {

    private static final String TAG = "aiqin-monitor";
    private static final String CHANNEL_ID = "aiqin_monitor";
    private static final int NOTIFICATION_ID = 1001;

    // Phase 1: first 1 minute, check every 10 seconds
    private static final long PHASE1_DURATION_MS = 60 * 1000L;
    private static final long PHASE1_INTERVAL_MS = 10 * 1000L;

    // Phase 2: check every 15 minutes when screen is on
    private static final long PHASE2_INTERVAL_MS = 15 * 60 * 1000L;

    // Screen off: do a reset cycle every 30 minutes (testing, will increase later)
    private static final long SCREEN_OFF_RESET_INTERVAL_MS = 30 * 60 * 1000L;

    private static boolean running = false;
    public static boolean isRunning() { return running; }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long startTime = 0L;
    private long lastReset = 0L;
    private boolean screenOn = true;
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver screenReceiver;

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            doCheck();
            long interval = getCurrentInterval();
            handler.postDelayed(this, interval);
        }
    };

    private long getCurrentInterval() {
        if (!screenOn) {
            // When screen is off, check more often for reset cycle
            return 60 * 1000L; // every minute while screen off
        }
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed < PHASE1_DURATION_MS) {
            return PHASE1_INTERVAL_MS;
        }
        return PHASE2_INTERVAL_MS;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("monitoring..."));
        Log.d(TAG, "monitor service created");
        registerScreenReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!handler.hasCallbacks(checkRunnable)) {
            startTime = System.currentTimeMillis();
            handler.post(checkRunnable);
            registerNetworkCallback();
        }
        return START_STICKY;
    }

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    screenOn = false;
                    Log.d(TAG, "screen off, starting screen-off maintenance");
                    // Do a reset shortly after screen off
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!screenOn && wifiValidated()) {
                                Log.d(TAG, "screen off reset cycle");
                                resetClient();
                            }
                        }
                    }, 5000L);
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    screenOn = true;
                    Log.d(TAG, "screen on, checking network");
                    // When screen turns on, do an immediate check
                    doCheck();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, filter);
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest request = new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(Network network) {
                Log.d(TAG, "network lost, triggering check");
                doCheck();
            }
        };
        try {
            cm.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            Log.d(TAG, "register callback failed: " + e.getMessage());
        }
    }

    private void doCheck() {
        String phase = screenOn ? "screen-on" : "screen-off";
        Log.d(TAG, "checking (" + phase + ")");

        if (wifiValidated()) {
            Log.d(TAG, "WiFi validated, no action needed");
            updateNotification("网络正常");
            return;
        }

        updateNotification("重连中...");
        Log.d(TAG, "WiFi not validated, starting reconnect flow");

        boolean clientRunning = isClientRunning();
        Log.d(TAG, "client running: " + clientRunning);

        if (!clientRunning) {
            // Client not running -> launch it, it will auto-connect
            Log.d(TAG, "launching client (not running)");
            launchClient();
        } else if (screenOn) {
            // Client running but no network + screen on -> let accessibility handle it
            Log.d(TAG, "client running, screen on, accessibility should handle it");
        } else {
            // Screen off + client running + no network -> do a reset
            long timeSinceLastReset = System.currentTimeMillis() - lastReset;
            if (timeSinceLastReset > 60 * 1000L) {
                Log.d(TAG, "screen off + no network, doing reset");
                resetClient();
            }
        }
    }

    private void resetClient() {
        lastReset = System.currentTimeMillis();
        Log.d(TAG, "=== reset client ===");
        killClient();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                launchClient();
                Log.d(TAG, "client relaunched");
            }
        }, 1500L);
        // No need to return to previous app - screen is off
        // When user turns screen on, they will see whatever was there before
        // (the client will be in foreground but user can just use recent apps)
    }

    private void killClient() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            am.killBackgroundProcesses(ClientAccessibilityService.CLIENT_PACKAGE);
            Log.d(TAG, "killed client background processes");
        } catch (Exception e) {
            Log.d(TAG, "kill client failed: " + e.getMessage());
        }
    }

    private void launchClient() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(
            ClientAccessibilityService.CLIENT_PACKAGE);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            Log.d(TAG, "launched Guangdong Campus client");
        } else {
            Log.d(TAG, "client not found");
        }
    }

    private boolean wifiValidated() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        if (caps == null) return false;
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false;
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false;
        }
        // Additional check: try to reach a known server
        // This avoids false positives from captive portal
        return isNetworkReallyConnected();
    }

    private boolean isNetworkReallyConnected() {
        try {
            // Quick DNS check - if we can resolve a known domain, network is working
            InetAddress addr = InetAddress.getByName("www.baidu.com");
            boolean reachable = addr != null && !addr.getHostAddress().isEmpty();
            Log.d(TAG, "network reachability check: " + reachable);
            return reachable;
        } catch (Exception e) {
            Log.d(TAG, "network check failed: " + e.getMessage());
            return false;
        }
    }

    private boolean isClientRunning() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningTaskInfo task : am.getRunningTasks(32)) {
            if (task.topActivity != null &&
                ClientAccessibilityService.CLIENT_PACKAGE.equals(task.topActivity.getPackageName())) {
                return true;
            }
        }
        for (ActivityManager.RunningServiceInfo service : am.getRunningServices(64)) {
            if (ClientAccessibilityService.CLIENT_PACKAGE.equals(service.service.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "aiqin monitor",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("aiqin")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        try {
            if (networkCallback != null) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                cm.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception ignored) {}
        try {
            if (screenReceiver != null) {
                unregisterReceiver(screenReceiver);
            }
        } catch (Exception ignored) {}
        Log.d(TAG, "monitor service destroyed");
        super.onDestroy();
    }
}
