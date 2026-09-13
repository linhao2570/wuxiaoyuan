package com.wyu.esurfing;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * aiqin 的轻量后台服务。
 *
 * 这里只保留两类工作：
 * 1. 开启服务时检查一次当前 WiFi/互联网状态；
 * 2. 熄屏后执行广东校园重置，亮屏立即取消熄屏任务。
 *
 * 不注册 NetworkCallback，也不在亮屏期间持续轮询，避免后台耗电和打扰用户。
 */
public class MonitorService extends Service {

    private static final String TAG = "aiqin-monitor";
    private static final String CHANNEL_ID = "aiqin_monitor";
    private static final int NOTIFICATION_ID = 1001;

    // 熄屏约 30 秒后执行第一次重置。
    private static final long FIRST_SCREEN_OFF_RESET_MS = 30_000L;
    // 持续熄屏时，之后每 40 分钟重置一次。
    private static final long SCREEN_OFF_RESET_INTERVAL_MS = 40 * 60 * 1000L;
    private static final long CLIENT_RESTART_DELAY_MS = 800L;
    public static final String ACTION_FIRST_RESET =
            "com.wyu.esurfing.action.FIRST_SCREEN_OFF_RESET";
    public static final String ACTION_PERIODIC_RESET =
            "com.wyu.esurfing.action.PERIODIC_SCREEN_OFF_RESET";
    private static final int FIRST_RESET_REQUEST = 2201;
    private static final int PERIODIC_RESET_REQUEST = 2202;

    private static final Object LOG_LOCK = new Object();
    private static final ArrayDeque<String> RECENT_LOGS = new ArrayDeque<>();
    private static volatile boolean running;
    private static volatile boolean screenOn = true;
    private static volatile boolean lastWifiConnected;
    private static volatile boolean lastInternetOk;
    private static volatile String lastNetworkDetail = "尚未检测";
    private static volatile MonitorService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private AlarmManager alarmManager;
    private boolean initialCheckDone;
    private BroadcastReceiver screenReceiver;

    public static boolean isRunning() {
        return running;
    }

    public static void handleAlarm(Context context, String action) {
        MonitorService service = instance;
        if (service != null) {
            service.handler.post(() -> service.handleAlarm(action));
            return;
        }

        // 如果服务恰好被系统回收，先恢复前台服务，再由 onStartCommand 接收动作。
        try {
            Intent intent = new Intent(context, MonitorService.class).setAction(action);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            logEvent("闹钟恢复后台服务失败：" + e.getClass().getSimpleName());
        }
    }

    public static Map<String, Object> getStatusSnapshot() {
        Map<String, Object> status = new HashMap<>();
        status.put("wifiConnected", lastWifiConnected);
        status.put("wifiOk", lastWifiConnected);
        status.put("internetOk", lastInternetOk);
        status.put("networkDetail", lastNetworkDetail);
        status.put("screenOn", screenOn);
        status.put("monitorRunning", running);
        status.put("accessibilityRunning", ClientAccessibilityService.isRunning());
        synchronized (LOG_LOCK) {
            status.put("logs", new ArrayList<>(RECENT_LOGS));
        }
        return status;
    }

    /**
     * 读取当前 WiFi 和互联网状态。
     *
     * wifiConnected 表示手机是否连接 WiFi；
     * internetOk 表示是否具备互联网访问能力。
     *
     * 不把 NET_CAPABILITY_VALIDATED 当成唯一条件，因为部分校园网、
     * 国内系统或厂商 ROM 会把“实际可用”错误报告成未验证。
     */
    public static NetworkState checkNetwork(Context context, boolean probeInternet) {
        boolean wifiConnected = false;
        boolean capabilityInternet = false;

        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Network active = cm.getActiveNetwork();
                    NetworkCapabilities caps =
                            active == null ? null : cm.getNetworkCapabilities(active);
                    if (caps != null) {
                        wifiConnected = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                        capabilityInternet =
                                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                    }
                }

                // 兼容旧系统和部分 ROM 的网络能力报告。
                if (!wifiConnected) {
                    NetworkInfo info = cm.getActiveNetworkInfo();
                    wifiConnected = info != null
                            && info.isConnected()
                            && info.getType() == ConnectivityManager.TYPE_WIFI;
                    capabilityInternet = capabilityInternet || wifiConnected;
                }

                // VPN 或厂商 ROM 可能让 activeNetwork 显示成 VPN。
                // 再从全部网络中寻找 WiFi，避免“明明连着 WiFi 却显示未连接”。
                if (!wifiConnected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    for (Network network : cm.getAllNetworks()) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            wifiConnected = true;
                            capabilityInternet = capabilityInternet
                                    || caps.hasCapability(
                                    NetworkCapabilities.NET_CAPABILITY_INTERNET);
                            break;
                        }
                    }
                }

                // 某些系统在 WiFi 已连接但网络能力尚未刷新时，
                // 用 WiFi 连接状态做最后兼容判断。
                if (!wifiConnected) {
                    WifiManager wifiManager =
                            (WifiManager) context.getApplicationContext()
                                    .getSystemService(Context.WIFI_SERVICE);
                    WifiInfo wifiInfo = wifiManager == null ? null : wifiManager.getConnectionInfo();
                    wifiConnected = wifiInfo != null
                            && wifiInfo.getNetworkId() != -1
                            && wifiInfo.getSupplicantState()
                            == android.net.wifi.SupplicantState.COMPLETED;
                    capabilityInternet = capabilityInternet || wifiConnected;
                }
            }
        } catch (Exception e) {
            logEvent("读取 WiFi 状态失败：" + e.getClass().getSimpleName());
        }

        boolean internetOk = wifiConnected && capabilityInternet;
        if (wifiConnected && probeInternet) {
            internetOk = probeInternet();
        }

        String detail;
        if (!wifiConnected) {
            detail = "未连接 WiFi";
        } else if (internetOk) {
            detail = "WiFi 已连接，互联网可用";
        } else {
            detail = "WiFi 已连接，但互联网不可用";
        }

        lastWifiConnected = wifiConnected;
        lastInternetOk = internetOk;
        lastNetworkDetail = detail;
        return new NetworkState(wifiConnected, internetOk, detail);
    }

    private static boolean probeInternet() {
        // 只读取很小的响应，不上传账号、密码或任何个人数据。
        // 校园网可能拦截某个地址，因此准备两个轻量探测地址。
        String[] probeUrls = {
                "http://connect.rom.miui.com/generate_204",
                "http://connectivitycheck.platform.hicloud.com/generate_204"
        };

        for (String address : probeUrls) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setConnectTimeout(1800);
                connection.setReadTimeout(1800);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Cache-Control", "no-cache");
                connection.connect();

                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_NO_CONTENT) {
                    return true;
                }

                // 某些 ROM 的探测地址返回空的 200，也视作网络可用。
                if (code == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream(),
                                    StandardCharsets.UTF_8));
                    String firstLine = reader.readLine();
                    reader.close();
                    if (firstLine == null || firstLine.trim().isEmpty()) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
                // 尝试下一个地址；最终失败才判定互联网不可用。
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        return false;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        running = true;
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("后台监测已开启"));
        registerScreenReceiver();
        logEvent("后台监测已启动");

        if (!screenOn) {
            logEvent("服务启动时屏幕已熄灭，开始计算熄屏任务");
            scheduleScreenOffTasks();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean alarmAction = false;
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_FIRST_RESET.equals(action) || ACTION_PERIODIC_RESET.equals(action)) {
                alarmAction = true;
                handler.post(() -> handleAlarm(action));
            }
        }
        if (!alarmAction && !initialCheckDone) {
            initialCheckDone = true;
            networkExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    performInitialNetworkCheck();
                }
            });
        }
        return START_STICKY;
    }

    private void performInitialNetworkCheck() {
        NetworkState state = checkNetwork(this, true);
        logEvent("启动检查：" + state.detail);
        updateNotification(state.internetOk ? "网络正常，后台监测中" : "网络不可用，准备拉起广东校园");

        if (!state.internetOk) {
            handler.post(() -> {
                if (screenOn) {
                    launchClientAndReturnImmediately();
                } else {
                    launchClientOnly();
                }
            });
        }
    }

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    screenOn = false;
                    logEvent("检测到熄屏，30 秒后重置广东校园");
                    updateNotification("熄屏计时中");
                    scheduleScreenOffTasks();
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    screenOn = true;
                    cancelScreenOffTasks();
                    logEvent("检测到亮屏，已取消熄屏重置任务");
                    updateNotification("亮屏中，后台监测已开启");
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        screenOn = powerManager == null || powerManager.isInteractive();
    }

    private void scheduleScreenOffTasks() {
        cancelScreenOffTasks();
        scheduleAlarm(ACTION_FIRST_RESET, FIRST_RESET_REQUEST, FIRST_SCREEN_OFF_RESET_MS);
        logEvent("已安排省电唤醒闹钟：30 秒后检查熄屏状态");
    }

    private void cancelScreenOffTasks() {
        cancelAlarm(ACTION_FIRST_RESET, FIRST_RESET_REQUEST);
        cancelAlarm(ACTION_PERIODIC_RESET, PERIODIC_RESET_REQUEST);
    }

    private void handleAlarm(String action) {
        if (screenOn) {
            logEvent("闹钟触发时已经亮屏，取消本次熄屏任务");
            cancelScreenOffTasks();
            return;
        }

        if (ACTION_FIRST_RESET.equals(action)) {
            logEvent("熄屏已超过 30 秒，开始重置广东校园");
            resetClientInBackground();
            scheduleAlarm(ACTION_PERIODIC_RESET, PERIODIC_RESET_REQUEST,
                    SCREEN_OFF_RESET_INTERVAL_MS);
        } else if (ACTION_PERIODIC_RESET.equals(action)) {
            logEvent("持续熄屏已达到 40 分钟，重新重置广东校园");
            resetClientInBackground();
            scheduleAlarm(ACTION_PERIODIC_RESET, PERIODIC_RESET_REQUEST,
                    SCREEN_OFF_RESET_INTERVAL_MS);
        }
    }

    private void scheduleAlarm(String action, int requestCode, long delayMs) {
        if (alarmManager == null) {
            return;
        }
        Intent intent = new Intent(this, MonitorAlarmReceiver.class).setAction(action);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long triggerAt = android.os.SystemClock.elapsedRealtime() + delayMs;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // 使用系统允许的省电唤醒闹钟，不要求用户额外开启“精确闹钟”权限。
                // 系统可能有少量时间误差，但不会因为精确闹钟权限被拒绝而完全失效。
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
            } else {
                alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
            }
        } catch (Exception e) {
            logEvent("安排熄屏任务失败：" + e.getClass().getSimpleName());
        }
    }

    private void cancelAlarm(String action, int requestCode) {
        if (alarmManager == null) {
            return;
        }
        Intent intent = new Intent(this, MonitorAlarmReceiver.class).setAction(action);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarmManager.cancel(pendingIntent);
        pendingIntent.cancel();
    }

    /**
     * 亮屏时的恢复流程：
     * 拉起广东校园后，不点击页面、不等待固定时长；
     * 由无障碍服务在广东校园窗口出现的第一个事件中立即执行返回。
     */
    private void launchClientAndReturnImmediately() {
        boolean canReturn = ClientAccessibilityService.requestReturnOnNextClientWindow();
        logEvent("互联网不可用，立即拉起广东校园并准备返回原页面");
        if (!canReturn) {
            logEvent("无障碍未开启，广东校园可能无法自动返回原页面");
        }
        launchClientOnly();
    }

    private void launchClientOnly() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(
                    ClientAccessibilityService.CLIENT_PACKAGE);
            if (intent == null) {
                logEvent("未找到广东校园，请先安装官方客户端");
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
            logEvent("已启动广东校园");
        } catch (Exception e) {
            logEvent("启动广东校园失败：" + e.getClass().getSimpleName());
        }
    }

    private void resetClientInBackground() {
        updateNotification("正在后台重置广东校园");
        killClient();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!screenOn) {
                    launchClientOnly();
                    updateNotification("熄屏后台运行中");
                    logEvent("广东校园已重新启动");
                } else {
                    logEvent("重启前检测到亮屏，取消本次启动");
                }
            }
        }, CLIENT_RESTART_DELAY_MS);
    }

    private void killClient() {
        try {
            ActivityManager activityManager =
                    (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                activityManager.killBackgroundProcesses(
                        ClientAccessibilityService.CLIENT_PACKAGE);
                logEvent("已请求关闭广东校园后台进程");
            }
        } catch (Exception e) {
            logEvent("关闭广东校园失败：" + e.getClass().getSimpleName());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "aiqin 后台监测", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("aiqin")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private static void logEvent(String message) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date()) + " " + message;
        Log.d(TAG, line);
        synchronized (LOG_LOCK) {
            RECENT_LOGS.addLast(line);
            while (RECENT_LOGS.size() > 80) {
                RECENT_LOGS.removeFirst();
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (instance == this) {
            instance = null;
        }
        cancelScreenOffTasks();
        handler.removeCallbacksAndMessages(null);
        networkExecutor.shutdownNow();
        try {
            if (screenReceiver != null) {
                unregisterReceiver(screenReceiver);
            }
        } catch (Exception ignored) {
        }
        logEvent("后台监测已停止");
        super.onDestroy();
    }

    public static final class NetworkState {
        public final boolean wifiConnected;
        public final boolean internetOk;
        public final String detail;

        public NetworkState(boolean wifiConnected, boolean internetOk, String detail) {
            this.wifiConnected = wifiConnected;
            this.internetOk = internetOk;
            this.detail = detail;
        }
    }
}
