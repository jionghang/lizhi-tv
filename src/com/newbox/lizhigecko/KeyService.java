package com.newbox.lizhigecko;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** 全局遥控器按键监听（Firefox 前台时也能收到） */
public class KeyService extends AccessibilityService {
    public static final String TAG = "LWKey";
    public static final String ACTION_KEY = "com.newbox.lizhigecko.KEY";
    public static final String EXTRA_CODE = "code";
    private static KeyService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "onServiceConnected");
        // 主动申请按键过滤能力
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            setServiceInfo(info);
            Log.i(TAG, "flags set " + info.flags);
        }
    }

    public static KeyService getInstance() { return instance; }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "interrupt");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int kc = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN &&
                (kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN ||
                 kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT ||
                 kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_DPAD_CENTER ||
                 kc == KeyEvent.KEYCODE_BACK)) {
            Log.i(TAG, "KEY " + kc);
            // 广播给 TvService 处理；返回 true 拦截，避免 Firefox 也响应
            Intent it = new Intent(ACTION_KEY);
            it.setPackage(getPackageName());
            it.putExtra(EXTRA_CODE, kc);
            sendBroadcast(it);
            return true;
        }
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "service start");
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
