package com.newbox.lizhigecko;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

/** 前台服务：常驻悬浮控制层，管理 Firefox 播放 */
public class TvService extends Service {
    private static final String TAG = "LizhiGecko";
    public static final String ACTION_START = "com.newbox.lizhigecko.START";
    public static final String ACTION_STOP = "com.newbox.lizhigecko.STOP";
    private static final String FIREFOX = "org.mozilla.firefox";
    private static final int NOTIFY_ID = 2026;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private OverlayView overlay;
    private ChannelRepo repo;
    private boolean firefoxInstalled = false;
    /** 页面最后一次动静之后，再等这么久才撤加载遮罩（保底，正常由"音频起来"提前撤） */
    private static final long STABLE_WINDOW = 12000L;
    /** 换台后最多等这么久就开始找播放信号 */
    private static final long PLAYBACK_WATCH_MS = 26000L;

    private long tuneAt;
    private int playHits;
    private boolean playbackSeen;
    private EvdevKeyWatcher evdevWatcher;
    private Thread evdevThread;
    private final android.content.BroadcastReceiver keyReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (KeyService.ACTION_KEY.equals(intent.getAction()) && overlay != null) {
                int code = intent.getIntExtra(KeyService.EXTRA_CODE, -1);
                Log.i(TAG, "key from a11y " + code);
                if (!dupKey(code)) overlay.onKey(code);
            }
        }
    };

    // 无障碍和 root evdev 是两条独立的路，同一个物理按键有可能两路都收到。
    // 那样列表里按一下下键会跳两格、按 OK 会开两次，所以同键 120ms 内只认第一次。
    private long lastKeyAt;
    private int lastKeyCode = -1;

    private synchronized boolean dupKey(int code) {
        long now = android.os.SystemClock.uptimeMillis();
        if (code == lastKeyCode && now - lastKeyAt < 120) {
            Log.i(TAG, "drop dup key " + code);
            return true;
        }
        lastKeyCode = code;
        lastKeyAt = now;
        return false;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "TvService onCreate");
        handler = new android.os.Handler(android.os.Looper.getMainLooper());
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        repo = new ChannelRepo(this);
        // 换台加载遮罩。
        // 页面事件（重定向 / 文档加载完）只负责把保底倒计时往后推，**不再靠它撤遮罩** ——
        // 荔枝首页 www → m 会整页重定向，而且文档加载完之后视频还要起播、还要重新布局，
        // 结果就是"遮罩撤了才看到网页跳变"。
        // 真正撤遮罩的信号是"播放器真的开始出声"（AudioManager.isMusicActive），
        // 见 startPlaybackWatch()。
        GeckoHost.setPageListener(new GeckoHost.PageListener() {
            @Override
            public void onPageStart(String url) {
                if (!playbackSeen && overlay != null) overlay.touchLoading(STABLE_WINDOW);
            }

            @Override
            public void onLocationChange(String url) {
                if (!playbackSeen && overlay != null) overlay.touchLoading(STABLE_WINDOW);
            }

            @Override
            public void onPageStop(String url, boolean success) {
                if (!playbackSeen && overlay != null) overlay.touchLoading(STABLE_WINDOW);
            }

            @Override
            public void onFirstComposite() {
                // 首帧合成不算"稳定"，什么都不做
            }
        });
        try {
            getPackageManager().getPackageInfo(FIREFOX, 0);
            firefoxInstalled = true;
        } catch (Exception e) {
            firefoxInstalled = false;
        }
        try {
            android.content.IntentFilter f = new android.content.IntentFilter(KeyService.ACTION_KEY);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(keyReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(keyReceiver, f);
            }
        } catch (Exception e) {
            Log.w(TAG, "register keyReceiver " + e);
        }
        // 双保险：root 直读 evdev 事件流（无障碍收不到时的兜底）
        startEvdevWatcher();
    }

    private void startEvdevWatcher() {
        try {
            evdevWatcher = new EvdevKeyWatcher(new EvdevKeyWatcher.Listener() {
                @Override
                public void onKey(final int code) {
                    Log.i(TAG, "key from evdev " + code);
                    if (dupKey(code)) return;
                    if (overlay != null) {
                        overlay.post(new Runnable() {
                            @Override
                            public void run() {
                                if (overlay != null) overlay.onKey(code);
                            }
                        });
                    }
                }
            });
            evdevThread = new Thread(evdevWatcher, "evdev-watcher");
            evdevThread.setDaemon(true);
            evdevThread.start();
            Log.i(TAG, "evdev watcher started");
        } catch (Throwable e) {
            Log.w(TAG, "evdev start fail " + e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFY_ID, buildNotification());
        boolean fresh = (overlay == null);
        if (fresh) {
            installOverlay();
        }
        // 首次启动进电视第一个频道。开机自启时没有人拉起 Activity，得由这里负责；
        // 从桌面点进来的话 Activity 正在加载页面，这里只同步 UI + 把加载遮罩亮起来，
        // 否则冷启动那几秒能清楚看到网页从上往下渲染、重定向。
        if (fresh && overlay != null) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (GeckoHost.isReady()) {
                        // 恢复上次看的台（用户要求：一开启就回到上次那个频道）。
                        // MainActivity 已经按同一份记录加载了页面，这里只是把
                        // 悬浮层的频道号 / 模式同步过来，不会二次加载。
                        boolean tv = LastChannel.isTv(TvService.this);
                        int idx = indexOf(LastChannel.pk(TvService.this), tv);
                        if (idx < 0) { tv = true; idx = 0; }
                        overlay.setChannel(idx, tv);
                        if (tv) overlay.showOsd(); else overlay.showRadio();
                        if (!GeckoHost.isPageLoaded()) {
                            java.util.List<ChannelRepo.Ch> l = repo.list(tv);
                            String name = (l.isEmpty() || idx >= l.size())
                                    ? "直播" : l.get(idx).name;
                            overlay.showLoading(idx + 1, name);
                            startPlaybackWatch();
                        }
                    } else {
                        // Gecko 还没就绪（500ms 时 Activity 常常还在建）：
                        // 以前这里写死 tune(0,true)，直接把用户上次看的台顶成第 1 个，
                        // 恢复记忆就白做了。改成同样按记录来。
                        boolean tv2 = LastChannel.isTv(TvService.this);
                        int i2 = indexOf(LastChannel.pk(TvService.this), tv2);
                        if (i2 < 0) { tv2 = true; i2 = 0; }
                        tune(i2, tv2);
                    }
                }
            }, 500);
        }
        return START_STICKY;
    }

    private void installOverlay() {
        try {
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                            WindowManager.LayoutParams.TYPE_PHONE,
                    BASE_FLAGS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED;

            overlay = new OverlayView(this, repo, new OverlayView.Callback() {
                @Override
                public void onTune(ChannelRepo.Ch ch) {
                    play(ch);
                }

                @Override
                public void onListVisibility(boolean showing) {
                    // 按键由无障碍服务和 root evdev 两路转发，不依赖窗口焦点
                }

                @Override
                public void onOpenUrl(String url) {
                    openUrl(url);
                }

                @Override
                public void onExit() {
                    goHome();
                }

                @Override
                public void onRefresh() {
                    refreshChannels();
                }

                @Override
                public void onQuit() {
                    quit();
                }
            });
            wm.addView(overlay, lp);
            INSTANCE = this;
            Log.i(TAG, "overlay installed");
        } catch (Throwable e) {
            Log.e(TAG, "overlay install fail " + e);
            Toast.makeText(this, "悬浮层启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ==================== 前台/后台：按键归属 ====================
    // 悬浮层必须 focusable 才能拿到遥控器按键（这是 v2.0 打通按键的原理），
    // 但常驻 focusable 会连桌面上的 OK 键一起劫持。所以由 Activity 的生命周期驱动：
    // 我们的 App 在前台 → 可聚焦；退到桌面/别处 → 立刻 FLAG_NOT_FOCUSABLE 把按键还回去。

    private static final int BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN;

    private static volatile TvService INSTANCE;

    public static void setAppVisible(final boolean visible) {
        final TvService s = INSTANCE;
        if (s == null) return;
        s.handler.post(new Runnable() {
            @Override
            public void run() {
                s.applyInteractive(visible);
            }
        });
    }

    private android.os.Handler handler;

    private void applyInteractive(boolean interactive) {
        if (lp == null || overlay == null || wm == null) return;
        lp.flags = interactive
                ? BASE_FLAGS
                : BASE_FLAGS | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            wm.updateViewLayout(overlay, lp);
            Log.i(TAG, "overlay interactive=" + interactive);
        } catch (Throwable e) {
            Log.w(TAG, "updateViewLayout fail " + e);
        }
        if (!interactive) {
            // 退到后台时收起所有面板，避免回到前台看到残留
            if (overlay != null) overlay.applyMode(OverlayView.Mode.HIDDEN);
        } else {
            // 回到前台：如果后台听的是广播，把广播界面恢复出来
            if (overlay != null) overlay.restoreAfterForeground();
        }
    }

    /**
     * 换台。电视和广播都走 GeckoView 的频道页 —— 只有 NSS 栈才拿得到播放地址。
     *
     * 广播为什么不再用原生 MediaPlayer：
     * channels.json 里那份 rlizhi.gdtv.cn/audio/*.m3u8?auth_key=… 是**会过期**的
     * （第一段就是时间戳，我们这份昨天 00:36 就死了，CDN 直接 403）。
     * 要拿新地址只能打 gdtv-api，而它要求一个由页面 JS 现算的 node 签名，
     * 离线无法生成 —— 所以只能让页面自己去拿。界面上仍然是我们的广播界面，
     * GeckoView 被藏起来，用户看不到网页。
     */
    private void play(ChannelRepo.Ch ch) {
        if (ch == null || overlay == null) return;
        // 每次换台都记下来，下次开机/进 App 直接回到这个台
        LastChannel.save(this, ch);
        overlay.showLoading(channelNo(ch), ch.name);
        startPlaybackWatch();

        // 原生可播的片子（节目库 MP4 / 广播回放 HLS）不走 GeckoView
        if (!ch.isWeb()) {
            playNative(ch);
            return;
        }

        String url = ch.pageUrl();
        Log.i(TAG, "play " + ch.kind + " " + ch.name + " url=" + url);

        // 注意：这里**不动** GeckoView 的可见性。广播时页面还在播音频，
        // 把 View 设成 INVISIBLE 会销毁内部 SurfaceView，有让媒体停掉的风险；
        // 而悬浮层的广播界面本身是不透明全屏，天然就盖住了网页。
        if (url.equals(GeckoHost.currentUrl())) {
            // 地址一样：Activity 刚开、页面还在加载（冷启动就是这条路径），
            // 这时候千万不能收遮罩 —— 一收就看到网页从头渲染、www 跳 m。
            // 交给 startPlaybackWatch 的"真出声"信号去撤。
            if (!GeckoHost.isPageLoaded()) {
                Log.i(TAG, "play same url, keep loading mask");
                return;
            }
            // 已经在这个频道且页面也加载完了，不重复加载，直接收遮罩
            overlay.hideLoading(0);
            return;
        }
        openUrl(url);
    }

    /**
     * 原生可播的片子（gdtv 节目库 MP4 / 广播回放 HLS）。
     *
     * 已实测于本机（2026-11-25）：
     *   - vfile2.grtn.cn 的 MP4：系统 MediaPlayer 直接播，1280x720、info 3/0（首帧渲染），正常
     *   - avod.gdtv.cn 的纯音频 HLS：内置 ExoPlayer 651ms 进 READY，音频 2ch 稳定推进
     * 但目前还没有可浏览的节目列表（gdtv-api 要页面 JS 现算的签名），
     * 所以这条分支还没有入口。先保证不会把旧路径搞坏：退回网页播放。
     */
    private void playNative(ChannelRepo.Ch ch) {
        Log.i(TAG, "playNative " + ch.kind + " " + ch.name + " url=" + ch.url + "（暂无入口，退回网页）");
        openUrl(ch.pageUrl());
    }

    /** 频道号（1 起），只用于加载页上那个大数字 */
    private int channelNo(ChannelRepo.Ch ch) {
        if (ch == null) return 0;
        java.util.List<ChannelRepo.Ch> l = repo.list(ch.tv);
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).pk == ch.pk) return i + 1;
        }
        return 0;
    }

    /** pk 在这张表里的下标，找不到返回 -1 */
    private int indexOf(int pk, boolean tv) {
        java.util.List<ChannelRepo.Ch> l = repo.list(tv);
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).pk == pk) return i;
        }
        return -1;
    }

    /**
     * 菜单里的"刷新频道"：重新读一遍内置频道表，再把当前频道页重新加载。
     *
     * 说明：频道表 / 节目单是打包在 APK 里的静态数据（来自 gdtv-api，
     * 那份接口要页面 JS 现算的 node 签名，离线拿不到），所以这里的"刷新"
     * 是"重读 + 重开当前频道"，能解决节目单卡住、画面黑掉这类问题。
     */
    private void refreshChannels() {
        try {
            repo.reload();
        } catch (Throwable e) {
            Log.w(TAG, "repo reload fail " + e);
        }
        final ChannelRepo.Ch c = overlay == null ? null : overlay.currentChannel();
        if (c == null) {
            if (overlay != null) overlay.showOsd();
            Toast.makeText(this, "频道数据已刷新", Toast.LENGTH_SHORT).show();
            return;
        }
        // 先回 about:blank 再重开 —— 直接 reload 同一个地址时，m 站那个播放器
        // 经常卡在"正在读取栏目…"，必须给它一份全新的文档。
        GeckoHost.load("about:blank");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                play(c);
            }
        }, 400);
        Toast.makeText(this, "已刷新，正在重新加载", Toast.LENGTH_SHORT).show();
        Log.i(TAG, "refreshChannels " + c.name);
    }

    /** 菜单里的"退出应用"：收悬浮层 -> 退 Activity -> 杀进程 */
    private void quit() {
        Log.i(TAG, "quit");
        GeckoHost.load("about:blank");
        try {
            if (MainActivity.sInstance != null) MainActivity.sInstance.finish();
        } catch (Throwable e) {
            Log.w(TAG, "finish fail " + e);
        }
        stopSelf();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, 350);
    }


    /** 回桌面：广播继续在后台播，回到 App 会恢复广播界面 */
    private void goHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
            Log.i(TAG, "goHome");
        } catch (Throwable e) {
            Log.w(TAG, "goHome fail " + e);
        }
    }

    // ==================== 播放信号监视 ====================
    // GeckoView 69 拿不到"视频/音频真的开始播"的事件，但 AudioManager.isMusicActive()
    // 能反映"系统里有没有正在出声的音乐流"。页面还在加载 / 播放器还没起来时它是 false，
    // 一旦起播就是 true —— 这才是撤加载遮罩的正确时机。
    // 判定要连续命中 2 次（约 700ms），避免换台瞬间旧流的尾巴造成误判。

    private void startPlaybackWatch() {
        playbackSeen = false;
        playHits = 0;
        tuneAt = android.os.SystemClock.elapsedRealtime();
        handler.removeCallbacks(pollPlaying);
        handler.postDelayed(pollPlaying, 700);
    }

    private boolean isMusicActive() {
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            return am != null && am.isMusicActive();
        } catch (Throwable e) {
            return false;
        }
    }

    private final Runnable pollPlaying = new Runnable() {
        @Override
        public void run() {
            if (playbackSeen) return;
            if (overlay == null || !overlay.isLoading()) return;
            // 文档没加载完之前不判定，那时旧流的尾巴可能还在响
            if (!GeckoHost.isPageLoaded()) {
                reschedule();
                return;
            }
            playHits = isMusicActive() ? playHits + 1 : 0;
            if (playHits >= 2) {
                playbackSeen = true;
                Log.i(TAG, "playback detected, reveal");
                // 出声之后画面还要再布局一下（播放器初始化、海报换成视频帧），
                // 多等 1.2s 再淡出，避免"遮罩一撤网页还在动"。
                overlay.hideLoading(1200);
                return;
            }
            reschedule();
        }

        private void reschedule() {
            if (android.os.SystemClock.elapsedRealtime() - tuneAt < PLAYBACK_WATCH_MS) {
                handler.postDelayed(pollPlaying, 350);
            }
        }
    };

    /** 在进程内 GeckoView 里打开任意网页（节目表 / 节目库） */
    private void openUrl(String url) {
        Log.i(TAG, "openUrl " + url);
        if (GeckoHost.load(url)) {
            return;
        }
        // Gecko 还没起来（开机自启 / 进程被回收）：把地址交给 Activity 带上
        try {
            Intent it = new Intent(this, MainActivity.class);
            it.putExtra(MainActivity.EXTRA_URL, url);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(it);
        } catch (Throwable e) {
            Log.w(TAG, "openUrl fail " + e);
        }
    }

    private void tune(int idx, boolean tv) {
        if (overlay == null) return;
        overlay.setChannel(idx, tv);
        overlay.showOsd();
        java.util.List<ChannelRepo.Ch> list = repo.list(tv);
        if (!list.isEmpty()) play(list.get(idx));
    }

    private Notification buildNotification() {
        Intent it = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel("lizhi", "荔枝直播",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("后台播放控制");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, "lizhi");
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("荔枝直播")
                .setContentText("遥控器 ▲▼ 换台 · OK 频道列表")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        if (INSTANCE == this) INSTANCE = null;
        GeckoHost.setPageListener(null);
        if (overlay != null && wm != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) {}
        }
        try { unregisterReceiver(keyReceiver); } catch (Exception ignored) {}
        if (evdevWatcher != null) evdevWatcher.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
