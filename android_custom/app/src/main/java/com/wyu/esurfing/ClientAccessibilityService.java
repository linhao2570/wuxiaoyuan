package com.wyu.esurfing;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * 小型无障碍服务，只监听广东校园客户端。
 *
 * 功能分两类：
 * 1. 亮屏快速返回：启动广东校园后窗口一出现就返回上一级，回到用户原应用。
 * 2. 熄屏重置流程：检测到客户端后，先点断开网络，回调 MonitorService 杀进程重启，
 *    重启后再把客户端压回后台。
 *
 * 不扫描其它应用，也不读取账号、密码或验证码等敏感字段。
 */
public class ClientAccessibilityService extends AccessibilityService {

    public static final String CLIENT_PACKAGE = "com.cndatacom.campus.cdccportalgd";
    private static final String TAG = "aiqin-access";

    // 页面上的按钮文案，从实际截图提取
    private static final String BTN_DISCONNECT = "断开网络";
    private static final String BTN_LOGIN = "点我登录";

    // 熄屏重置流程的状态机
    private static final int STATE_IDLE = 0;
    private static final int STATE_WAIT_DISCONNECT = 1;
    private static final int STATE_WAIT_LOGIN_BUTTON = 2;
    private static final int STATE_WAIT_LOGIN_DONE = 3;
    // 只点断开，点完就回调 MonitorService 去杀进程重启
    private static final int STATE_WAIT_DISCONNECT_ONLY = 4;

    // 等登录按钮出现的稳定时间（点完断开后）
    private static final long WAIT_LOGIN_BUTTON_MS = 20_000L;
    // 点完登录后，给连接过程留一点时间，然后再压回后台
    private static final long AFTER_LOGIN_BACK_DELAY_MS = 8_000L;
    // 返回键最多尝试次数，确保回到原应用
    private static final int BACK_ATTEMPTS = 3;
    // 返回键每次间隔
    private static final long BACK_INTERVAL_MS = 600L;

    private static volatile ClientAccessibilityService instance;
    private static volatile boolean returnOnNextClientWindow;

    // 重登流程状态，都在主线程 handler 上操作
    private int flowState = STATE_IDLE;
    private long flowStartedAt;
    private boolean flowShouldReturnAfter = true;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastClickAt;

    public static boolean isRunning() {
        return instance != null;
    }

    /**
     * 标记一次：广东校园下次出现窗口时立即返回。
     */
    public static boolean requestReturnOnNextClientWindow() {
        if (instance == null) {
            returnOnNextClientWindow = false;
            Log.d(TAG, "未启用无障碍，无法自动返回原页面");
            return false;
        }
        returnOnNextClientWindow = true;
        Log.d(TAG, "已标记：广东校园窗口出现后立即返回");
        return true;
    }

    /**
     * 只点击断开网络按钮，断开成功后回调 MonitorService.onDisconnectClicked() 去杀进程重启。
     * 这样做的重置效果比单纯杀进程更彻底。
     */
    public static boolean startDisconnectOnly() {
        ClientAccessibilityService svc = instance;
        if (svc == null) {
            Log.d(TAG, "未启用无障碍，无法执行断开操作");
            return false;
        }
        svc.handler.post(new Runnable() {
            @Override
            public void run() {
                svc.flowState = STATE_WAIT_DISCONNECT_ONLY;
                svc.flowStartedAt = System.currentTimeMillis();
                svc.handler.removeCallbacks(svc.flowTimeoutRunnable);
                svc.handler.postDelayed(svc.flowTimeoutRunnable, 15000L);
                Log.d(TAG, "已触发：只点断开网络");
            }
        });
        return true;
    }

    /**
     * 主动开始一轮"断开 + 重新登录"的无障碍流程。
     * 调用方需确保先把广东校园拉到前台。
     *
     * @param returnAfterLogin 登录完成后是否自动返回原应用
     * @return 是否已成功触发
     */
    public static boolean startReloginFlow(boolean returnAfterLogin) {
        ClientAccessibilityService svc = instance;
        if (svc == null) {
            Log.d(TAG, "未启用无障碍，无法执行重登流程");
            return false;
        }
        svc.handler.post(new Runnable() {
            @Override
            public void run() {
                svc.flowState = STATE_WAIT_DISCONNECT;
                svc.flowStartedAt = System.currentTimeMillis();
                svc.flowShouldReturnAfter = returnAfterLogin;
                svc.handler.removeCallbacks(svc.flowTimeoutRunnable);
                svc.handler.postDelayed(svc.flowTimeoutRunnable, WAIT_LOGIN_BUTTON_MS + 10_000L);
                Log.d(TAG, "重登流程已启动，等待出现断开网络按钮");
            }
        });
        return true;
    }

    /**
     * 取消当前重登流程（比如用户突然亮屏，就不要再继续点了）
     */
    public static void cancelReloginFlow() {
        ClientAccessibilityService svc = instance;
        if (svc == null) return;
        svc.handler.post(new Runnable() {
            @Override
            public void run() {
                if (svc.flowState != STATE_IDLE) {
                    Log.d(TAG, "重登流程已取消");
                    svc.flowState = STATE_IDLE;
                    svc.handler.removeCallbacks(svc.flowTimeoutRunnable);
                }
            }
        });
    }

    /**
     * 主动发送多次返回键，把广东校园压回后台
     */
    public static boolean performBackNow() {
        return performBackNow(BACK_ATTEMPTS);
    }

    public static boolean performBackNow(int maxAttempts) {
        ClientAccessibilityService svc = instance;
        if (svc == null) {
            Log.d(TAG, "未启用无障碍，无法执行返回");
            return false;
        }
        final int attempts = Math.max(1, Math.min(maxAttempts, 6));
        svc.handler.post(new Runnable() {
            private int remaining = attempts;
            @Override
            public void run() {
                if (remaining <= 0) return;
                boolean sent = svc.performGlobalAction(GLOBAL_ACTION_BACK);
                Log.d(TAG, "主动执行返回，剩余次数=" + remaining + "，结果=" + sent);
                remaining--;
                if (remaining > 0) {
                    svc.handler.postDelayed(this, BACK_INTERVAL_MS);
                }
            }
        });
        return true;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 200;
        info.packageNames = new String[]{CLIENT_PACKAGE};
        setServiceInfo(info);
        Log.d(TAG, "无障碍服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;
        if (!CLIENT_PACKAGE.equals(event.getPackageName().toString())) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // 快速返回：优先级最高，只要设了标记就立刻返回
        if (returnOnNextClientWindow) {
            returnOnNextClientWindow = false;
            performBackNow();
            return;
        }

        // 重登流程只在主线程有序处理
        handler.post(new Runnable() {
            @Override
            public void run() {
                handleReloginState(root);
            }
        });
    }

    private void handleReloginState(AccessibilityNodeInfo root) {
        if (flowState == STATE_IDLE) return;

        long now = System.currentTimeMillis();

        switch (flowState) {
            case STATE_WAIT_DISCONNECT_ONLY:
                // 只点一次断开网络，点完就通知 MonitorService 杀进程重启
                if (hasVisibleText(root, BTN_DISCONNECT)) {
                    if (now - lastClickAt > 2000L && clickFirstVisible(root, BTN_DISCONNECT)) {
                        lastClickAt = now;
                        flowState = STATE_IDLE;
                        handler.removeCallbacks(flowTimeoutRunnable);
                        Log.d(TAG, "已点击断开网络，通知 MonitorService 杀进程重启");
                        MonitorService.onDisconnectClicked();
                    }
                } else if (hasVisibleText(root, BTN_LOGIN)) {
                    // 本来就是已断开状态，直接让 MonitorService 重启
                    flowState = STATE_IDLE;
                    handler.removeCallbacks(flowTimeoutRunnable);
                    Log.d(TAG, "当前已是登录页，直接通知 MonitorService 重启客户端");
                    MonitorService.onDisconnectClicked();
                }
                break;

            case STATE_WAIT_DISCONNECT:
                if (hasVisibleText(root, BTN_DISCONNECT)) {
                    if (now - lastClickAt > 2000L && clickFirstVisible(root, BTN_DISCONNECT)) {
                        lastClickAt = now;
                        flowState = STATE_WAIT_LOGIN_BUTTON;
                        flowStartedAt = now;
                        Log.d(TAG, "已点断开网络，等待出现点我登录按钮");
                        handler.removeCallbacks(flowTimeoutRunnable);
                        handler.postDelayed(flowTimeoutRunnable, WAIT_LOGIN_BUTTON_MS);
                    }
                } else if (hasVisibleText(root, BTN_LOGIN)) {
                    // 如果一打开就已经是登录页面，就直接进入下一步
                    flowState = STATE_WAIT_LOGIN_BUTTON;
                    flowStartedAt = now;
                    Log.d(TAG, "初始就是登录页面，直接等待点登录");
                }
                break;

            case STATE_WAIT_LOGIN_BUTTON:
                if (hasVisibleText(root, BTN_LOGIN)) {
                    if (now - lastClickAt > 2000L && clickFirstVisible(root, BTN_LOGIN)) {
                        lastClickAt = now;
                        flowState = STATE_WAIT_LOGIN_DONE;
                        flowStartedAt = now;
                        Log.d(TAG, "已点点我登录，等待连接成功");
                        handler.removeCallbacks(flowTimeoutRunnable);
                        handler.postDelayed(loginDoneRunnable, AFTER_LOGIN_BACK_DELAY_MS);
                    }
                }
                break;

            case STATE_WAIT_LOGIN_DONE:
                // 这里不做复杂判断，直接等固定时间后返回。
                // 因为熄屏时用户看不到，多等一会儿更稳。
                break;
        }
    }

    private final Runnable loginDoneRunnable = new Runnable() {
        @Override
        public void run() {
            if (flowState != STATE_WAIT_LOGIN_DONE) return;
            flowState = STATE_IDLE;
            Log.d(TAG, "重登流程完成");
            if (flowShouldReturnAfter) {
                performBackNow(BACK_ATTEMPTS);
            }
        }
    };

    private final Runnable flowTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (flowState == STATE_IDLE) return;
            Log.d(TAG, "重登流程超时，当前状态=" + flowState);
            flowState = STATE_IDLE;
            // 超时也尝试返回，别让广东校园停在前台
            if (flowShouldReturnAfter) {
                performBackNow(BACK_ATTEMPTS);
            }
        }
    };

    private boolean hasVisibleText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isVisibleToUser()) return true;
        }
        return false;
    }

    private boolean clickFirstVisible(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isVisibleToUser() && node.isEnabled()) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "无障碍服务被系统中断");
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        returnOnNextClientWindow = false;
        flowState = STATE_IDLE;
        handler.removeCallbacksAndMessages(null);
        Log.d(TAG, "无障碍服务已停止");
        super.onDestroy();
    }
}
