package com.newbox.lizhigecko;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 记住上次看的台。
 *
 * 存在 SharedPreferences 里，只存 pk + 是不是广播。
 * 为什么要存 pk 而不是下标：频道表以后可能增删，下标会错位，pk 是稳定的。
 */
final class LastChannel {

    private static final String P = "lizhigecko";
    private static final String K_PK = "last_pk";
    private static final String K_TV = "last_tv";
    /** 上次那个台的页面地址。CCTV 和应用内页面的地址不能由 pk 推出来，所以单独存一份。 */
    private static final String K_URL = "last_url";
    /** 从没看过时默认广东卫视 */
    private static final int DEF_PK = 43;

    private LastChannel() { }

    static void save(Context c, ChannelRepo.Ch ch) {
        if (c == null || ch == null || ch.pk <= 0) return;
        try {
            prefs(c).edit()
                    .putInt(K_PK, ch.pk)
                    .putBoolean(K_TV, ch.tv)
                    .putString(K_URL, ch.pageUrl())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    static int pk(Context c) {
        try {
            return prefs(c).getInt(K_PK, DEF_PK);
        } catch (Throwable e) {
            return DEF_PK;
        }
    }

    static boolean isTv(Context c) {
        try {
            return prefs(c).getBoolean(K_TV, true);
        } catch (Throwable e) {
            return true;
        }
    }

    /** 上次那个台的页面地址，冷启动时直接加载它，不用先开第一个台再跳 */
    static String url(Context c) {
        try {
            String u = prefs(c).getString(K_URL, null);
            if (u != null && u.length() > 0) return u;
        } catch (Throwable ignored) {
        }
        // 老版本只存了 pk，按 gdtv 规则补出来
        int pk = pk(c);
        return isTv(c)
                ? "https://www.gdtv.cn/tvChannelDetail/" + pk
                : "https://www.gdtv.cn/audioChannelDetail/" + pk;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(P, Context.MODE_PRIVATE);
    }
}
