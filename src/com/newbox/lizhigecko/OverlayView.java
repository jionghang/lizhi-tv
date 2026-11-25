package com.newbox.lizhigecko;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 悬浮控制层：换台 OSD / 左侧栏频道列表 / 广播界面 / 换台加载遮罩。
 *
 * 交互照老版 lizhilive 的做法：OK 弹出左侧栏（传统电视习惯），
 * ↑↓ 选台、←→ 切电视/广播、OK 播放、返回收起。
 */
class OverlayView extends FrameLayout {

    interface Callback {
        void onTune(ChannelRepo.Ch ch);

        void onListVisibility(boolean showing);

        void onOpenUrl(String url);   // 打开节目表 / 节目库等网页

        void onExit();                // 回到桌面（广播继续在后台播）

        void onRefresh();             // 刷新频道数据 + 重开当前频道

        void onQuit();                // 退出整个 App
    }

    enum Mode { HIDDEN, OSD, LIST, RADIO, MENU }

    // ==================== 配色 ====================
    // 全站只用一套黄色主色（就是频道号那个黄）。
    // 以前电视红 + 广播绿两套，同一屏里出现三种颜色太乱，用户要求统一。
    private static final int PRIMARY = 0xFFFFD54F;      // 主色：暖黄
    private static final int PRIMARY_SOFT = 0x26FFD54F; // 主色 15% 透明（选中底）
    private static final int PRIMARY_DEEP = 0xFFFFC107; // 深一档，用于进度/强调

    // 旧名保留，全部指向主色，免得散落的引用漏改
    private static final int ACCENT = PRIMARY;
    private static final int ACCENT_SOFT = PRIMARY_SOFT;
    private static final int GREEN = PRIMARY;
    private static final int GREEN_SOFT = PRIMARY_SOFT;

    private static final int TEXT = 0xFFF3F6FA;
    private static final int SUB = 0x66F3F6FA;
    private static final int PANEL_BG = 0xC810151D;     // 毛玻璃层之上的压暗色

    private static final int C_YELLOW = PRIMARY;
    private static final int C_TEAL = PRIMARY;
    private static final int C_WHITE = 0xFFFFFFFF;
    private static final int C_DIM = 0xFFB0BEC5;

    private final Callback cb;
    private final ChannelRepo repo;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Mode mode = Mode.HIDDEN;

    /** 当前正在播的是电视(true)还是广播(false) */
    private boolean isTv = true;
    /**
     * 列表当前停在哪一栏。必须和 isTv 分开！
     * 以前切 Tab 直接改 isTv，退出列表后不复位 —— 于是"看电视时打开列表看了一眼
     * 广播，关掉再按上下键"就变成换广播台了（用户反馈的 bug）。
     */
    private boolean listTv = true;
    private boolean radioPlaying = false;
    private int tvIdx = 0;      // 电视当前播放位置
    private int radioIdx = 0;   // 广播当前播放位置
    private int selIdx = 0;     // 列表高亮位置

    // ---- OSD ----
    private View osdRoot;
    private TextView osdNo, osdName, osdCur, osdNext, osdTime, osdTag;

    // ---- 侧栏频道列表 ----
    private FrameLayout listRoot;
    private ScrollView listScroll;
    private LinearLayout listRows;
    private TextView listTabTv, listTabRadio, listHint;
    private final List<ChRow> rows = new ArrayList<ChRow>();

    // ---- 广播界面 ----
    private FrameLayout radioRoot;
    private WaveDrawView radioPanel;
    private final ValueAnimator waveAnim;

    // ---- 加载遮罩 ----
    private FrameLayout loadingRoot;
    private LoadingRing loadingRing;
    private TextView loadingText, loadingNo;

    // ---- 菜单 ----
    private FrameLayout menuRoot;
    private final List<TextView> menuItems = new ArrayList<TextView>();
    private int menuSel = 0;
    private Mode menuBack = Mode.HIDDEN;
    private static final String[] MENU_TEXTS = {"刷新频道", "退出应用"};

    // ---- 数字键输入 ----
    private FrameLayout numRoot;
    private TextView numText;
    private final StringBuilder numBuf = new StringBuilder();

    OverlayView(Context ctx, ChannelRepo repo, Callback cb) {
        super(ctx);
        this.repo = repo;
        this.cb = cb;
        setClickable(true);
        setFocusable(true);

        buildBottomGuard();
        buildOsd();
        buildList();
        buildRadio();
        buildLoading();
        buildNumPad();
        buildMenu();

        waveAnim = ValueAnimator.ofFloat(0f, 1f);
        waveAnim.setDuration(1200);
        waveAnim.setRepeatCount(ValueAnimator.INFINITE);
        waveAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                if (radioRoot != null && radioRoot.getVisibility() == VISIBLE) radioPanel.invalidate();
            }
        });

        // z-order（从下往上）：底部黑条 < OSD < 广播界面 < 加载遮罩 < 频道列表 < 菜单 < 数字键
        // 加载遮罩要压在网页上（防止看到网页渲染），但必须让列表/菜单盖住它，
        // 否则换台那 10 秒里按 OK 什么都看不到（用户反馈）。
        loadingRoot.bringToFront();
        listRoot.bringToFront();
        menuRoot.bringToFront();
        numRoot.bringToFront();

        applyMode(Mode.HIDDEN);
    }

    private int dp(float v) {
        return (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()) + 0.5f);
    }

    // ==================== 毛玻璃 ====================
    // 画面后面那一帧被缩小 + 模糊，当作所有面板的底。
    // 面板打开时才抓一次（视频一直在动，实时模糊不现实），
    // 抓不到就退回纯深色底，不影响功能。
    private Bitmap frost;
    private boolean frostBusy;
    /** 模糊底图的平均亮度（0-255）：白底页面要压得更狠，视频才能透 */
    private int frostLuma = 128;
    private final Paint frostBmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** 模糊底图自身的透明度：0xCC ≈ 80%，剩下 20% 让真实电视画面透上来 */
    private int frostBmpAlpha = 0xCC;
    private final Paint frostScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frostStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path frostPath = new Path();
    private final RectF frostRect = new RectF();
    private final android.graphics.Rect frostSrc = new android.graphics.Rect();

    // ---- 液态玻璃（Liquid Glass）边缘 ----
    private final Paint glassEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassGlossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path glassTmpPath = new Path();
    private final RectF glassTmpRect = new RectF();
    /** 顶上一道柔光的高度（dp），0 表示关掉 */
    private float glossDp = 0f;
    /** 边缘描边总开关。用户嫌"像古早拟物风"，默认关掉——现在是纯净玻璃，没有描边 */
    private boolean glassEdgeOn = false;

    /**
     * 面板打开时刷新毛玻璃底图。
     *
     * 用户嫌"打开瞬间一张定格"太死，又嫌每秒一抓太费 —— 折中：
     * 只在前 1.5 秒抓 3 帧（0 / 500 / 1050ms），之后彻底停手。
     * 开销是 3 次 240x135 的截屏 + 盒式模糊，比一帧动画还轻。
     */
    void refreshFrost() {
        h.removeCallbacks(frostShot);
        captureFrostOnce();
        h.postDelayed(frostShot, 500);
        h.postDelayed(frostShot, 1050);
    }

    private final Runnable frostShot = new Runnable() {
        @Override
        public void run() {
            captureFrostOnce();
        }
    };

    /** 抓一帧；面板已经收起就不抓了 */
    private void captureFrostOnce() {
        if (frostBusy) return;
        if (mode == Mode.HIDDEN) return;
        final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        // 分辨率给足一点：以前 /12 是 160x90，放大到侧栏大小后
        // 全是大色块，看着像糊墙。 /8 = 240x135，开销仍然很低。
        final int w = Math.max(24, dm.widthPixels / 8);
        final int h = Math.max(24, dm.heightPixels / 8);
        frostBusy = true;
        Blur.capture(w, h, new Blur.Cb() {
            @Override
            public void onBitmap(Bitmap b) {
                frostBusy = false;
                frost = b;
                if (b != null && !b.isRecycled()) frostLuma = lumaOf(b);
                invalidateFrostUsers();
            }
        });
    }

    /** 抽稀采样算平均亮度，14k 像素里每 7 个取一个就够 */
    private static int lumaOf(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        try {
            b.getPixels(px, 0, w, 0, 0, w, h);
        } catch (Throwable e) {
            return 128;
        }
        long sum = 0;
        int n = 0;
        for (int i = 0; i < px.length; i += 7) {
            int c = px[i];
            sum += (int) (0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF)
                    + 0.114 * (c & 0xFF));
            n++;
        }
        return n == 0 ? 128 : (int) (sum / n);
    }

    private void invalidateFrostUsers() {
        if (osdRoot != null) osdRoot.invalidate();
        if (radioPanel != null) radioPanel.invalidate();
        if (listRoot != null) listRoot.invalidate();
        if (menuRoot != null) menuRoot.invalidate();
        if (numRoot != null) numRoot.invalidate();
        invalidate();
    }

    /**
     * 在指定矩形里画液态玻璃：模糊底 + 压暗 + 边缘受光描边 + 顶部柔光。
     *
     * @param radius 圆角，0 表示直角
     * @param scrim  压暗色，一般 0x36~0x5A 的深色
     */
    void drawFrost(Canvas c, RectF r, float radius, int scrim) {
        frostRect.set(r);
        frostPath.reset();
        if (radius > 0) {
            frostPath.addRoundRect(frostRect, radius, radius, Path.Direction.CW);
        } else {
            frostPath.addRect(frostRect, Path.Direction.CW);
        }

        // ---- 0. 外圈厚度：外扩 1.3dp 画一道深色描边，内半会被面板本体盖掉，
        //        只剩最外一圈，面板和后面画面之间就有了"浮起来"的分离感 ----
        if (glassEdgeOn) {
            float o = dp(1.3f);
            glassTmpRect.set(r);
            glassTmpRect.inset(-o, -o);
            glassTmpPath.reset();
            glassTmpPath.addRoundRect(glassTmpRect, radius > 0 ? radius + o : 0,
                    radius > 0 ? radius + o : 0, Path.Direction.CW);
            glassOuterPaint.setShader(null);
            glassOuterPaint.setStyle(Paint.Style.STROKE);
            glassOuterPaint.setStrokeWidth(o * 2f);
            glassOuterPaint.setColor(0x55000000);
            c.drawPath(glassTmpPath, glassOuterPaint);
        }

        // ---- 1. 模糊底：按"屏幕上的同一位置"取源区域 ----
        // 以前是把 16:9 的缩略图硬拉进面板矩形，侧栏那种竖长条就被横向压扁
        // （用户反馈"压扁了再模糊，显得变形"）。抓的图本来就是整屏等比缩小的，
        // 直接用面板坐标映射过去，比例天然一致，玻璃后面的内容位置也对得上。
        int save = c.save();
        c.clipPath(frostPath);
        if (frost != null && !frost.isRecycled()) {
            // 模糊底图本身也带透明度：底下那层是真实的电视画面，留 20% 透上来。
            mapSrc(frost, frostRect, frostSrc);
            frostBmpPaint.setAlpha(frostBmpAlpha);
            c.drawBitmap(frost, frostSrc, frostRect, frostBmpPaint);
        } else {
            c.drawColor(0xFF0E131A);
        }
        c.restoreToCount(save);

        // ---- 2. 自适应压暗 ----
        // 底图越亮（网页白底）压得越狠，是视频就让它透出来。
        int baseA = (scrim >>> 24) & 0xFF;
        int extra = (int) (frostLuma * 0.30f);
        int a = Math.min(0xA8, baseA + extra);
        frostScrimPaint.setStyle(Paint.Style.FILL);
        frostScrimPaint.setShader(null);
        frostScrimPaint.setColor((a << 24) | (scrim & 0x00FFFFFF));
        c.drawPath(frostPath, frostScrimPaint);

        if (!glassEdgeOn) return;

        // ---- 3. 主描边：上亮下暗，模拟玻璃切面的受光——液态玻璃最标志的那圈 ----
        glassEdgePaint.setShader(new LinearGradient(
                r.left, r.top, r.left, r.bottom,
                new int[]{0xC0FFFFFF, 0x4DFFFFFF, 0x14FFFFFF, 0x52FFFFFF},
                new float[]{0f, 0.26f, 0.74f, 1f},
                Shader.TileMode.CLAMP));
        glassEdgePaint.setStyle(Paint.Style.STROKE);
        glassEdgePaint.setStrokeWidth(dp(1.3f));
        c.drawPath(frostPath, glassEdgePaint);

        // ---- 4. 内缩一圈的极淡细线：玻璃的厚度 ----
        float in = dp(1.6f);
        glassTmpRect.set(r);
        glassTmpRect.inset(in, in);
        if (glassTmpRect.width() > 2 && glassTmpRect.height() > 2) {
            float ir = radius > in ? radius - in : 0;
            glassTmpPath.reset();
            glassTmpPath.addRoundRect(glassTmpRect, ir, ir, Path.Direction.CW);
            glassInnerPaint.setShader(null);
            glassInnerPaint.setStyle(Paint.Style.STROKE);
            glassInnerPaint.setStrokeWidth(dp(0.8f));
            glassInnerPaint.setColor(0x1FFFFFFF);
            c.drawPath(glassTmpPath, glassInnerPaint);
        }

        // ---- 5. 顶部柔光：上方光源打在玻璃上的一道反光 ----
        if (glossDp > 0 && r.height() > dp(glossDp)) {
            int s2 = c.save();
            c.clipPath(frostPath);
            float gh = dp(glossDp);
            glassGlossPaint.setShader(new LinearGradient(
                    r.left, r.top, r.left, r.top + gh,
                    new int[]{0x30FFFFFF, 0x0CFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            glassGlossPaint.setStyle(Paint.Style.FILL);
            c.drawRect(r.left, r.top, r.right, r.top + gh, glassGlossPaint);
            c.restoreToCount(s2);
        }
    }

    /** 把面板矩形映射到缩略图里的同一块区域（两者都是同比例的全屏图） */
    private void mapSrc(Bitmap b, RectF dst, android.graphics.Rect out) {
        int vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            vw = Math.max(1, dm.widthPixels);
            vh = Math.max(1, dm.heightPixels);
        }
        float sx = b.getWidth() / (float) vw;
        float sy = b.getHeight() / (float) vh;
        int bw = b.getWidth(), bh = b.getHeight();
        int l = Math.max(0, Math.min(bw - 1, Math.round(dst.left * sx)));
        int t = Math.max(0, Math.min(bh - 1, Math.round(dst.top * sy)));
        int rr = Math.max(l + 1, Math.min(bw, Math.round(dst.right * sx)));
        int bb = Math.max(t + 1, Math.min(bh, Math.round(dst.bottom * sy)));
        out.set(l, t, rr, bb);
    }

    /** 把整块 View 画成毛玻璃（左侧栏面板、广播界面用它） */
    private class FrostDrawable extends android.graphics.drawable.Drawable {
        private final int scrim;
        private final int radiusDp;
        /** 挂在这块 View 上之后才知道自己在屏幕的哪个位置，玻璃后面的内容才对得上 */
        private View host;
        private final int[] loc = new int[2];

        FrostDrawable(int scrim, int radiusDp) {
            this.scrim = scrim;
            this.radiusDp = radiusDp;
        }

        FrostDrawable attach(View v) {
            host = v;
            return this;
        }

        @Override
        public void draw(Canvas c) {
            RectF r = new RectF(getBounds());
            int dx = 0, dy = 0;
            if (host != null) {
                host.getLocationInWindow(loc);
                dx = loc[0];
                dy = loc[1];
                r.offset(dx, dy);
            }
            // canvas 此时已经 translate 到 View 原点，先抵消，再用屏幕坐标画，
            // 这样 drawFrost 里按屏幕位置取样就是准的。
            int save = c.save();
            c.translate(-dx, -dy);
            drawFrost(c, r, dp(radiusDp), scrim);
            c.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) { }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) { }

        @Override
        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private TextView textView(float sp, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    // ==================== 底部黑条 ====================
    /**
     * 盖住画面最底那 1~2 个物理像素。
     *
     * 原因见 MainActivity.applyCropRatio：header 137 CSS 落在半个物理像素上，
     * 视频不可能同时做到"顶部不出 header"和"底部不出白"，必然在底部露一条
     * 1px 左右的页面白底。这里用一条 6px 的黑条盖上，电视上完全看不出来。
     */
    private View bottomGuard;

    private void buildBottomGuard() {
        bottomGuard = new View(getContext());
        bottomGuard.setBackgroundColor(0xFF000000);
        FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(-1, dp(6), Gravity.BOTTOM);
        addView(bottomGuard, lp);
    }

    // ==================== OSD ====================
    private void buildOsd() {
        osdRoot = new OsdDrawView(getContext());
        addView(osdRoot, new FrameLayout.LayoutParams(-1, -1));
    }

    private class OsdDrawView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF barBg = new RectF();

        OsdDrawView(Context c) {
            super(c);
            osdNo = new TextView(getContext());
            osdName = new TextView(getContext());
            osdCur = new TextView(getContext());
            osdNext = new TextView(getContext());
            osdTime = new TextView(getContext());
            osdTag = new TextView(getContext());
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight();
            if (mode != Mode.OSD || w == 0) return;

            // 顶部渐变遮罩
            p.setShader(new LinearGradient(0, 0, 0, 220, 0xCC000000, 0x00000000, Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            c.drawRect(0, 0, w, 220, p);
            p.setShader(null);

            // 左上角频道号 + 名称
            int x = 70, y = 90;
            p.setColor(isTv ? C_YELLOW : C_TEAL);
            p.setTextSize(88);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(String.format("%02d", playIdx() + 1), x, y + 60, p);

            p.setColor(C_WHITE);
            p.setTextSize(46);
            c.drawText(currentName(), x + 130, y + 48, p);

            p.setColor(C_DIM);
            p.setTextSize(20);
            p.setTypeface(Typeface.DEFAULT);
            c.drawText(isTv ? "电视 LIVE" : "广播 LIVE", x + 132, y + 82, p);

            // 底部节目信息面板（毛玻璃）
            int panelH = 190;
            int top = h - panelH - 60;
            RectF panel = new RectF(60, top, w - 60, top + panelH);
            drawFrost(c, panel, dp(20), 0x3612161D);

            String[] pr = repo.program(currentCh(), System.currentTimeMillis());
            boolean onAir = !TextUtils.isEmpty(pr[0]);
            boolean hasList = currentCh() != null && currentCh().times != null
                    && currentCh().times.length > 0;
            p.setColor(C_WHITE);
            p.setTextSize(30);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText(onAir ? pr[0] : (hasList ? "正在直播" : "节目单加载中…"), 100, top + 56, p);

            p.setColor(C_DIM);
            p.setTextSize(21);
            p.setTypeface(Typeface.DEFAULT);
            // 下一节目带上开始时间（pr[5]）；落在节目单空档时"当前"为空，这一行就是主信息
            String nextLine = TextUtils.isEmpty(pr[1]) ? "—"
                    : ((TextUtils.isEmpty(pr[5]) ? "" : pr[5] + "  ") + pr[1]);
            c.drawText("接下来  " + nextLine, 100, top + 96, p);

            if (onAir && !TextUtils.isEmpty(pr[3]) && !TextUtils.isEmpty(pr[4])) {
                p.setColor(C_TEAL);
                p.setTextSize(19);
                c.drawText(pr[3] + " — " + pr[4], 100, top + 132, p);
            }

            barBg.set(100, top + 152, w - 100, top + 160);
            p.setColor(0x44FFFFFF);
            c.drawRoundRect(barBg, 4, 4, p);
            int pct = 0;
            try { pct = Integer.parseInt(pr[2]); } catch (Exception ignored) {}
            if (pct > 0) {
                p.setColor(isTv ? C_YELLOW : C_TEAL);
                barBg.right = 100 + (w - 200) * pct / 100f;
                c.drawRoundRect(barBg, 4, 4, p);
            }

            p.setColor(0x88FFFFFF);
            p.setTextSize(17);
            p.setTypeface(Typeface.DEFAULT);
            c.drawText("▲▼ 换台   数字键 直达   OK 频道列表", w - 520, top + 40, p);
        }
    }

    // ==================== 侧栏频道列表 ====================
    private void buildList() {
        listRoot = new FrameLayout(getContext());

        View dim = new View(getContext());
        dim.setBackgroundColor(0x66000000);
        listRoot.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        // 毛玻璃：面板本身透明，底下铺一层模糊后的画面
        panel.setBackground(null);
        panel.setPadding(dp(40), dp(34), dp(24), dp(20));

        // 玻璃底（和 panel 同宽同高，垫在下面）
        View glass = new View(getContext());
        glass.setBackground(new FrostDrawable(0x3A0D1218, 0).attach(glass));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(dp(460), -1, Gravity.START);
        listRoot.addView(glass, gp);

        // 标题行：电视 / 广播
        LinearLayout tabs = new LinearLayout(getContext());
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        listTabTv = makeTab("电视");
        listTabRadio = makeTab("广播");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.rightMargin = dp(26);
        tabs.addView(listTabTv, tp);
        tabs.addView(listTabRadio);
        panel.addView(tabs);

        listHint = textView(13, SUB, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.topMargin = dp(16);
        panel.addView(listHint, hp);

        listScroll = new ScrollView(getContext());
        listScroll.setVerticalScrollBarEnabled(false);
        listRows = new LinearLayout(getContext());
        listRows.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(listRows, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1f);
        sp.topMargin = dp(12);
        panel.addView(listScroll, sp);

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(460), -1, Gravity.START);
        listRoot.addView(panel, pp);

        addView(listRoot, new FrameLayout.LayoutParams(-1, -1));
    }

    private TextView makeTab(String s) {
        TextView t = textView(22, SUB, true);
        t.setText(s);
        return t;
    }

    private void updateTabs() {
        listTabTv.setTextColor(listTv ? ACCENT : SUB);
        listTabRadio.setTextColor(listTv ? SUB : GREEN);
        listTabTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, listTv ? 22 : 20);
        listTabRadio.setTextSize(TypedValue.COMPLEX_UNIT_SP, listTv ? 20 : 22);
    }

    /** 重建整个列表（切分类 / 每次打开时调用，顺便刷新节目名） */
    private void rebuildRows() {
        listRows.removeAllViews();
        rows.clear();
        List<ChannelRepo.Ch> list = repo.list(listTv);
        listHint.setText(listTv
                ? "共 " + list.size() + " 个电视频道   ↑↓ 选择   ←→ 切换   OK 播放"
                : "共 " + list.size() + " 个广播频率   ↑↓ 选择   ←→ 切换   OK 收听");
        for (int i = 0; i < list.size(); i++) {
            ChRow r = new ChRow(getContext());
            r.bind(list.get(i), i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(58));
            lp.bottomMargin = dp(6);
            listRows.addView(r, lp);
            rows.add(r);
        }
        updateTabs();
        refreshRows(true);
    }

    private void refreshRows(boolean scrollToSel) {
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setSel(i == selIdx);
        }
        if (scrollToSel && selIdx >= 0 && selIdx < rows.size()) {
            final View v = rows.get(selIdx);
            listScroll.post(new Runnable() {
                @Override
                public void run() {
                    int top = v.getTop() - listScroll.getHeight() / 2 + v.getHeight() / 2;
                    listScroll.smoothScrollTo(0, Math.max(0, top));
                }
            });
        }
    }

    /** 单行频道：序号 + 名称 + 当前节目 */
    private class ChRow extends LinearLayout {
        private final TextView num, name, prog;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean sel;

        ChRow(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setWillNotDraw(false);
            setPadding(dp(18), 0, dp(16), 0);

            num = textView(16, SUB, true);
            num.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(34), -2);
            nlp.rightMargin = dp(10);
            addView(num, nlp);

            LinearLayout mid = new LinearLayout(c);
            mid.setOrientation(VERTICAL);
            mid.setGravity(Gravity.CENTER_VERTICAL);
            name = textView(19, TEXT, false);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            prog = textView(12, SUB, false);
            prog.setSingleLine(true);
            prog.setEllipsize(TextUtils.TruncateAt.END);
            mid.addView(name);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
            plp.topMargin = dp(3);
            mid.addView(prog, plp);
            addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
        }

        void bind(ChannelRepo.Ch ch, int i) {
            num.setText(String.format("%02d", i + 1));
            name.setText(ch.name);
            String[] pr = repo.program(ch, System.currentTimeMillis());
            if (!TextUtils.isEmpty(pr[0])) {
                prog.setText((TextUtils.isEmpty(pr[3]) ? "" : pr[3] + "  ") + pr[0]);
            } else {
                prog.setText(listTv ? "正在直播" : "正在播音");
            }
        }

        void setSel(boolean s) {
            sel = s;
            if (s) {
                setBackground(round(listTv ? ACCENT_SOFT : GREEN_SOFT, dp(10)));
            } else {
                setBackground(null);
            }
            num.setTextColor(s ? (listTv ? ACCENT : GREEN) : SUB);
            name.setTextColor(s ? 0xFFFFFFFF : TEXT);
            prog.setTextColor(s ? 0xB3FFFFFF : SUB);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (!sel) return;
            p.setColor(listTv ? ACCENT : GREEN);
            float w = dp(4), hh = getHeight();
            c.drawRoundRect(new RectF(dp(4), hh * 0.22f, dp(4) + w, hh * 0.78f),
                    w / 2f, w / 2f, p);
        }
    }

    // ==================== 广播界面 ====================
    private void buildRadio() {
        radioRoot = new FrameLayout(getContext());
        radioRoot.setBackgroundColor(0xFF0A0D10);
        radioPanel = new WaveDrawView(getContext());
        radioRoot.addView(radioPanel, new FrameLayout.LayoutParams(-1, -1));
        addView(radioRoot, new FrameLayout.LayoutParams(-1, -1));
    }

    private class WaveDrawView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        WaveDrawView(Context c) {
            super(c);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w == 0) return;
            float t = waveAnim != null && waveAnim.getAnimatedValue() != null
                    ? (Float) waveAnim.getAnimatedValue() : 0f;

            // 毛玻璃打底（整屏模糊画面 + 深色压暗），比纯色背景有质感
            drawFrost(canvas, new RectF(0, 0, w, h), 0, 0x4A0A0D10);

            // 中央波纹圈。cy 比原来上移 50px：下面要放"节目时间轴 + 进度条 + 下一节目"三行
            int cx = w / 2, cy = h / 2 - 110;
            for (int i = 3; i >= 0; i--) {
                float radius = 130 + i * 46 + t * 18;
                p.setColor(i == 0 ? 0x55FFD54F : 0x1FFFD54F);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(i == 0 ? 6 : 3);
                canvas.drawCircle(cx, cy, radius, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(GREEN);
            canvas.drawCircle(cx, cy, 96, p);
            p.setColor(0xFF1A1408);
            p.setTextSize(64);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);
            String label = currentCh() != null && currentCh().name.length() > 0
                    ? currentCh().name.substring(0, 1) : "FM";
            canvas.drawText(label, cx, cy + 22, p);

            // 音柱
            p.setStrokeWidth(6);
            p.setColor(GREEN);
            for (int i = 0; i < 32; i++) {
                float x = w / 2f - 400 + i * 25;
                float amp = 16 + 44 * (float) Math.abs(Math.sin(i * 0.7 + t * 6.28));
                canvas.drawLine(x, cy + 250 - amp, x, cy + 250 + amp, p);
            }

            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(TEXT);
            p.setTextSize(54);
            canvas.drawText(currentName(), w / 2f, cy + 340, p);

            /*
             * 节目单三件套：当前节目 + 它的起止时间，下面一条时间轴，再下面下一节目的
             * 开始时间与名称。数据全部来自 channels.json 里的 programs（repo.program
             * 返回 {当前, 下一, 进度%, 当前开始, 当前结束, 下一开始}），不联网。
             *
             * 正常情况下"下一开始"就是当前节目的结束时间；但 gdtv 的节目单凌晨会有
             * 空档（停播 / 购物时段），这时当前为空、下一开始要往后找，所以两处都用
             * pr[5]，别拿 pr[4] 顶。
             */
            String[] pr = repo.program(currentCh(), System.currentTimeMillis());
            boolean hasProg = pr[0] != null && pr[0].length() > 0;
            boolean hasSpan = pr[3] != null && pr[3].length() > 0 && pr[4] != null && pr[4].length() > 0;

            p.setTypeface(Typeface.DEFAULT);
            p.setTextSize(28);
            p.setColor(GREEN);
            canvas.drawText(hasProg ? pr[0] : "正在播音", w / 2f, cy + 400, p);

            if (hasSpan) {
                p.setTextSize(22);
                p.setColor(SUB);
                canvas.drawText(pr[3] + "  —  " + pr[4], w / 2f, cy + 440, p);
            }

            if (hasProg && hasSpan) {
                int pct = 0;
                try {
                    pct = Integer.parseInt(pr[2]);
                } catch (Exception ignored) {
                }
                if (pct < 0) pct = 0;
                if (pct > 100) pct = 100;

                float barW = Math.min(w * 0.56f, 760f);
                float barH = 10f;
                float left = w / 2f - barW / 2f;
                float top = cy + 464;
                p.setColor(0x33FFFFFF);
                canvas.drawRoundRect(new RectF(left, top, left + barW, top + barH),
                        barH / 2f, barH / 2f, p);
                if (pct > 0) {
                    p.setColor(GREEN);
                    canvas.drawRoundRect(new RectF(left, top, left + barW * pct / 100f, top + barH),
                            barH / 2f, barH / 2f, p);
                }
                // 末端白点：画面在动态变化，一眼能看出进度真的在走
                p.setColor(0xFFFFFFFF);
                canvas.drawCircle(left + barW * pct / 100f, top + barH / 2f, 5f, p);
            }

            if (pr[1] != null && pr[1].length() > 0) {
                p.setTextSize(24);
                p.setColor(SUB);
                // 下一节目的开始时间用 pr[5]：有空档时它和"当前结束"不是一回事
                String next = (pr[5] != null && pr[5].length() > 0 ? pr[5] + "  " : "") + pr[1];
                canvas.drawText("接下来  " + next, w / 2f, cy + 524, p);
            } else if (!hasProg && currentCh() != null && currentCh().times != null
                    && currentCh().times.length > 0) {
                // 有节目单但当前时刻落在数据范围外（表是打包进 APK 的静态快照，会过期）。
                // 说清楚比留一片空白强，否则像是界面坏了。
                p.setTextSize(22);
                p.setColor(0x66FFFFFF);
                canvas.drawText("节目单数据未覆盖当前时段", w / 2f, cy + 510, p);
            }

            p.setTextSize(20);
            p.setColor(0x88FFFFFF);
            canvas.drawText("▲▼ 换台   数字键 直达   OK 频道列表   返回 桌面", w / 2f, h - 60, p);
        }
    }

    // ==================== 换台加载遮罩 ====================
    private void buildLoading() {
        loadingRoot = new FrameLayout(getContext());
        loadingRoot.setBackgroundColor(0xFF07090D);

        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        loadingRing = new LoadingRing(getContext());
        box.addView(loadingRing, new LinearLayout.LayoutParams(dp(72), dp(72)));

        // 频道号：用户要求加载页也带上号，这样换台时一眼知道自己到了第几台
        loadingNo = textView(58, PRIMARY, true);
        loadingNo.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = dp(20);
        box.addView(loadingNo, np);

        loadingText = textView(24, TEXT, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(6);
        box.addView(loadingText, tp);

        TextView tip = textView(14, SUB, false);
        tip.setText("正在切换频道，请稍候");
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(-2, -2);
        tipLp.topMargin = dp(10);
        box.addView(tip, tipLp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        loadingRoot.addView(box, lp);
        loadingRoot.setVisibility(GONE);
        // 加在最后：永远盖在所有层之上
        addView(loadingRoot, new FrameLayout.LayoutParams(-1, -1));
    }

    private class LoadingRing extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float angle;

        LoadingRing(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(5));
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), hh = getHeight();
            float r = Math.min(w, hh) / 2f - dp(5);
            p.setColor(0x22FFFFFF);
            c.drawCircle(w / 2f, hh / 2f, r, p);
            // 电视红 / 广播绿：切广播时整条加载页都应该是绿的
            p.setColor(isTv ? ACCENT : GREEN);
            box.set(w / 2f - r, hh / 2f - r, w / 2f + r, hh / 2f + r);
            c.drawArc(box, angle, 100, false, p);
            angle = (angle + 7) % 360;
            if (getVisibility() == VISIBLE) postInvalidateOnAnimation();
        }
    }

    private final Runnable loadHide = new Runnable() {
        @Override
        public void run() {
            fadeOutLoading();
        }
    };

    private void fadeOutLoading() {
        loadingRoot.animate().cancel();
        loadingRoot.animate().alpha(0f).setDuration(350).withEndAction(new Runnable() {
            @Override
            public void run() {
                loadingRoot.setVisibility(GONE);
                loadingRoot.setAlpha(1f);
                // 遮罩盖着的时候 OSD 那 8 秒计时早就跑完了，撤遮罩后重开一轮。
                // 不这么做的话"起播后底下的信息栏"永远看不到（用户反馈的问题）。
                if (mode == Mode.OSD) {
                    // 重新抓一张毛玻璃底：换台前抓的那张还是上一页的黑屏
                    refreshFrost();
                    osdRoot.invalidate();
                    h.removeCallbacks(osdHide);
                    h.postDelayed(osdHide, 8000);
                }
            }
        }).start();
    }

    /** 盖住换台时的网页跳变。no 为 1 起的频道序号，0 表示不显示 */
    void showLoading(int no, String name) {
        android.util.Log.i("LizhiGecko", "loading on: " + no + " " + name);
        loadingNo.setText(no > 0 ? String.format("%02d", no) : "");
        loadingNo.setVisibility(no > 0 ? VISIBLE : GONE);
        loadingText.setText(TextUtils.isEmpty(name) ? "正在加载…" : name);
        loadingRoot.animate().cancel();
        loadingRoot.setAlpha(1f);
        loadingRoot.setVisibility(VISIBLE);
        // 不要再提到最上层：列表 / 菜单必须能盖住它（层级在构造里定死了）
        loadingRing.invalidate();
        // 遮罩盖着的时候先别让 OSD 计时（否则 8 秒在遮罩期间走完，
        // 遮罩一撤画面干干净净，用户永远看不到那条起播信息），
        // 等遮罩淡出时在 fadeOutLoading 里重新起表。
        h.removeCallbacks(osdHide);
        h.removeCallbacks(loadHide);
        // 兜底：25s 无论发生什么都撤掉遮罩，避免永久黑屏
        h.postDelayed(loadHide, 25000);
    }

    /**
     * 页面还在动（重定向 / 文档加载完 / 进度满），把撤遮罩的倒计时往后推。
     *
     * 荔枝首页会 www → m 整页重定向，onPageStop 会响好几次，如果只在第一次
     * pageStop 后计时，遮罩就会在整页重载之前撤掉，用户就会看到"网页跳变"。
     */
    void touchLoading(long windowMs) {
        if (loadingRoot.getVisibility() != VISIBLE) return;
        h.removeCallbacks(loadHide);
        h.postDelayed(loadHide, windowMs);
    }

    void hideLoading(long delayMs) {
        h.removeCallbacks(loadHide);
        if (delayMs <= 0) {
            fadeOutLoading();
        } else {
            h.postDelayed(loadHide, delayMs);
        }
    }

    boolean isLoading() {
        return loadingRoot.getVisibility() == VISIBLE;
    }

    // ==================== 数字键直达 ====================
    /**
     * 遥控器上直接敲频道号（比如 12），停手 1.6 秒自动跳过去。
     * 电视上"背得出台号"的人都是这么用的，比翻列表快得多。
     */
    private void buildNumPad() {
        numRoot = new FrameLayout(getContext());

        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(46), dp(24), dp(46), dp(22));
        box.setBackground(new FrostDrawable(0x440D1218, 20).attach(box));

        numText = textView(92, PRIMARY, true);
        numText.setGravity(Gravity.CENTER);
        numText.setMinWidth(dp(180));
        box.addView(numText);

        TextView tip = textView(15, SUB, false);
        tip.setText("输入频道号，停手自动切换");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(8);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(tip, lp);

        numRoot.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        numRoot.setVisibility(GONE);
        addView(numRoot, new FrameLayout.LayoutParams(-1, -1));
    }

    // ==================== 菜单（MENU 键） ====================
    private void buildMenu() {
        menuRoot = new FrameLayout(getContext());

        View dim = new View(getContext());
        dim.setBackgroundColor(0x55000000);
        menuRoot.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(22), dp(24), dp(20));
        // 菜单上要看清字，玻璃压得比列表重一点
        panel.setBackground(new FrostDrawable(0x5A10151C, 18).attach(panel));

        TextView title = textView(15, SUB, true);
        title.setText("菜单");
        panel.addView(title);

        for (int i = 0; i < MENU_TEXTS.length; i++) {
            TextView t = textView(21, TEXT, false);
            t.setText(MENU_TEXTS[i]);
            t.setPadding(dp(18), dp(13), dp(18), dp(13));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(6);
            panel.addView(t, lp);
            menuItems.add(t);
        }

        TextView hint = textView(13, SUB, false);
        hint.setText("▲▼ 选择   OK 确定   返回 关闭");
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.topMargin = dp(14);
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(hint, hp);

        menuRoot.addView(panel, new FrameLayout.LayoutParams(dp(360), -2, Gravity.CENTER));
        menuRoot.setVisibility(GONE);
        addView(menuRoot, new FrameLayout.LayoutParams(-1, -1));
    }

    void showMenu() {
        if (mode == Mode.MENU) {
            applyMode(menuBack);
            return;
        }
        menuBack = (mode == Mode.LIST || mode == Mode.RADIO) ? mode : Mode.HIDDEN;
        menuSel = 0;
        refreshMenuSel();
        applyMode(Mode.MENU);
        refreshFrost();
    }

    private void refreshMenuSel() {
        for (int i = 0; i < menuItems.size(); i++) {
            TextView t = menuItems.get(i);
            boolean sel = (i == menuSel);
            t.setBackground(sel ? round(PRIMARY_SOFT, dp(10)) : null);
            t.setTextColor(sel ? PRIMARY : TEXT);
        }
    }

    private void runMenu() {
        int i = menuSel;
        applyMode(menuBack);
        if (i == 0) {
            cb.onRefresh();
        } else {
            cb.onQuit();
        }
    }

    private final Runnable numCommit = new Runnable() {
        @Override
        public void run() {
            String s = numBuf.toString();
            numBuf.setLength(0);
            numRoot.setVisibility(GONE);
            if (s.length() == 0) return;
            int no;
            try {
                no = Integer.parseInt(s);
            } catch (Exception e) {
                return;
            }
            List<ChannelRepo.Ch> list = repo.list(isTv);
            if (no <= 0 || no > list.size()) {
                android.util.Log.i("LizhiGecko", "num " + no + " out of range 1.." + list.size());
                return;
            }
            int i = no - 1;
            setPlayIdx(i);
            selIdx = i;
            ChannelRepo.Ch c = list.get(i);
            cb.onTune(c);
            if (c.tv) {
                radioPlaying = false;
                showOsd();
            } else {
                showRadio();
            }
        }
    };

    /** 数字键返回 true 表示已消费 */
    private boolean handleDigit(int kc) {
        int d = -1;
        if (kc >= KeyEvent.KEYCODE_0 && kc <= KeyEvent.KEYCODE_9) {
            d = kc - KeyEvent.KEYCODE_0;
        } else if (kc >= KeyEvent.KEYCODE_NUMPAD_0 && kc <= KeyEvent.KEYCODE_NUMPAD_9) {
            d = kc - KeyEvent.KEYCODE_NUMPAD_0;
        }
        if (d < 0) return false;
        if (numBuf.length() >= 3) numBuf.setLength(0);
        numBuf.append(d);
        numText.setText(numBuf.toString());
        numRoot.setVisibility(VISIBLE);
        numRoot.bringToFront();
        h.removeCallbacks(numCommit);
        h.postDelayed(numCommit, 1600);
        return true;
    }

    // ==================== 公共 API ====================
    private int playIdx() {
        return isTv ? tvIdx : radioIdx;
    }

    private void setPlayIdx(int i) {
        if (isTv) tvIdx = i; else radioIdx = i;
    }

    private ChannelRepo.Ch currentCh() {
        List<ChannelRepo.Ch> l = repo.list(isTv);
        if (l.isEmpty()) return null;
        int i = Math.min(Math.max(playIdx(), 0), l.size() - 1);
        return l.get(i);
    }

    /** 给菜单的"刷新频道"用 */
    ChannelRepo.Ch currentChannel() {
        return currentCh();
    }

    /** 给菜单的"刷新频道"用 */
    private String currentName() {
        ChannelRepo.Ch c = currentCh();
        return c == null ? "—" : c.name;
    }

    void setChannel(int idx, boolean tv) {
        this.isTv = tv;
        if (tv) tvIdx = idx; else radioIdx = idx;
        this.selIdx = idx;
        if (mode == Mode.OSD) showOsd();
        if (radioRoot != null && radioRoot.getVisibility() == VISIBLE) radioPanel.invalidate();
    }

    void showOsd() {
        applyMode(Mode.OSD);
        refreshFrost();
        osdRoot.invalidate();
        h.removeCallbacks(osdHide);
        h.postDelayed(osdHide, 8000);
    }

    private final Runnable osdHide = new Runnable() {
        @Override
        public void run() {
            if (mode == Mode.OSD) {
                applyMode(Mode.HIDDEN);
                cb.onListVisibility(false);
            }
        }
    };

    void showList() {
        // 每次打开列表都从"正在播的那一栏"开始，不记上次看的是哪一栏，
        // 免得又出现"看电视时翻了一下广播，回来按键就换广播"的错觉。
        listTv = isTv;
        selIdx = Math.max(0, playIdx());
        rebuildRows();
        applyMode(Mode.LIST);
        refreshFrost();
        cb.onListVisibility(true);
    }

    void showRadio() {
        radioPlaying = true;
        applyMode(Mode.RADIO);
        refreshFrost();
        if (!waveAnim.isRunning()) waveAnim.start();
        radioPanel.invalidate();
    }

    /** 从后台回来时恢复广播界面 */
    void restoreAfterForeground() {
        if (radioPlaying) showRadio();
    }

    void applyMode(Mode m) {
        if (mode != m) android.util.Log.i("LizhiGecko", "mode " + mode + " -> " + m);
        this.mode = m;
        setVisibility(VISIBLE);
        // 广播层同时承担「遮罩」职责：它是不透明的深底 + 波纹，把底下的 gdtv 网页盖住。
        // 所以菜单（MENU）打开时也必须留着 —— 以前只判 RADIO / LIST，一按菜单广播层就 GONE，
        // 菜单的半透明压暗直接落在原始网页上，看着像"遮罩没了"。
        boolean radioVisible = (m == Mode.RADIO)
                || (radioPlaying && (m == Mode.LIST || m == Mode.MENU));
        osdRoot.setVisibility(m == Mode.OSD ? VISIBLE : GONE);
        if (bottomGuard != null) {
            bottomGuard.setVisibility(m == Mode.HIDDEN || m == Mode.OSD ? VISIBLE : GONE);
        }
        listRoot.setVisibility(m == Mode.LIST ? VISIBLE : GONE);
        menuRoot.setVisibility(m == Mode.MENU ? VISIBLE : GONE);
        radioRoot.setVisibility(radioVisible ? VISIBLE : GONE);
        if (waveAnim != null) {
            if (radioVisible) {
                if (!waveAnim.isRunning()) waveAnim.start();
            } else {
                waveAnim.cancel();
            }
        }
        // 毛玻璃：面板切换时各 show 方法里各自调 refreshFrost()（前 1.5 秒抓 3 帧）。
        // 这里把还没跑的那两帧撤掉，面板收起后不再白抓。
        h.removeCallbacks(frostTick);
        h.removeCallbacks(frostShot);
    }

    private final Runnable frostTick = new Runnable() {
        @Override
        public void run() {
            // 已停用：降功耗。留着壳子是为了以后想开时改一行 postDelayed 就行。
        }
    };

    Mode getMode() { return mode; }

    boolean isTv() { return isTv; }

    /** 返回 true 表示按键已被消费 */
    boolean onKey(int kc) {
        // 菜单键：设置菜单。（导视键原来跳 gdtv 节目库，这一版先不接，见 CHANGELOG v1.2）
        if (kc == KeyEvent.KEYCODE_MENU) {
            showMenu();
            return true;
        }
        // 数字键直达频道（列表打开时不抢，列表有自己的上下选择逻辑）
        if (mode != Mode.LIST && mode != Mode.MENU && handleDigit(kc)) return true;
        List<ChannelRepo.Ch> list = repo.list(isTv);
        switch (mode) {
            case MENU:
                if (kc == KeyEvent.KEYCODE_BACK) {
                    applyMode(menuBack);
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_UP) {
                    menuSel = (menuSel - 1 + MENU_TEXTS.length) % MENU_TEXTS.length;
                    refreshMenuSel();
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_DOWN) {
                    menuSel = (menuSel + 1) % MENU_TEXTS.length;
                    refreshMenuSel();
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_DPAD_CENTER) {
                    runMenu();
                    return true;
                }
                return true;
            case HIDDEN:
                if (kc == KeyEvent.KEYCODE_BACK && radioPlaying) {
                    cb.onExit();
                    return true;
                }
                if (list.isEmpty()) {
                    if (kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_DPAD_CENTER) {
                        showList();
                        return true;
                    }
                    return false;
                }
                // 用户要求：▲ 频道号 +1，▼ 频道号 -1（和列表里"▲=上一行"正好相反）
                if (kc == KeyEvent.KEYCODE_DPAD_UP) { tune(1); return true; }
                if (kc == KeyEvent.KEYCODE_DPAD_DOWN) { tune(-1); return true; }
                if (kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_DPAD_CENTER) {
                    showList();
                    return true;
                }
                return false;
            case OSD:
                if (list.isEmpty()) {
                    if (kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_DPAD_CENTER) {
                        showList();
                        return true;
                    }
                    return false;
                }
                // 用户要求：▲ 频道号 +1，▼ 频道号 -1（和列表里"▲=上一行"正好相反）
                if (kc == KeyEvent.KEYCODE_DPAD_UP) { tune(1); return true; }
                if (kc == KeyEvent.KEYCODE_DPAD_DOWN) { tune(-1); return true; }
                if (kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_DPAD_CENTER) {
                    showList();
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_BACK) {
                    // 广播在播时按返回不再回电视（用户要求），直接回桌面，声音继续
                    if (radioPlaying) { cb.onExit(); return true; }
                    applyMode(Mode.HIDDEN);
                    return true;
                }
                return false;
            case LIST:
                if (kc == KeyEvent.KEYCODE_BACK) {
                    applyMode(radioPlaying ? Mode.RADIO : Mode.HIDDEN);
                    if (radioPlaying) showRadio();
                    cb.onListVisibility(false);
                    return true;
                }
                // 列表里的一切都以 listTv（当前这一栏）为准，绝不动 isTv。
                // isTv 只在真按 OK 播出去的那一刻才改。
                list = repo.list(listTv);
                if (list.isEmpty()) {
                    return false;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_UP) {
                    selIdx = (selIdx - 1 + list.size()) % list.size();
                    refreshRows(true);
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_DOWN) {
                    selIdx = (selIdx + 1) % list.size();
                    refreshRows(true);
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    listTv = !listTv;
                    selIdx = 0;
                    rebuildRows();
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_DPAD_CENTER) {
                    list = repo.list(listTv);
                    if (list.isEmpty()) return true;
                    if (selIdx >= list.size()) selIdx = 0;
                    // 到这里才是真的换到另一栏去播
                    isTv = listTv;
                    setPlayIdx(selIdx);
                    ChannelRepo.Ch c = list.get(selIdx);
                    cb.onListVisibility(false);
                    cb.onTune(c);
                    if (c.tv) {
                        radioPlaying = false;
                        showOsd();
                    } else {
                        showRadio();
                    }
                    return true;
                }
                return false;
            case RADIO:
                if (kc == KeyEvent.KEYCODE_BACK) {
                    // 不再回到电视（用户要求）—— 直接回桌面，广播继续播
                    cb.onExit();
                    return true;
                }
                if (list.isEmpty()) {
                    if (kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_DPAD_CENTER) {
                        showList();
                        return true;
                    }
                    return false;
                }
                // 用户要求：▲ 频道号 +1，▼ 频道号 -1（和列表里"▲=上一行"正好相反）
                if (kc == KeyEvent.KEYCODE_DPAD_UP) { tune(1); return true; }
                if (kc == KeyEvent.KEYCODE_DPAD_DOWN) { tune(-1); return true; }
                if (kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_DPAD_CENTER) {
                    showList();
                    return true;
                }
                return false;
        }
        return false;
    }

    private void tune(int delta) {
        // 硬保险：列表 / 菜单开着的时候绝不换台。
        // 用户遇到过"看着列表按上下，结果后面的台在换"——根因是列表被意外关掉、
        // 按键落到了 HIDDEN 分支。这里再兜一层，mode 哪天又错位也不会误换台。
        if (mode == Mode.LIST || mode == Mode.MENU) return;
        List<ChannelRepo.Ch> list = repo.list(isTv);
        if (list.isEmpty()) return;
        int i = (playIdx() + delta + list.size()) % list.size();
        setPlayIdx(i);
        selIdx = i;
        ChannelRepo.Ch c = list.get(i);
        cb.onTune(c);
        if (isTv) {
            radioPlaying = false;
            showOsd();
        } else {
            showRadio();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            android.util.Log.i("LizhiGecko", "overlay key " + e.getKeyCode() + " mode=" + mode);
            if (onKey(e.getKeyCode())) {
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }
}
