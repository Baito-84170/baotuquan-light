package com.self.water;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.List;

/**
 * 设备实时状态查询。
 *
 * 取水（慧生活798）：可拿到完整状态 —— 空闲 / 使用中 / 已激活 / 关闭 / 异常。
 * 洗澡（胖乖生活）：可拿到健康状态（正常 / 故障）与「我是否在用」，拿不到他人占用。
 */
public final class StatusChecker {

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private StatusChecker() {
    }

    /** 一条状态：文案 + 配色。 */
    public static final class Status {
        public final String label;
        public final int bgRes;
        public final int fgRes;

        Status(String label, int bgRes, int fgRes) {
            this.label = label;
            this.bgRes = bgRes;
            this.fgRes = fgRes;
        }
    }

    public interface Callback {
        void onStatus(String deviceId, Status status);
    }

    static Status unknown() {
        return new Status("—", R.color.idle_bg, R.color.idle);
    }

    static Status querying() {
        return new Status("…", R.color.idle_bg, R.color.idle);
    }

    /** 并发查询一批设备的状态，每有结果就回调一次。 */
    public static void checkAll(final Context ctx, final String type,
                                List<DeviceStore.Device> devices, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        for (final DeviceStore.Device d : devices) {
            cb.onStatus(d.id, querying());
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final Status s = checkOne(app, type, d);
                    UI.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onStatus(d.id, s);
                        }
                    });
                }
            }).start();
        }
    }

    private static Status checkOne(Context ctx, String type, DeviceStore.Device d) {
        try {
            if (DeviceStore.TYPE_WATER.equals(type)) {
                return waterStatus(ctx, d.id);
            }
            return showerStatus(ctx, d);
        } catch (Exception e) {
            return unknown();
        }
    }

    // ---------------------------------------------------------- 取水

    private static Status waterStatus(Context ctx, String deviceId) {
        if (!Prefs.loggedIn(ctx)) {
            return unknown();
        }
        Api.Resp r = Api.deviceStatus(deviceId, Prefs.token(ctx));
        JSONObject j = r.json();
        if (r.err != null || j.optInt("code", -999) != 0) {
            return new Status("离线", R.color.idle_bg, R.color.idle);
        }
        JSONObject data = j.optJSONObject("data");
        JSONObject dev = data == null ? null : data.optJSONObject("device");
        JSONObject gene = dev == null ? null : dev.optJSONObject("gene");
        if (gene == null) {
            return unknown();
        }
        int st = gene.optInt("status", -1);
        double vel = gene.optDouble("vel", 0);
        switch (st) {
            case 0:
                return new Status("关闭", R.color.idle_bg, R.color.idle);
            case 1:
                return new Status("已激活", R.color.warn_bg, R.color.warn);
            case 10:
                return new Status(vel > 0 ? "出水中" : "使用中", R.color.warn_bg, R.color.warn);
            case 20:
                return new Status("冲洗中", R.color.warn_bg, R.color.warn);
            case 30:
                return new Status("准备中", R.color.warn_bg, R.color.warn);
            case 99:
                return new Status("空闲", R.color.ok_bg, R.color.ok);
            case 98:
                return new Status("已停用", R.color.idle_bg, R.color.idle);
            case 2:
                return new Status("异常", R.color.warn_bg, R.color.warn);
            default:
                return unknown();
        }
    }

    // ---------------------------------------------------------- 洗澡

    private static Status showerStatus(Context ctx, DeviceStore.Device d) {
        if (!Prefs.pgLoggedIn(ctx)) {
            return unknown();
        }
        String goodsId = d.extra;
        if (goodsId == null || goodsId.length() == 0) {
            // 还没解析过这台设备，状态未知（用一次之后就有了）
            return unknown();
        }
        String token = Prefs.pgToken(ctx);

        // 我是否正在使用这台
        PanggualApi.Resp last = PanggualApi.lastOrder(goodsId, token);
        JSONObject lj = last.json();
        if (last.err == null && lj.optInt("code", -999) == 0) {
            JSONObject ld = lj.optJSONObject("data");
            if (ld != null && ld.length() > 0) {
                return new Status("我使用中", R.color.ok_bg, R.color.ok);
            }
        }

        // 设备健康
        PanggualApi.Resp r = PanggualApi.checkDeviceStatus(goodsId, token);
        JSONObject j = r.json();
        if (r.err != null || j.optInt("code", -999) != 0) {
            return new Status("离线", R.color.idle_bg, R.color.idle);
        }
        JSONObject data = j.optJSONObject("data");
        if (data == null) {
            return unknown();
        }
        boolean healthy = data.optBoolean("health", true);
        if (!healthy) {
            String desc = data.optString("faultDesc", "");
            return new Status(desc.length() > 0 ? desc : "故障",
                    R.color.warn_bg, R.color.warn);
        }
        return new Status("正常", R.color.ok_bg, R.color.ok);
    }
}
