package com.newbox.lizhigecko;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;

/**
 * 广播原生播放（当前**未接入**，仅保留实现）。
 *
 * 曾以为广播流 rlizhi.gdtv.cn/audio/*.m3u8?auth_key=xxx 是简单 md5、长期有效，
 * 系统 MediaPlayer 直接就能放。2026-11-25 实测推翻：auth_key 首段就是过期时间戳，
 * 内置的那批已过期 47 小时、CDN 全 403；重新抓接口只能拿到 161B 短 t_token。
 * 所以广播实际走的是 GeckoView 打开 audioChannelDetail/<pk> 页面（见 TvService.play），
 * 由页面自己现取 token，不会过期。本类保留仅因音频 HTTP 头写法可参考。
 */
class RadioPlayer {
    private static final String TAG = "LizhiGecko";

    interface Listener {
        void onPrepared();

        void onError(String why);
    }

    private final Context ctx;
    private MediaPlayer mp;
    private String url;
    private Listener listener;

    RadioPlayer(Context c) {
        this.ctx = c.getApplicationContext();
    }

    void setListener(Listener l) {
        this.listener = l;
    }

    boolean isPlaying() {
        return mp != null;
    }

    String url() {
        return url;
    }

    void play(final String u) {
        if (u == null || u.length() == 0) return;
        if (u.equals(url) && mp != null) return;
        stop();
        url = u;
        try {
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            // 老版 lizhilive 就是这么带的头，照抄，别自己发挥
            java.util.Map<String, String> headers = new java.util.HashMap<String, String>();
            headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/66.0 Mobile Safari/537.36");
            headers.put("Referer", "https://www.gdtv.cn/");
            p.setDataSource(ctx, Uri.parse(u), headers);
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    Log.i(TAG, "radio prepared " + url);
                    try { m.start(); } catch (Throwable e) { Log.w(TAG, "radio start " + e); }
                    if (listener != null) listener.onPrepared();
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    Log.e(TAG, "radio error " + what + "/" + extra + " " + url);
                    if (listener != null) listener.onError(what + "/" + extra);
                    return true;
                }
            });
            mp = p;
            p.prepareAsync();
        } catch (Throwable e) {
            Log.e(TAG, "radio play fail " + e);
            if (listener != null) listener.onError(String.valueOf(e));
        }
    }

    void stop() {
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
    }
}
