package com.self.water;

import android.content.Context;
import android.content.SharedPreferences;

/** token / uid / 设备号 的本地存储。 */
public final class Prefs {

    private static final String FILE = "water";
    private static final String K_TOKEN = "token";
    private static final String K_UID = "uid";
    private static final String K_PHONE = "phone";
    private static final String K_DEVICE = "device";
    private static final String K_PTYPE = "ptype";


    /** 智慧笑联小程序入口。 */
    private static final String XIAOLIAN =
            "alipays://platformapi/startapp?appId=2021001189641702";

    /**
     * 洗衣页深层链接（从支付宝分享链接解出）：
     *   pages/device/washing/index?__id__=6&query={"deviceType":4}&__appxPageId=14
     * page 参数本身是百分号编码的，原样保留即可。
     */
    private static final String LAUNDRY_PAGE =
            "&page=pages%2Fdevice%2Fwashing%2Findex%3F__id__%3D6"
            + "%26query%3D%25257B%252522deviceType%252522%25253A4%25257D"
            + "%26__appxPageId%3D14";

    /** 胖乖生活（洗澡）的包名。 */
    private static final String PANGGUAI_PKG = "com.qiekj.user";

    /** 各入口的默认目标。 */
    private static String defaultEntry(String key) {
        if ("laundry".equals(key)) {
            return XIAOLIAN + LAUNDRY_PAGE;
        }
        if ("shower".equals(key)) {
            return "pkg:" + PANGGUAI_PKG;   // 胖乖生活，直接启动
        }
        return XIAOLIAN;
    }

    /** 快捷入口的目标：完整 scheme、支付宝小程序分享链接，或纯 appId。 */
    public static String entry(Context c, String key) {
        String v = sp(c).getString("entry_" + key, "");
        return (v == null || v.length() == 0) ? defaultEntry(key) : v;
    }

    private Prefs() {
    }

    /**
     * 记住 Application context。
     *
     * 静态方法里拿不到 Context 时可以来这里取；
     * 这里顺手把第一次见到的 applicationContext 存下来。
     */
    private static volatile Context APP;

    public static Context app() {
        return APP;
    }

    private static SharedPreferences sp(Context c) {
        if (c != null) {
            APP = c.getApplicationContext();
        }
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String token(Context c) {
        return sp(c).getString(K_TOKEN, "");
    }

    public static String uid(Context c) {
        return sp(c).getString(K_UID, "");
    }

    public static String phone(Context c) {
        return sp(c).getString(K_PHONE, "");
    }

    public static String device(Context c) {
        String v = sp(c).getString(K_DEVICE, "");
        return v == null ? "" : v;
    }

    public static int ptype(Context c) {
        return sp(c).getInt(K_PTYPE, 21);
    }

    public static boolean loggedIn(Context c) {
        return token(c).length() > 0;
    }

    public static void saveLogin(Context c, String phone, String token, String uid) {
        sp(c).edit()
                .putString(K_PHONE, phone)
                .putString(K_TOKEN, token)
                .putString(K_UID, uid)
                .apply();
    }

    // ---------------------------------------------------- 798 免密（pga）相关

    private static final String K_EID = "eid";

    /**
     * eid：798 查免密协议状态（pga-check）必须要它。
     * 来自登录返回的 data.al.eid，之前一直没存，所以查不了免密状态。
     */
    public static String eid(Context c) {
        return sp(c).getString(K_EID, "");
    }

    public static void saveEid(Context c, String eid) {
        sp(c).edit().putString(K_EID, eid == null ? "" : eid).apply();
    }

    // ---------------------------------------------------- 免密引导提示

    /**
     * 是否已经不再提示「开通免密支付」。
     *
     * 按服务分开记：798 和胖乖是两个平台，你可能只开了其中一个的免密，
     * 所以在一边点了「不再提示」不该把另一边也静音。
     */
    public static boolean payNagDisabled(Context c, String service) {
        return sp(c).getBoolean("pay_nag_off_" + service, false);
    }

    public static void disablePayNag(Context c, String service) {
        sp(c).edit().putBoolean("pay_nag_off_" + service, true).apply();
    }

    /** 重新开启提示（设置页「重新显示免密提示」用）。 */
    public static void resetPayNag(Context c) {
        sp(c).edit()
                .remove("pay_nag_off_" + PayStatus.SVC_WATER)
                .remove("pay_nag_off_" + PayStatus.SVC_SHOWER)
                .apply();
    }

    public static void saveDevice(Context c, String deviceId) {
        sp(c).edit().putString(K_DEVICE, deviceId).apply();
    }

    public static void savePtype(Context c, int ptype) {
        sp(c).edit().putInt(K_PTYPE, ptype).apply();
    }

    public static void logout(Context c) {
        sp(c).edit().remove(K_TOKEN).remove(K_UID).apply();
    }

    // ---------------------------------------------------- 快捷入口

    public static void saveEntry(Context c, String key, String value) {
        sp(c).edit().putString("entry_" + key, value == null ? "" : value).apply();
    }

    // ---------------------------------------------------- 洗澡（胖乖生活）

    private static final String K_PG_TOKEN = "pg_token";
    private static final String K_PG_PHONE = "pg_phone";
    private static final String K_SHOWER_SN = "shower_sn";


    public static String pgToken(Context c) {
        return sp(c).getString(K_PG_TOKEN, "");
    }

    public static boolean pgLoggedIn(Context c) {
        return pgToken(c).length() > 0;
    }

    public static void savePgLogin(Context c, String phone, String token) {
        sp(c).edit().putString(K_PG_PHONE, phone).putString(K_PG_TOKEN, token).apply();
    }

    public static void pgLogout(Context c) {
        sp(c).edit().remove(K_PG_TOKEN).apply();
    }

    public static String showerSn(Context c) {
        String v = sp(c).getString(K_SHOWER_SN, "");
        return v == null ? "" : v;
    }

    public static void saveShowerSn(Context c, String sn) {
        sp(c).edit().putString(K_SHOWER_SN, sn == null ? "" : sn).apply();
    }

    // ---------------------------------------------------- 在途洗澡订单

    /**
     * 一笔「已经下单、但还没结束」的订单。
     *
     * 之所以要落到本地：下单成功之后如果进程被杀、或者解锁失败来不及收尾，
     * 这笔订单会一直挂在账号下被结算。存下来才能在下次打开时提醒并收尾。
     */
    public static final class PendingOrder {
        public String orderId = "";   // trade/create 返回的 orderId，撤销用它
        public String orderNo = "";   // unlock 返回的 orderNo，结束用它
        public String goodsId = "";
        public String sn = "";
        public String title = "";
        public long at;

        public boolean isEmpty() {
            return orderId.length() == 0 && orderNo.length() == 0;
        }
    }

    private static final String K_PG_PENDING = "pg_pending_order";

    public static PendingOrder pgPending(Context c) {
        PendingOrder p = new PendingOrder();
        String raw = sp(c).getString(K_PG_PENDING, "");
        if (raw == null || raw.length() == 0) {
            return p;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(raw);
            p.orderId = o.optString("orderId", "");
            p.orderNo = o.optString("orderNo", "");
            p.goodsId = o.optString("goodsId", "");
            p.sn = o.optString("sn", "");
            p.title = o.optString("title", "");
            p.at = o.optLong("at", 0);
        } catch (Exception ignored) {
            // 坏了就当没有，避免卡在一个解析不了的记录上
        }
        return p;
    }

    public static void savePgPending(Context c, PendingOrder p) {
        if (p == null || p.isEmpty()) {
            clearPgPending(c);
            return;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("orderId", p.orderId == null ? "" : p.orderId);
            o.put("orderNo", p.orderNo == null ? "" : p.orderNo);
            o.put("goodsId", p.goodsId == null ? "" : p.goodsId);
            o.put("sn", p.sn == null ? "" : p.sn);
            o.put("title", p.title == null ? "" : p.title);
            o.put("at", p.at == 0 ? System.currentTimeMillis() : p.at);
            sp(c).edit().putString(K_PG_PENDING, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 解锁成功后补上 orderNo（结束要用它）。 */
    public static void setPgPendingOrderNo(Context c, String orderNo) {
        PendingOrder p = pgPending(c);
        p.orderNo = orderNo == null ? "" : orderNo;
        savePgPending(c, p);
    }

    public static void clearPgPending(Context c) {
        sp(c).edit().remove(K_PG_PENDING).apply();
    }

    // ---------------------------------------------------- 检查更新

    private static final String K_UPDATE_AT = "update_at";
    private static final String K_UPDATE_SKIP = "update_skip";

    /** 上次检查更新的时间戳；0 表示从没查过。 */
    public static long lastUpdateCheck(Context c) {
        return sp(c).getLong(K_UPDATE_AT, 0L);
    }

    public static void setLastUpdateCheck(Context c, long at) {
        sp(c).edit().putLong(K_UPDATE_AT, at).apply();
    }

    /** 用户点过「稍后」的版本名 —— 同一个版本不再自动提醒。 */
    public static String skippedUpdate(Context c) {
        String v = sp(c).getString(K_UPDATE_SKIP, "");
        return v == null ? "" : v;
    }

    public static void setSkippedUpdate(Context c, String version) {
        sp(c).edit().putString(K_UPDATE_SKIP, version == null ? "" : version).apply();
    }

    // ---------------------------------------------------- 外观

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    public static int themeMode(Context c) {
        return sp(c).getInt("theme", THEME_SYSTEM);
    }

    public static void setThemeMode(Context c, int mode) {
        sp(c).edit().putInt("theme", mode).apply();
    }

    // ---------------------------------------------------- 桌面小组件样式

    public static final int WIDGET_DARK = 0;
    public static final int WIDGET_LIGHT = 1;
    public static final int WIDGET_GLASS = 2;

    /** 小组件配色。默认深色 —— 在浅色和深色壁纸上都立得住。 */
    public static int widgetStyle(Context c) {
        return sp(c).getInt("widget_style", WIDGET_DARK);
    }

    public static void setWidgetStyle(Context c, int style) {
        sp(c).edit().putInt("widget_style", style).apply();
    }

    // ---------------------------------------------------- 首次启动

    public static boolean onboarded(Context c) {
        return sp(c).getBoolean("onboarded", false);
    }

    public static void setOnboarded(Context c) {
        sp(c).edit().putBoolean("onboarded", true).apply();
    }
}
