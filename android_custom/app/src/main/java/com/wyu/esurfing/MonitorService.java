package com.wyu.esurfing;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;

public class MonitorService extends Service {

    private static final String TAG = "aiqin-monitor";
    private static final String CHANNEL_ID = "aiqin_monitor";
    private static final int NOTIFICATION_ID = 1001;

    // Phase 1: first 1 minute, check every 5 seconds
    private static final long PHASE1_DURATION_MS = 60 * 1000L;
    private static final long PHASE1_INTERVAL_MS = 5 * 1000L;

    // Phase 2: reset every 5 minutes (testing)
    private static final long PHASE2_INTERVAL_MS = 5 * 60 * 1000L;

    private static boolean running = false;
    public static boolean isRunning() { return running; }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long startTime = 0L;
    private long lastReset = 0L;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            doCheck();
            long elapsed = System.currentTimeMillis() - startTime;
            long interval = (elapsed < PHASE1_DURATION_MS)
                ? PHASE1_INTERVAL_MS
                : PHASE2_INTERVAL_MS;
            handler.postDelayed(this, interval);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("monitoring..."));
        Log.d(TAG, "monitor service created");
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
        long elapsed = System.currentTimeMillis() - startTime;
        String phase = (elapsed < PHASE1_DURATION_MS) ? "phase1" : "phase2";
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
        } else {
            // Client running but no network -> force reset
            long timeSinceLastReset = System.currentTimeMillis() - lastReset;
            if (timeSinceLastReset > 60 * 1000L) {
                Log.d(TAG, "client running but no network, doing reset");
                resetClient();
            } else {
                Log.d(TAG, "reset cooldown, waiting");
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
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                returnHome();
            }
        }, 8000L);
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

    private void returnHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(home);
        Log.d(TAG, "returned home");
        if (wifiValidated()) {
            updateNotification("网络正常");
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
        return true;
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
        Log.d(TAG, "monitor service destroyed");
        super.onDestroy();
    }
}
