package com.wyu.esurfing;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 广东校园无障碍辅助服务。
 *
 * 核心功能只有一个：在熄屏重置流程中，可靠地点击"断开网络"按钮。
 * 点击方式三重兜底：
 *   1. 按文字精确匹配查找按钮
 *   2. 递归遍历所有可点击节点，用文字模糊匹配
 *   3. 直接按预估坐标发送点击（最稳的兜底）
 *
 * 不做账号、密码、验证码相关的任何操作。
 */
public class ClientAccessibilityService extends AccessibilityService {

    /**
     * 把无障碍的关键日志同步到 MonitorService，让用户在 UI 上能看到。
     */
    private static void logAccess(String msg) {
        Log.d(TAG, msg);
        try {
            // 用反射调用 MonitorService.logEvent，避免循环依赖问题
            java.lang.reflect.Method m = Class.forName("com.wyu.esurfing.MonitorService")
                    .getMethod("logAccessEvent", String.class);
            m.invoke(null, msg);
        } catch (Exception e) {
            // 忽略，写不到也不影响功能
        }
    }

    public static final String CLIENT_PACKAGE = "com.cndatacom.campus.cdccportalgd";
    private static final String TAG = "aiqin-access";

    // 目标按钮文字
    private static final String BTN_DISCONNECT = "断开网络";
    private static final String BTN_LOGIN = "点我登录";

    private static volatile ClientAccessibilityService instance;

    // 重登流程状态
    private static final int STATE_IDLE = 0;
    private static final int STATE_WAIT_DISCONNECT = 1;

    private int flowState = STATE_IDLE;
    private long flowStartedAt;
    private int clickAttempts = 0;
    private static final int MAX_CLICK_ATTEMPTS = 8;
    private static final long CLICK_INTERVAL_MS = 1500L;
    private static final long FLOW_TIMEOUT_MS = 20_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    public static boolean isRunning() {
        return instance != null;
    }

    /**
     * 启动点击"断开网络"的流程。
     * 成功点击后会回调 MonitorService.onDisconnectClicked()。
     * 超时也会回调，让 MonitorService 继续后续的杀进程重启流程。
     */
    public static boolean startDisconnectOnly() {
        ClientAccessibilityService svc = instance;
        if (svc == null) {
            Log.d(TAG, "无障碍未运行，无法执行断开操作");
            return false;
        }
        svc.handler.post(new Runnable() {
            @Override
            public void run() {
                svc.flowState = STATE_WAIT_DISCONNECT;
                svc.flowStartedAt = System.currentTimeMillis();
                svc.clickAttempts = 0;
                svc.handler.removeCallbacks(svc.flowTimeoutRunnable);
                svc.handler.removeCallbacks(svc.clickRunnable);
                svc.handler.post(svc.clickRunnable);
                svc.handler.postDelayed(svc.flowTimeoutRunnable, FLOW_TIMEOUT_MS);
                Log.d(TAG, "已启动断开网络流程，最多尝试 " + MAX_CLICK_ATTEMPTS + " 次");
                logAccess("启动断开网络流程");
            }
        });
        return true;
    }

    /**
     * 取消当前流程。
     */
    public static void cancelReloginFlow() {
        ClientAccessibilityService svc = instance;
        if (svc == null) return;
        svc.handler.post(new Runnable() {
            @Override
            public void run() {
                if (svc.flowState != STATE_IDLE) {
                    Log.d(TAG, "断开流程已取消");
                    svc.flowState = STATE_IDLE;
                    svc.handler.removeCallbacks(svc.clickRunnable);
                    svc.handler.removeCallbacks(svc.flowTimeoutRunnable);
                }
            }
        });
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.packageNames = new String[]{CLIENT_PACKAGE};
        setServiceInfo(info);
        Log.d(TAG, "无障碍服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;
        if (!CLIENT_PACKAGE.equals(event.getPackageName().toString())) return;
        // 事件只用来提示页面变化，点击逻辑由定时循环驱动，更可靠
    }

    /**
     * 点击任务：每隔一段时间尝试点一次"断开网络"。
     * 点到就回调 MonitorService，点满次数也回调（兜底走杀进程）。
     */
    private final Runnable clickRunnable = new Runnable() {
        @Override
        public void run() {
            if (flowState != STATE_WAIT_DISCONNECT) return;
            if (clickAttempts >= MAX_CLICK_ATTEMPTS) {
                Log.d(TAG, "已达到最大点击次数，回调 MonitorService 继续重启流程");
                flowState = STATE_IDLE;
                handler.removeCallbacks(flowTimeoutRunnable);
                MonitorService.onDisconnectClicked();
                return;
            }

            clickAttempts++;
            boolean clicked = tryClickDisconnect();
            Log.d(TAG, "第 " + clickAttempts + " 次点击断开网络，结果=" + clicked);
            logAccess("第" + clickAttempts + "次尝试点击，结果=" + (clicked ? "成功" : "失败"));

            if (clicked) {
                // 点到了，等 2 秒确认页面变化，然后回调
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (flowState != STATE_WAIT_DISCONNECT) return;
                        flowState = STATE_IDLE;
                        handler.removeCallbacks(flowTimeoutRunnable);
                        Log.d(TAG, "断开网络已点击，回调 MonitorService");
                    logAccess("已点击断开网络，开始重启");
                        MonitorService.onDisconnectClicked();
                    }
                }, 2000L);
                return;
            }

            // 没点到，继续下一轮
            handler.postDelayed(this, CLICK_INTERVAL_MS);
        }
    };

    private final Runnable flowTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (flowState == STATE_IDLE) return;
            Log.d(TAG, "断开网络流程超时，兜底回调 MonitorService");
            logAccess("点击超时，兜底杀进程重启");
            flowState = STATE_IDLE;
            handler.removeCallbacks(clickRunnable);
            MonitorService.onDisconnectClicked();
        }
    };

    /**
     * 三重方式点击"断开网络"：
     *   1. findAccessibilityNodeInfosByText 精确查找
     *   2. 递归遍历所有节点，模糊匹配文字
     *   3. 按屏幕预估坐标直接点击（兜底）
     */
    private boolean tryClickDisconnect() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.d(TAG, "无法获取根节点，尝试坐标兜底点击");
            return clickByEstimatedPosition();
        }

        // 方式 1：按文字精确查找
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(BTN_DISCONNECT);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isVisibleToUser() && clickNode(node)) {
                    Log.d(TAG, "方式1成功：按文字找到并点击");
                    return true;
                }
            }
        }

        // 方式 2：递归遍历，模糊匹配所有可点击节点的文字和 contentDescription
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectClickableNodes(root, candidates);
        for (AccessibilityNodeInfo node : candidates) {
            if (textMatches(node, BTN_DISCONNECT)) {
                if (clickNode(node)) {
                    Log.d(TAG, "方式2成功：递归遍历找到并点击");
                    return true;
                }
            }
        }

        // 方式 3：坐标兜底
        Log.d(TAG, "文字匹配都没找到，用坐标兜底点击");
        return clickByEstimatedPosition();
    }

    /**
     * 递归收集所有可点击节点。
     */
    private void collectClickableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isClickable() && node.isVisibleToUser()) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectClickableNodes(child, out);
            }
        }
    }

    /**
     * 判断节点文字是否匹配目标。
     * 检查 text 和 contentDescription，支持包含匹配。
     */
    private boolean textMatches(AccessibilityNodeInfo node, String target) {
        CharSequence text = node.getText();
        if (text != null && text.toString().contains(target)) {
            return true;
        }
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.toString().contains(target)) {
            return true;
        }
        // 也检查子节点里有没有（有时候文字在 Button 内部的 TextView 上）
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                CharSequence ct = child.getText();
                if (ct != null && ct.toString().contains(target)) {
                    return true;
                }
                CharSequence cd = child.getContentDescription();
                if (cd != null && cd.toString().contains(target)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 点击一个节点：优先 performAction，不行就用坐标。
     */
    private boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        // 方式 A：直接 performAction
        if (node.isClickable() && node.isEnabled()) {
            boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (ok) return true;
        }
        // 方式 B：找到父节点中可点击的那个，点它
        AccessibilityNodeInfo parent = node.getParent();
        int depth = 0;
        while (parent != null && depth < 3) {
            if (parent.isClickable() && parent.isEnabled()) {
                boolean ok = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (ok) return true;
            }
            parent = parent.getParent();
            depth++;
        }
        // 方式 C：用坐标点
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if (rect.width() > 0 && rect.height() > 0) {
            return clickAt(rect.centerX(), rect.centerY());
        }
        return false;
    }

    /**
     * 预估"断开网络"按钮的位置，直接发送手势点击。
     * 从你的截图看，按钮在屏幕中央偏上的大圆形区域内部下方。
     * 坐标按屏幕比例估算：水平 50%，垂直约 38%。
     */
    private boolean clickByEstimatedPosition() {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int width = dm.widthPixels;
            int height = dm.heightPixels;
            // 多个候选位置，按尝试顺序轮流点
            // 从截图看，按钮在大圆内部下方，大概是屏幕高度的 30%~38% 之间
            // 不同手机屏幕比例不同，所以多试几个位置
            float[] yRatios = { 0.31f, 0.34f, 0.37f, 0.30f, 0.39f };
            int attemptIndex = clickAttempts % yRatios.length;
            float yRatio = yRatios[attemptIndex];
            int x = width / 2;
            int y = (int) (height * yRatio);
            Log.d(TAG, "坐标兜底点击（第" + clickAttempts + "次）：(" + x + ", " + y + ")，屏幕 " + width + "x" + height);
            logAccess("坐标点击(" + attemptIndex + ")：" + x + "," + y);
            return clickAt(x, y);
        } catch (Exception e) {
            Log.d(TAG, "坐标点击失败：" + e.getMessage());
            return false;
        }
    }

    /**
     * 通过 GestureDescription 发送精确点击。
     * 这是 Android 7+ 无障碍服务最可靠的坐标点击方式。
     */
    private boolean clickAt(int x, int y) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return false;
        }
        try {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x, y);
            android.accessibilityservice.GestureDescription.Builder builder =
                    new android.accessibilityservice.GestureDescription.Builder();
            builder.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(
                    path, 0, 100));
            return dispatchGesture(builder.build(), null, null);
        } catch (Exception e) {
            Log.d(TAG, "dispatchGesture 失败：" + e.getMessage());
            return false;
        }
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
        flowState = STATE_IDLE;
        handler.removeCallbacksAndMessages(null);
        Log.d(TAG, "无障碍服务已停止");
        super.onDestroy();
    }
}
