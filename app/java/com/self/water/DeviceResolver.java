package com.self.water;

import android.content.Context;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 把设备编号/SN 解析成可读的位置信息（分组、副标题、以及洗澡需要的 goodsId）。
 * 结果按 类型+编号 缓存，避免重复请求。
 */
public final class DeviceResolver {

    private static final Map<String, JSONObject> CACHE = new HashMap<>();

    private DeviceResolver() {
    }

    /** 结构：{ group, sub, extra }；解析失败返回 null。 */
    public static synchronized JSONObject resolve(Context c, String type, String id) {
        String key = type + ":" + id;
        if (CACHE.containsKey(key)) {
            return CACHE.get(key);
        }
        JSONObject out = null;
        try {
            out = DeviceStore.TYPE_SHOWER.equals(type)
                    ? resolveShower(c, id) : resolveWater(c, id);
        } catch (Exception ignored) {
        }
        // ⚠️ 只缓存成功结果。
        //
        // 之前连 null 一起缓存了，后果很严重：只要第一次解析失败
        // （没登录、token 过期、那一下没网），这台设备就**永久**解析不出来了 ——
        // 重新扫码、重新查询都拿的是缓存里那个 null，名字和 goodsId 再也没有。
        // 扫码添加后"设备没有名字、状态一直是 —"就是这么来的。
        if (out != null) {
            CACHE.put(key, out);
        }
        return out;
    }

    /** 丢掉某台设备的缓存，用于「重新解析」。 */
    public static synchronized void invalidate(String type, String id) {
        CACHE.remove(type + ":" + id);
    }

    /** 清空全部缓存。 */
    public static synchronized void clearCache() {
        CACHE.clear();
    }

    public static String groupOf(Context c, String type, String id) {
        JSONObject o = resolve(c, type, id);
        return o == null ? "" : o.optString("group", "");
    }

    public static String subOf(Context c, String type, String id) {
        JSONObject o = resolve(c, type, id);
        return o == null ? null : o.optString("sub", null);
    }

    public static String extraOf(Context c, String type, String id) {
        JSONObject o = resolve(c, type, id);
        return o == null ? "" : o.optString("extra", "");
    }

    // ---------------------------------------------------------- 取水

    private static JSONObject resolveWater(Context c, String id) throws Exception {
        if (!Prefs.loggedIn(c)) {
            return null;
        }
        Api.Resp r = Api.deviceHome(id, Prefs.token(c));
        JSONObject j = r.json();
        if (r.err != null || j.optInt("code", -999) != 0) {
            return null;
        }
        JSONObject data = j.optJSONObject("data");
        JSONObject dev = data == null ? null : data.optJSONObject("device");
        if (dev == null) {
            return null;
        }
        JSONObject bm = dev.optJSONObject("bm");
        JSONObject ep = dev.optJSONObject("ep");
        String epName = ep == null ? "" : ep.optString("name", "");
        String brand = bm == null ? "" : bm.optString("brand", "");
        String model = bm == null ? "" : bm.optString("model", "");

        JSONObject out = new JSONObject();
        out.put("group", epName);
        out.put("sub", (epName.length() > 0 ? epName + "  ·  " : "") + brand + " " + model);
        out.put("extra", "");
        return out;
    }

    // ---------------------------------------------------------- 洗澡

    private static JSONObject resolveShower(Context c, String id) throws Exception {
        if (!Prefs.pgLoggedIn(c)) {
            return null;
        }
        JSONObject dev = PanggualApi.resolveDevice(id, Prefs.pgToken(c));
        String orgName = dev.optString("orgName", "");
        String goodsName = dev.optString("goodsName", "");

        JSONObject out = new JSONObject();
        out.put("group", orgName);
        out.put("sub", (orgName.length() > 0 ? orgName + "  ·  " : "") + goodsName);
        out.put("extra", dev.optString("goodsId", ""));
        return out;
    }
}
