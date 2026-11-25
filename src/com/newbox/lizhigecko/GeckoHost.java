package com.newbox.lizhigecko;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;

/**
 * GeckoView 宿主：整个 App 只有一份 runtime + 一份 session。
 *
 * 存在的理由：荔枝 CDN 按 TLS/JA3 指纹下发 token，只有 NSS 栈（Gecko/Firefox）
 * 能拿到 256B 可播 token。这里把 Gecko 嵌进进程内，取 token 和播放都在同一栈完成，
 * 不再需要外挂 Firefox。
 *
 * 注意：GeckoView 69 的 GeckoSession 没有 evaluateJS，页面里要做什么只能靠
 * WebExtension 的 content script（后续需要时再加）。
 */
public final class GeckoHost {
    private static final String TAG = "LizhiGecko";

    private static volatile GeckoSession session;
    private static volatile GeckoRuntime runtime;
    private static volatile String currentUrl;

    /** 页面加载进度回调：给悬浮层做"换台加载遮罩"用 */
    interface PageListener {
        void onPageStart(String url);

        void onLocationChange(String url);

        void onPageStop(String url, boolean success);

        void onFirstComposite();
    }

    private static volatile PageListener pageListener;

    /** 当前页面是否已经加载完（冷启动 / 服务重启时用来决定要不要亮加载遮罩） */
    private static volatile boolean pageLoaded;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private GeckoHost() {
    }

    static void setPageListener(PageListener l) {
        pageListener = l;
    }

    static boolean isPageLoaded() {
        return pageLoaded;
    }

    static void firePageStart(String url) {
        pageLoaded = false;
        PageListener l = pageListener;
        if (l != null) l.onPageStart(url);
    }

    static void fireLocationChange(String url) {
        PageListener l = pageListener;
        if (l != null) l.onLocationChange(url);
    }

    static void firePageStop(String url, boolean success) {
        pageLoaded = true;
        PageListener l = pageListener;
        if (l != null) l.onPageStop(url, success);
    }

    static void fireFirstComposite() {
        PageListener l = pageListener;
        if (l != null) l.onFirstComposite();
    }

    /** 广播播放时把 Gecko 的媒体停掉，避免和 MediaPlayer 叠音 */
    static void setActive(final boolean active) {
        final GeckoSession s = session;
        if (s == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    s.setActive(active);
                } catch (Throwable e) {
                    Log.w(TAG, "setActive fail " + e);
                }
            }
        });
    }

    static void bind(GeckoRuntime rt, GeckoSession s) {
        runtime = rt;
        session = s;
    }

    public static boolean isReady() {
        return session != null;
    }

    /** 换台 / 打开任意页面；session 未就绪时返回 false，由调用方去拉 Activity */
    public static boolean load(final String url) {
        final GeckoSession s = session;
        if (s == null) {
            Log.w(TAG, "load skipped, session null: " + url);
            return false;
        }
        if (url.equals(currentUrl)) {
            // 同一个地址不重复加载（Activity 已经开过一次，服务首次 tune 会再来一发）
            Log.i(TAG, "load skip (same url) " + url);
            return true;
        }
        currentUrl = url;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    s.loadUri(url);
                    Log.i(TAG, "loadUri " + url);
                } catch (Throwable e) {
                    Log.e(TAG, "loadUri fail " + e);
                }
            }
        });
        return true;
    }

    /** 重新加载当前页（菜单里的"刷新频道"用，解决画面卡死 / 黑屏） */
    public static boolean reload() {
        final GeckoSession s = session;
        final String u = currentUrl;
        if (s == null || u == null) return false;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    s.loadUri(u);
                    Log.i(TAG, "reload " + u);
                } catch (Throwable e) {
                    Log.e(TAG, "reload fail " + e);
                }
            }
        });
        return true;
    }

    /** 重新加载当前页（菜单里的"刷新频道"用，解决画面卡死 / 黑屏） */
    public static String currentUrl() {
        return currentUrl;
    }
}
