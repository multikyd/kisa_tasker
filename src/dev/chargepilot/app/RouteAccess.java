package dev.chargepilot.app;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class RouteAccess extends AccessibilityService {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static volatile RouteAccess instance;
    private String eventPackage = "";
    private String windowClass = "";

    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
    @Override public void onInterrupt() {}
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() != null && event.getClassName() != null) {
            eventPackage = event.getPackageName().toString();
            windowClass = event.getClassName().toString();
        }
    }

    static boolean end(String target, BooleanSupplier current) throws Exception {
        RouteAccess service = instance;
        if (service == null) return false;
        boolean pressed = false;
        boolean back = false;
        int clearFrames = 0;
        long start = android.os.SystemClock.elapsedRealtime();
        for (int pass = 0; pass < 11 && current.getAsBoolean(); pass++) {
            final boolean hadAction = pressed;
            final boolean mayBack = !back && !pressed
                    && android.os.SystemClock.elapsedRealtime() - start > 1_100;
            FutureTask<Integer> step = new FutureTask<>(() ->
                    current.getAsBoolean() ? service.endStep(target, hadAction, mayBack) : 0);
            MAIN.post(step);
            int result;
            try {
                result = step.get(600, TimeUnit.MILLISECONDS);
            } finally {
                step.cancel(false);
            }
            clearFrames = result == 4 ? clearFrames + 1 : 0;
            if (clearFrames >= 2) return true;
            if (result == 3) return false;
            if (result == 2) back = true;
            if (result == 1) pressed = true;
            Thread.sleep(320);
        }
        return false;
    }

    private int endStep(String target, boolean pressed, boolean mayBack) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !target.contentEquals(root.getPackageName())) return 0;
        String[] prompts = {"안내를 종료하시겠습니까?", "안내를 종료할까요?", "경로 안내를 종료하시겠습니까?",
                "길안내를 종료할까요?", "경로를 취소할까요?", "End navigation?"};
        String[] stop = {"안내 종료", "길안내 종료", "경로 취소", "주행 종료", "안심 주행 종료",
                "전체 안내 종료", "End navigation", "Cancel route", "Stop guidance"};
        boolean confirmation = find(root, prompts) != null;
        AccessibilityNodeInfo button = find(root, confirmation
                ? new String[]{"종료", "종료하기", "안내 종료", "경로 취소", "확인", "End", "OK"} : stop);
        if (button != null && click(button)) return 1;
        if (pressed && !confirmation && button == null) return 4;
        if (mayBack && target.equals(State.TMAP) && target.equals(eventPackage)
                && windowClass.endsWith(".TmapNaviActivity")
                && performGlobalAction(GLOBAL_ACTION_BACK)) return 2;
        return 0;
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo root, String[] labels) {
        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int count = 0;
        while (!queue.isEmpty() && count++ < 1500) {
            AccessibilityNodeInfo node = queue.remove();
            if (node.isVisibleToUser()) {
                for (String label : labels) {
                    String key = normalize(label);
                    if (key.equals(normalize(node.getText()))
                            || key.equals(normalize(node.getContentDescription()))) {
                        return node;
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    private boolean click(AccessibilityNodeInfo node) {
        for (int depth = 0; node != null && depth < 4; depth++, node = node.getParent()) {
            if (node.isClickable() && node.isEnabled()
                    && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return false;
    }

    private String normalize(CharSequence value) {
        return value == null ? "" : value.toString().replaceAll("\\s+", "")
                .toLowerCase(java.util.Locale.ROOT);
    }
}
