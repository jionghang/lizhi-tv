package com.newbox.lizhigecko;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** 频道数据 + 节目单（当前 / 下一节目） */
class ChannelRepo {
    private static final String TAG = "LizhiWeb";

    // ---- 来源类型 ----
    /** gdtv 直播（电视/广播）：只能走 GeckoView，CDN 认 TLS 指纹 */
    static final String KIND_GDTV = "gdtv";
    /** CCTV 直播：走 tv.cctv.com 的频道页 */
    static final String KIND_CCTV = "cctv";
    /** gdtv 节目库：vfile2.grtn.cn 的无签名 MP4，系统 MediaPlayer 就能播 */
    static final String KIND_VOD_MP4 = "vod-mp4";
    /** gdtv 广播回放：avod.gdtv.cn 的标准 HLS（纯音频），内置 ExoPlayer 播 */
    static final String KIND_VOD_HLS = "vod-hls";

    static class Ch {
        int pk;
        String name;
        String logo;
        String cover;
        String url;          // 广播：rlizhi.gdtv.cn/audio/*.m3u8?auth_key=…（**会过期**，仅作历史数据；实际播放走 pageUrl()）
        boolean tv;
        /** 来源类型，默认 gdtv */
        String kind = KIND_GDTV;
        /** CCTV 频道代号，如 cctv13；拼 https://tv.cctv.com/live/{code}/ */
        String code;
        long[][] times;      // {start,end}
        String[] titles;

        boolean isGdtv() {
            return KIND_GDTV.equals(kind);
        }

        /** 这类频道要不要用 GeckoView 打开网页来播 */
        boolean isWeb() {
            return KIND_GDTV.equals(kind) || KIND_CCTV.equals(kind);
        }

        /** 网页播放时该打开的地址 */
        String pageUrl() {
            if (KIND_CCTV.equals(kind)) {
                // 直接开 /m/ 移动版，省掉官网页那段 window.location 跳转（跳一次多等 1~2 秒）。
                //
                // 曾经写过一个自建播放页（原生 HLS + 三级死锁自愈），实测比官方页更差：
                //   起播 10 秒能出画，但解码器必然周期性死锁，画面定住后靠跳段/reloc
                //   也救不回来，用户看到的是"黑屏循环"。官方页走的是自己的 MSE(hls.js)
                //   管线，虽然掉帧但画面一直在，所以退回来。
                // 全屏问题改由 content script 的"影院模式"解决（见 assets/ext/lizhi）。
                return "https://tv.cctv.com/live/" + code + "/m/";
            }
            if (isWeb()) {
                return "https://www.gdtv.cn/"
                        + (tv ? "tvChannelDetail/" : "audioChannelDetail/") + pk;
            }
            // 原生播放的片子直接给流地址
            return url;
        }
    }

    final List<Ch> tv = new ArrayList<Ch>();
    final List<Ch> radio = new ArrayList<Ch>();
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private final Context app;

    ChannelRepo(Context ctx) {
        hm.setTimeZone(TimeZone.getDefault());
        app = ctx.getApplicationContext();
        load(ctx);
    }

    /** 重新从内置 channels.json 读一遍（菜单里的"刷新频道"） */
    synchronized void reload() {
        tv.clear();
        radio.clear();
        load(app);
        Log.i(TAG, "ChannelRepo reload tv=" + tv.size() + " radio=" + radio.size());
    }

    private void load(Context ctx) {
        try {
            InputStream in = ctx.getAssets().open("channels.json");
            byte[] buf = new byte[in.available()];
            in.read(buf);
            in.close();
            JSONObject root = new JSONObject(new String(buf, "UTF-8"));
            parse(root.optJSONArray("tv"), tv, true);
            parse(root.optJSONArray("radio"), radio, false);
        } catch (Exception e) {
            Log.e(TAG, "ChannelRepo load " + e);
        }
    }

    private void parse(JSONArray arr, List<Ch> out, boolean isTv) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                Ch c = new Ch();
                c.pk = o.optInt("pk");
                c.name = o.optString("name", "");
                c.logo = o.optString("logo", "");
                c.cover = o.optString("cover", "");
                c.url = o.optString("url", "");
                c.tv = isTv;
                c.kind = o.optString("kind", KIND_GDTV);
                c.code = o.optString("code", "");
                JSONArray ps = o.optJSONArray("programs");
                if (ps != null && ps.length() > 0) {
                    c.times = new long[ps.length()][2];
                    c.titles = new String[ps.length()];
                    for (int j = 0; j < ps.length(); j++) {
                        JSONArray p = ps.getJSONArray(j);
                        c.times[j][0] = p.optLong(0);
                        c.times[j][1] = p.optLong(1);
                        c.titles[j] = p.optString(2, "");
                    }
                }
                out.add(c);
            } catch (Exception ignored) {
            }
        }
    }

    List<Ch> list(boolean isTv) {
        return isTv ? tv : radio;
    }

    /** 返回 {当前节目, 下一节目, 进度0-100, 当前开始时间, 当前结束时间} */
    /**
     * 当前节目信息：{当前名, 下一名, 进度%, 当前开始, 当前结束, 下一开始}。
     *
     * 第 6 位是后加的。gdtv 的节目单在凌晨常有空档（停播 / 购物时段，例如广东民生
     * 23:40 之后直接跳到 07:05），落在空档里时"当前节目"确实是空的，但界面不该
     * 因此显示"节目单未覆盖"——把最近的一条抬成"接下来 07:05 xxx"才对。
     */
    String[] program(Ch c, long now) {
        String cur = "", next = "";
        int pct = 0;
        long st = 0, et = 0, ns = 0;
        if (c != null && c.times != null) {
            for (int i = 0; i < c.times.length; i++) {
                if (now >= c.times[i][0] && now < c.times[i][1]) {
                    cur = c.titles[i];
                    st = c.times[i][0];
                    et = c.times[i][1];
                    if (i + 1 < c.titles.length) {
                        next = c.titles[i + 1];
                        ns = c.times[i + 1][0];
                    }
                    long span = et - st;
                    pct = span > 0 ? (int) ((now - st) * 100 / span) : 0;
                    break;
                }
            }
            if (cur.length() == 0) {
                for (int i = 0; i < c.times.length; i++) {
                    if (c.times[i][0] > now) {
                        next = c.titles[i];
                        ns = c.times[i][0];
                        break;
                    }
                }
            }
        }
        return new String[]{cur, next, String.valueOf(pct),
                st > 0 ? hm.format(st) : "", et > 0 ? hm.format(et) : "",
                ns > 0 ? hm.format(ns) : ""};
    }
}
