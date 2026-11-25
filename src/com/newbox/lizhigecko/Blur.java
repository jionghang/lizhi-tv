package com.newbox.lizhigecko;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoView;

/**
 * 毛玻璃底图：把 GeckoView 正在显示的那一帧抓下来，缩到很小再模糊，
 * 悬浮层用它当面板背景 —— API 28 没有 RenderEffect、也没有跨窗口实时模糊，
 * 这是唯一能做到"透出后面画面"的路子。
 *
 * 三条路依次尝试：
 * 1. GeckoView.capturePixels() —— 官方接口，拿的是渲染管线里那一帧，最准；
 * 2. GeckoView 内部 SurfaceView 的 PixelCopy —— 视频走 surface 时能抓到；
 * 3. Window 的 PixelCopy —— 兜底（SurfaceView 的内容一般是空的）。
 * 全失败就返回 null，调用方退回深色半透明底，功能不受影响。
 */
final class Blur {

    private static final String TAG = "LizhiGecko";

    interface Cb {
        void onBitmap(Bitmap b);
    }

    private static final Handler H = new Handler(Looper.getMainLooper());

    static void capture(final int outW, final int outH, final Cb cb) {
        if (outW <= 0 || outH <= 0) {
            cb.onBitmap(null);
            return;
        }
        final MainActivity act = MainActivity.sInstance;
        if (act == null || Build.VERSION.SDK_INT < 26) {
            cb.onBitmap(null);
            return;
        }
        try {
            final View decor = act.getWindow().getDecorView();
            final GeckoView gv = findGecko(decor);
            final Bitmap raw = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);

            final Runnable toGecko = new Runnable() {
                @Override
                public void run() {
                    tryGecko(gv, act.getWindow(), raw, cb);
                }
            };

            final SurfaceView sv = findSurface(decor);
            if (sv != null && sv.getHolder() != null && sv.getHolder().getSurface() != null
                    && sv.getHolder().getSurface().isValid()) {
                PixelCopy.request(sv, raw, new PixelCopy.OnPixelCopyFinishedListener() {
                    @Override
                    public void onPixelCopyFinished(int result) {
                        if (result == PixelCopy.SUCCESS) {
                            deliver(cb, blur(raw));
                        } else {
                            Log.w(TAG, "PixelCopy surface result=" + result);
                            toGecko.run();
                        }
                    }
                }, H);
                return;
            }
            toGecko.run();
        } catch (Throwable e) {
            Log.w(TAG, "blur capture fail " + e);
            deliver(cb, null);
        }
    }

    @SuppressWarnings("unchecked")
    private static void tryGecko(GeckoView gv, final Window win, final Bitmap raw, final Cb cb) {
        if (gv == null) {
            copyWindow(win, raw, cb);
            return;
        }
        try {
            GeckoResult<Bitmap> r = gv.capturePixels();
            r.then(new GeckoResult.OnValueListener<Bitmap, Void>() {
                @Override
                public GeckoResult<Void> onValue(Bitmap b) {
                    if (b == null || b.isRecycled()) {
                        copyWindow(win, raw, cb);
                        return null;
                    }
                    deliver(cb, blur(Bitmap.createScaledBitmap(b, raw.getWidth(), raw.getHeight(), true)));
                    return null;
                }
            }, new GeckoResult.OnExceptionListener<Void>() {
                @Override
                public GeckoResult<Void> onException(Throwable e) {
                    Log.w(TAG, "capturePixels fail " + e);
                    copyWindow(win, raw, cb);
                    return null;
                }
            });
        } catch (Throwable e) {
            Log.w(TAG, "capturePixels throw " + e);
            copyWindow(win, raw, cb);
        }
    }

    private static void copyWindow(final Window win, final Bitmap raw, final Cb cb) {
        try {
            PixelCopy.request(win, raw, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int result) {
                    deliver(cb, result == PixelCopy.SUCCESS ? blur(raw) : null);
                }
            }, H);
        } catch (Throwable e) {
            deliver(cb, null);
        }
    }

    private static void deliver(final Cb cb, final Bitmap b) {
        H.post(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "frost " + (b == null ? "fail" : ("ok " + b.getWidth() + "x" + b.getHeight())));
                cb.onBitmap(b);
            }
        });
    }

    private static GeckoView findGecko(View v) {
        if (v instanceof GeckoView) return (GeckoView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                GeckoView s = findGecko(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }

    private static SurfaceView findSurface(View v) {
        if (v instanceof SurfaceView) return (SurfaceView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                SurfaceView s = findSurface(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }

    /**
     * 三次盒式模糊 ≈ 高斯。图只有一百来个像素宽，随便糙也看不出来，
     * 重点是别在主线程上花超过几毫秒。
     */
    static Bitmap blur(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        // 横竖必须用各自的比例算半径。以前两边共用一个 min(w,h)/12 ——
        // 在 16:9 的图上，7px 只占宽度的 4.4%、却占了高度的 7.8%，
        // 竖着糊得比横着狠，出来就是一团竖条纹，看着像画面被压扁了（用户反馈）。
        int rx = Math.max(2, w / 16);
        int ry = Math.max(2, h / 16);
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 3; pass++) {
            boxH(px, tmp, w, h, rx);
            boxV(tmp, px, w, h, ry);
        }
        // 不再整体压暗：压暗交给 drawFrost 的自适应 scrim 统一控制，
        // 这里再压一道会让面板怎么调都很闷（用户反复要更透）。
        src.setPixels(px, 0, w, 0, 0, w, h);
        return src;
    }

    private static void boxH(int[] in, int[] out, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[row + clamp(i, w)];
                sa += (c >>> 24) & 0xFF; sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF; sb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[row + clamp(x + r + 1, w)];
                int sub = in[row + clamp(x - r, w)];
                sa += ((add >>> 24) & 0xFF) - ((sub >>> 24) & 0xFF);
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static void boxV(int[] in, int[] out, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[clamp(i, h) * w + x];
                sa += (c >>> 24) & 0xFF; sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF; sb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[clamp(y + r + 1, h) * w + x];
                int sub = in[clamp(y - r, h) * w + x];
                sa += ((add >>> 24) & 0xFF) - ((sub >>> 24) & 0xFF);
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int clamp(int i, int n) {
        return i < 0 ? 0 : (i >= n ? n - 1 : i);
    }
}
