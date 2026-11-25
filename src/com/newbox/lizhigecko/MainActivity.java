package com.newbox.lizhigecko;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;

/**
 * 一体化入口：GeckoView 占满整屏播放荔枝频道页，悬浮控制层由 TvService 叠加在上面。
 *
 * 页面本身是 www.gdtv.cn 的 H5 播放器，靠 NSS 栈拿到真 token；不存在 Firefox 的
 * 地址栏、标签堆积、返回键要按两次的问题。
 */
public class MainActivity extends Activity {
    private static final String TAG = "LizhiGecko";
    public static final String EXTRA_URL = "extra_url";

    private static GeckoRuntime sRuntime;
    private static GeckoSession sSession;
    static volatile MainActivity sInstance;

    private GeckoView geckoView;
    private FrameLayout root;
    private FrameLayout.LayoutParams geckoParams;

    /**
     * 画面裁切比例的默认值。
     *
     * 网页"全屏"这件事没有通用魔法：HTML 全屏必须页面自己调 requestFullscreen()，
     * 我们这边已经接管了 GeckoView 的 onFullScreen 回调（页面真请求全屏时会直接铺满），
     * 但荔枝的 H5 播放器既没有全屏按钮、页面按钮在纯遥控器场景下也点不到。
     *
     * 所以剩下最可靠的办法就是几何裁切：把页面顶部那条移动版导航栏（实测 137 CSS px）
     * 移到屏幕外，视频刚好 1280x720 CSS = 1920x1080 物理像素，正好铺满。
     *
     * 每个站点顶栏高度不一样，所以这里不做全局常量，而是按 host 分别记忆
     * （SharedPreferences 的 crop_&lt;host&gt;），没记过的 host 默认不裁。
     */
    private static final float DEFAULT_GDTV_CROP = 137f / 1280f;

    /**
     * 央视 /m/ 页播放器顶边距。实测（2026-11-25，dpr 抬到 2.5625 之后抓页面像素）：
     *   0   ~ 80   物理像素  #333 灰顶栏（.headernew）
     *   80  ~ 208  物理像素  页面留白/海报区
     *   208 ~      播放器（此时是黑的，还没起播）
     * 播放器本身 749.3x421.5 CSS = 1920x1080 物理，所以裁掉 208 就是真满屏。
     */
    private static final float DEFAULT_CCTV_CROP = 208f / 1920f;

    private String cropHost = "";

    private void applyCropFor(String url) {
        String host = hostOf(url);
        // about:blank 之类的空 host 不改布局，避免切站瞬间画面跳一下
        if (host.length() == 0 || host.equals(cropHost)) return;
        cropHost = host;
        boolean lizhi = host.endsWith("gdtv.cn") || host.endsWith("itouchtv.cn");
        float def = lizhi ? DEFAULT_GDTV_CROP
                : (host.endsWith("cctv.com") ? DEFAULT_CCTV_CROP : 0f);
        float ratio = getSharedPreferences("lizhi", MODE_PRIVATE).getFloat("crop_" + host, def);
        applyCropRatio(ratio);
        Log.i(TAG, "crop host=" + host + " ratio=" + ratio);
    }

    /**
     * 过滤掉已经不再支持的页面地址。
     *
     * v1.3 起不含央视 / 节目库 / 广播回放，但 LastChannel 里可能还存着 v1.1/v1.2 留下的
     * 央视页面（`tv.cctv.com/...`）。如果照原样加载，会出现"页面是央视、悬浮层显示第 1 台"
     * 的错位。所以这里只认 gdtv 的页面，其余按记下的 pk 在频道表里重新解析，
     * 找不到就退到第一个频道 —— 和 TvService 里 indexOf 返回 -1 的处理保持一致。
     */
    private String sanitizeUrl(String url) {
        if (url == null) return null;
        if (url.contains("gdtv.cn")) return url;
        boolean tv = LastChannel.isTv(this);
        ChannelRepo r = new ChannelRepo(this);
        java.util.List<ChannelRepo.Ch> l = r.list(tv);
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).pk == LastChannel.pk(this)) return l.get(i).pageUrl();
        }
        if (!l.isEmpty()) return l.get(0).pageUrl();
        return null;
    }

    private static String hostOf(String url) {
        if (url == null) return "";
        // 内置页面（比如 CCTV 自建播放页）。URI 能解析出 host=android，
        // 但这里显式认一下，避免某些 URL 形态解析成空 host 后沿用上一个站的裁切值。
        if (url.startsWith("resource://android/")) return "android";
        try {
            String h = java.net.URI.create(url).getHost();
            return h == null ? "" : h;
        } catch (Throwable e) {
            return "";
        }
    }

    /** ratio = 裁掉的高度 / 屏幕宽；0 表示不裁 */
    private void applyCropRatio(float ratio) {        if (ratio < 0f) ratio = 0f;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        // 几何账（dpr 1.5）：header 137 CSS = 205.5 物理，视频 720 CSS = 1080 物理，
        // 屏幕也是 1080。视频顶 = 205.5 - offset，视频底 = 1285.5 - offset。
        // 要让顶 ≤ 0 必须 offset ≥ 205.5，要让底 ≥ 1080 必须 offset ≤ 205.5 ——
        // **无解**，因为 205.5 落在半个物理像素上。所以永远差 0.5~1.5 个物理像素：
        // 取 206 时顶部不出 header、底部露 0.5px 白；
        // 取 204 时底部干净、顶部露 1.5px 的 header 灰底。
        // 选择：顶部不能露（header 是浅灰，很扎眼），底部那 0.5~1.5px 交给
        // OverlayView 的底部黑条盖掉。
        int offset = Math.round(screenW * ratio);
        geckoParams = new FrameLayout.LayoutParams(screenW, screenH + offset);
        geckoParams.topMargin = -offset;
        if (geckoView != null) geckoView.setLayoutParams(geckoParams);
        Log.i(TAG, "videoCrop offset=" + offset + " viewH=" + (screenH + offset)
                + " videoTop=" + (205.5f - offset) + " videoBottom=" + (1285.5f - offset));
    }

    // ---------------------------------------------------------------- CCTV 画面几何诊断

    /*
     * dpr 抬到 2.5625 之后（见 ensureSession），央视 /m/ 页的播放器正好是
     * 1920x1080 物理像素，剩下的问题只是"播放器顶边在屏幕的哪个 y"。
     * 这个值只能靠看像素：抓一帧，从上往下找两个特征——
     *   · #333 灰顶栏结束在哪一行（.headernew{background-color:#333333}）
     *   · 播放器（起播前是纯黑）从哪一行开始
     * 实测结论已固化成 DEFAULT_CCTV_CROP；这里只打日志，不再自动改裁切，
     * 免得页面状态一变就跟着乱动。
     */
    private static final int MEASURE_W = 480, MEASURE_H = 270;

    private void maybeAutoCrop(String url) {
        if (url == null || !url.contains("cctv.com/")) return;
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        for (int i = 1; i <= 3; i++) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    measureHeader();
                }
            }, 3000L * i);
        }
    }

    private void measureHeader() {
        final GeckoView gv = geckoView;
        if (gv == null) return;
        try {
            gv.capturePixels().then(new GeckoResult.OnValueListener<android.graphics.Bitmap, Void>() {
                @Override
                public GeckoResult<Void> onValue(android.graphics.Bitmap b) {
                    if (b != null && !b.isRecycled()) scanHeader(
                            android.graphics.Bitmap.createScaledBitmap(b, MEASURE_W, MEASURE_H, true));
                    return null;
                }
            }, new GeckoResult.OnExceptionListener<Void>() {
                @Override
                public GeckoResult<Void> onException(Throwable e) {
                    Log.w(TAG, "cctv measure fail " + e);
                    return null;
                }
            });
        } catch (Throwable e) {
            Log.w(TAG, "cctv measure throw " + e);
        }
    }

    private void scanHeader(android.graphics.Bitmap b) {
        int gray = 0;
        while (gray < b.getHeight() && isBarRow(b, gray)) gray++;
        int black = -1;
        for (int y = 0; y < b.getHeight(); y++) {
            if (isBlackRow(b, y)) { black = y; break; }
        }
        Log.i(TAG, "cctv scan grayTop=" + (gray * 4) + "px blackTop="
                + (black < 0 ? -1 : black * 4) + "px profile=" + profile(b));
    }

    /** 诊断用：把前 60 行（约前 240 物理像素）的灰度范围打成一行 */
    private static String profile(android.graphics.Bitmap b) {
        StringBuilder sb = new StringBuilder();
        int h = Math.min(60, b.getHeight());
        for (int y = 0; y < h; y += 2) {
            int min = 255, max = 0;
            for (int x = 2; x < b.getWidth(); x += 6) {
                int p = b.getPixel(x, y);
                int g = (p >> 8) & 0xFF;
                if (g < min) min = g;
                if (g > max) max = g;
            }
            sb.append(y * 4).append(':').append(min).append('-').append(max).append(' ');
        }
        return sb.toString();
    }

    /** 整行都是接近 #333 的灰 —— 顶栏 */
    private static boolean isBarRow(android.graphics.Bitmap b, int y) {
        int min = 255, max = 0;
        for (int x = 2; x < b.getWidth(); x += 6) {
            int p = b.getPixel(x, y);
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, bl = p & 0xFF;
            if (Math.abs(r - g) > 14 || Math.abs(g - bl) > 14) return false;
            if (g < min) min = g;
            if (g > max) max = g;
        }
        return (max - min) <= 14 && min >= 30 && max <= 90;
    }

    /** 整行都是黑的 —— 起播前的视频区 */
    private static boolean isBlackRow(android.graphics.Bitmap b, int y) {
        for (int x = 2; x < b.getWidth(); x += 6) {
            int p = b.getPixel(x, y);
            if (((p >> 16) & 0xFF) > 28 || ((p >> 8) & 0xFF) > 28 || (p & 0xFF) > 28) return false;
        }
        return true;
    }

    /** 广播播放时把画面藏掉（悬浮层的广播界面会盖上来） */
    public static void setGeckoVisible(final boolean visible) {
        final MainActivity a = sInstance;
        if (a == null) return;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (a.geckoView != null) {
                    a.geckoView.setVisibility(visible ? android.view.View.VISIBLE
                            : android.view.View.INVISIBLE);
                }
            }
        });
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sInstance = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 开机自启已关闭（用户要求：接线通电后不要自动进直播），
        // 原来这里还会把 BootReceiver 强制 enable 一遍，一并去掉。

        String url = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_URL);
        if (url == null) {
            url = GeckoHost.currentUrl();
        }
        if (url == null) {
            // 恢复上次看的台（电视/广播都算）
            url = LastChannel.url(this);
        }
        url = sanitizeUrl(url);
        android.util.Log.i("LizhiGecko", "start url=" + url
                + " lastPk=" + LastChannel.pk(this) + " lastTv=" + LastChannel.isTv(this));

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        geckoView = new GeckoView(this);
        applyCropFor(url);
        root.addView(geckoView, geckoParams);
        setContentView(root);

        ensureSession();
        geckoView.setSession(sSession);

        installExtensionAsync();
        GeckoHost.load(url);
        probeExtensionLater();

        // 悬浮控制层
        Intent svc = new Intent(this, TvService.class);
        svc.setAction(TvService.ACTION_START);
        try {
            startForegroundService(svc);
        } catch (Throwable e) {
            startService(svc);
        }
    }

    private void ensureSession() {
        if (sRuntime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    // 荔枝页面要无人值守自动起播
                    .autoplayDefault(GeckoRuntimeSettings.AUTOPLAY_DEFAULT_ALLOWED)
                    // ---- 关键：把 Web 层的 devicePixelRatio 抬到 2.5625 ----
                    //
                    // 央视 /m/ 页的播放器宽度是页面自己算死的：
                    //     var _videoW = (clientWidth > 750) ? 750 : clientWidth;
                    // 默认 dpr=1.5 时 clientWidth=1280，于是播放器恒为 750x421 CSS
                    // = 1125x633 物理，在 1920x1080 上只占 58% 宽 —— 这就是"央视没全屏"。
                    //
                    // 改 dpr 不改变物理尺寸，只是改变 CSS px 的"汇率"：
                    //     dpr 2.5625 → clientWidth = 1920/2.5625 = 749.3 CSS
                    //     → 749.3 不 > 750，_videoW 取 749.3
                    //     → 播放器 749.3 x 421.5 CSS = 1920 x 1080 物理，正好铺满整屏。
                    //
                    // 移动版页面基本都用 rem（html font-size = clientWidth/10）排版，
                    // 等比缩放下物理像素尺寸不变，所以 gdtv 那边的裁切比例也不用改。
                    .displayDpiOverride(410)        // 410/160 = 2.5625
                    .displayDensityOverride(2.5625f)
                    .remoteDebuggingEnabled(true)
                    .build();
            sRuntime = GeckoRuntime.create(getApplicationContext(), settings);
            sRuntime.getSettings().setConsoleOutputEnabled(true);
            Log.i(TAG, "GeckoRuntime created dpi=" + settings.getDisplayDpiOverride()
                    + " density=" + settings.getDisplayDensityOverride());
        }
        if (sSession == null) {
            sSession = new GeckoSession();
            sSession.setContentDelegate(new GeckoSession.ContentDelegate() {
                @Override
                public void onTitleChange(GeckoSession session, String title) {
                    Log.i(TAG, "title " + title);
                }

                @Override
                public void onFullScreen(GeckoSession session, boolean fullScreen) {
                    Log.i(TAG, "onFullScreen " + fullScreen);
                    // 页面自己请求全屏（HTML5 Fullscreen API）时让 GeckoView 铺满整屏
                    if (fullScreen) {
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                        if (geckoParams != null) {
                            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                            geckoParams.width = dm.widthPixels;
                            geckoParams.height = dm.heightPixels;
                            geckoParams.topMargin = 0;
                            if (geckoView != null) geckoView.setLayoutParams(geckoParams);
                        }
                    }
                }

                @Override
                public void onFirstComposite(GeckoSession session) {
                    GeckoHost.fireFirstComposite();
                }

                @Override
                public void onCrash(GeckoSession session) {
                    Log.e(TAG, "content process crashed");
                }
            });
            sSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
                @Override
                public void onLocationChange(GeckoSession session, String url) {
                    Log.i(TAG, "location " + url);
                    if (url != null) applyCropFor(url);
                    GeckoHost.fireLocationChange(url);
                }
            });
            sSession.setProgressDelegate(new GeckoSession.ProgressDelegate() {
                @Override
                public void onPageStart(GeckoSession session, String url) {
                    GeckoHost.firePageStart(url);
                }

                @Override
                public void onPageStop(GeckoSession session, boolean success) {
                    Log.i(TAG, "onPageStop success=" + success);
                    GeckoHost.firePageStop(GeckoHost.currentUrl(), success);
                    if (success) maybeAutoCrop(GeckoHost.currentUrl());
                }

                @Override
                public void onProgressChange(GeckoSession session, int progress) {
                    if (progress == 100) {
                        Log.i(TAG, "progress 100");
                    }
                }
            });
            sSession.open(sRuntime);
            GeckoHost.bind(sRuntime, sSession);
            Log.i(TAG, "GeckoSession opened");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = intent == null ? null : intent.getStringExtra(EXTRA_URL);
        if (url != null) {
            GeckoHost.load(url);
        }
    }

    private static final String EXT_ID = "lizhi@newbox";
    private static final String EXT_LOC = "resource://android/assets/ext/lizhi/";
    private static boolean sExtInstalled;

    /**
     * 装 WebExtension（content script 负责兜底点播放 / 后续隐藏页面元素）。
     * 注意：GV69 的 registerWebExtension 在这台盒子上不会回调，所以绝不能把
     * 页面加载挂在它的回调里 —— 加载必须独立进行。
     */
    private void installExtensionAsync() {
        if (sExtInstalled || sRuntime == null) {
            return;
        }
        WebExtension ext = new WebExtension(EXT_ID, EXT_LOC,
                WebExtension.Flags.ALLOW_CONTENT_MESSAGING);
        Log.i(TAG, "registerWebExtension " + EXT_LOC);
        sRuntime.registerWebExtension(ext).then(new GeckoResult.OnValueListener<Void, Void>() {
            @Override
            public GeckoResult<Void> onValue(Void value) {
                sExtInstalled = true;
                Log.i(TAG, "web extension installed");
                return null;
            }
        }, new GeckoResult.OnExceptionListener<Void>() {
            @Override
            public GeckoResult<Void> onException(Throwable e) {
                Log.e(TAG, "web extension install fail " + e);
                return null;
            }
        });
    }

    private void probeExtensionLater() {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        Log.i(TAG, "ext probe installed=" + sExtInstalled);
                    }
                }, 6000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 只有我们前台时才允许悬浮层抢焦点接管遥控器
        TvService.setAppVisible(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sSession != null) {
            // 保持页面（音频/视频）继续，不让 Gecko 认为进了后台
            sSession.setActive(true);
        }
        // 退到桌面/别的 App 时把按键还回系统，否则桌面按 OK 也会弹我们的列表
        TvService.setAppVisible(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sInstance == this) sInstance = null;
        if (geckoView != null) {
            geckoView.setSession(null);
        }
    }
}
