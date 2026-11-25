package com.newbox.lizhigecko;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Root 按键监听：直接读 /dev/input/event* 的原始事件流。
 * 无障碍服务（KeyService）在某些系统上收不到按键时的兜底方案。
 * 盒子已 root，此路稳定可靠。
 */
class EvdevKeyWatcher implements Runnable {
    private static final String TAG = "LWKey";
    interface Listener {
        void onKey(int keyCode);
    }

    private final Listener listener;
    private volatile boolean running = true;
    private Process su;

    // Linux input event key codes -> Android KeyEvent
    private static final int[] KEY_MAP = {
            103, android.view.KeyEvent.KEYCODE_DPAD_UP,
            108, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            105, android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            106, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            28,  android.view.KeyEvent.KEYCODE_ENTER,
            158, android.view.KeyEvent.KEYCODE_BACK,
            139, android.view.KeyEvent.KEYCODE_MENU,
            172, android.view.KeyEvent.KEYCODE_GUIDE,
    };

    EvdevKeyWatcher(Listener listener) {
        this.listener = listener;
    }

    void stop() {
        running = false;
        if (su != null) {
            try { su.destroy(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void run() {
        // 依次监听可能的遥控器节点，某个失败就换下一个
        String[] devices = {"/dev/input/event0", "/dev/input/event3", "/dev/input/event2",
                "/dev/input/event1", "/dev/input/event5"};
        while (running) {
            for (String dev : devices) {
                if (!running) break;
                try {
                    watchDevice(dev);
                } catch (Throwable e) {
                    Log.w(TAG, "evdev watch " + dev + " ended: " + e);
                }
                if (running) {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                }
            }
        }
    }

    private void watchDevice(String dev) throws Exception {
        String[] cmd = {"su", "-c", "getevent -l " + dev};
        su = Runtime.getRuntime().exec(cmd);
        BufferedReader br = new BufferedReader(new InputStreamReader(su.getInputStream()));
        String line;
        while (running && (line = br.readLine()) != null) {
            // 输出示例: /dev/input/event0: EV_KEY KEY_UP DOWN
            if (!line.contains("EV_KEY")) continue;
            if (!line.contains(" DOWN")) continue;   // 只处理按下
            for (int i = 0; i < KEY_MAP.length; i += 2) {
                if (line.contains("KEY_" + nameOf(KEY_MAP[i]))) {
                    Log.i(TAG, "evdev key " + KEY_MAP[i] + " from " + dev);
                    listener.onKey(KEY_MAP[i + 1]);
                    break;
                }
            }
        }
    }

    /** Linux key code -> getevent 里显示的 KEY_ 名称 */
    private static String nameOf(int code) {
        switch (code) {
            case 103: return "UP";
            case 108: return "DOWN";
            case 105: return "LEFT";
            case 106: return "RIGHT";
            case 28:  return "ENTER";
            case 158: return "BACK";
            case 139: return "MENU";
            case 172: return "GUIDE";
            default:  return "UNKNOWN";
        }
    }
}
