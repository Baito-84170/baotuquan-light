package com.self.water;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 极简 HTTP 客户端。只用 Android 自带的 HttpURLConnection 与 org.json，
 * 不引入任何第三方库。
 */
public final class Api {

    public static final String BASE = "https://i.ilife798.com";
    private static final String VERSION_CODE = "31007";
    private static final String UA = "Android_ilife798_" + VERSION_CODE;
    private static final int TIMEOUT = 20000;

    private Api() {
    }

    /** 一次请求的结果。body 为文本；raw 为二进制（验证码图片）；err 非空表示失败。 */
    public static final class Resp {
        public int http;
        public String body;
        public byte[] raw;
        public String err;

        public boolean ok() {
            return err == null && http == 200;
        }

        public JSONObject json() {
            try {
                return new JSONObject(body == null ? "" : body);
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    public static Resp get(String path, String token) {
        return exec("GET", path, null, token, false);
    }

    public static Resp getRaw(String path, String token) {
        return exec("GET", path, null, token, true);
    }

    public static Resp postJson(String path, String json, String token) {
        return exec("POST", path, json, token, false);
    }

    private static Resp exec(String method, String path, String jsonBody, String token, boolean raw) {
        Resp r = new Resp();
        HttpURLConnection c = null;
        try {
            URL url = new URL(BASE + path);
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setRequestProperty("ApplicationType", "1,1");
            c.setRequestProperty("VersionCode", VERSION_CODE);
            c.setRequestProperty("user-agent", UA);
            c.setRequestProperty("Accept", "*/*");
            if (token != null && token.length() > 0) {
                c.setRequestProperty("Authorization", token);
            }
            if (jsonBody != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                OutputStream os = c.getOutputStream();
                os.write(jsonBody.getBytes("UTF-8"));
                os.flush();
                os.close();
            }
            r.http = c.getResponseCode();
            InputStream is = (r.http >= 200 && r.http < 400) ? c.getInputStream() : c.getErrorStream();
            byte[] data = is == null ? new byte[0] : readAll(is);
            if (raw) {
                r.raw = data;
            } else {
                r.body = new String(data, "UTF-8");
            }
        } catch (Exception e) {
            r.err = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        return r;
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        is.close();
        return bos.toByteArray();
    }

    // ---------------------------------------------------------- 具体接口

    /** 图形验证码。服务端 Content-Type 标的可能是 jpeg，实际是 PNG，按字节解码即可。 */
    public static Resp captcha(double seed) {
        String p = "/api/v1/captcha/?s=" + seed + "&r=" + System.currentTimeMillis();
        return getRaw(p, null);
    }

    /** 发送短信验证码。 */
    public static Resp sendSms(String phone, String captchaCode, double seed) {
        try {
            JSONObject o = new JSONObject();
            o.put("un", phone);
            o.put("authCode", captchaCode);
            o.put("s", seed);
            return postJson("/api/v1/acc/login/code", o.toString(), null);
        } catch (Exception e) {
            Resp r = new Resp();
            r.err = e.toString();
            return r;
        }
    }

    /** 登录，成功时 data.al 里有 token / uid / eid。 */
    public static Resp login(String phone, String smsCode) {
        try {
            JSONObject o = new JSONObject();
            o.put("un", phone);
            o.put("authCode", smsCode);
            return postJson("/api/v1/acc/login", o.toString(), null);
        } catch (Exception e) {
            Resp r = new Resp();
            r.err = e.toString();
            return r;
        }
    }

    /** 查设备详情。 */
    public static Resp deviceHome(String deviceId, String token) {
        return get("/api/v1/ui/app/dev/home/1?did=" + enc(deviceId) + "&apply=6", token);
    }

    /**
     * 首页聚合数据。**这是 798 唯一能拿到"设备名单"的接口。**
     *
     * 返回 {@code data.favos : List<DeviceInfo>} —— 用户在官方 App 里收藏（关注）的设备，
     * 每个都带 id / 名称 / 备注 / 站点 / 实时状态。
     *
     * 798 没有"列出整栋楼所有机器"的接口，这点和胖乖不一样：
     *   - `/api/v1/dev/near` 只返回 {name, distance}，**没有设备 id**，选不了；
     *   - `/api/v1/ep/station/lst` 只到"站点"这一层，站点下面没有再列设备的口子。
     * 所以取水这边只能靠"收藏"—— 收藏了才在名单里。
     */
    public static Resp master(String token) {
        return get("/api/v1/ui/app/master", token);
    }

    /** 收藏 / 取消收藏一台设备（remove=1 表示取消）。 */
    public static Resp favo(String deviceId, boolean remove, String token) {
        return get("/api/v1/dev/favo?did=" + enc(deviceId)
                + "&remove=" + (remove ? 1 : 0), token);
    }

    /** 一台取水设备（来自 master 的 favos）。 */
    public static final class WaterDevice {
        public String id = "";
        public String name = "";
        public String memo = "";     // 用户备注
        public String site = "";     // 所属站点（ep.name）
        public String model = "";    // 品牌 型号
        public int status = -1;      // 实时状态：0 关闭 / 1 已激活 / 10 运行中 / 99 空闲 …
        public int dtype = -1;

        /** 显示名：优先用户备注，其次设备名，最后编号。 */
        public String title() {
            if (memo != null && memo.length() > 0) {
                return memo;
            }
            if (name != null && name.length() > 0) {
                return name;
            }
            return id;
        }

        public String statusText() {
            return Api.statusName(status);
        }

        public boolean idle() {
            return status == 99 || status == 0;
        }

        public boolean busy() {
            return status == 10 || status == 1 || status == 20 || status == 30;
        }
    }

    /** 解析 master 返回里的 favos。 */
    public static java.util.List<WaterDevice> parseFavos(Resp r) {
        java.util.List<WaterDevice> out = new java.util.ArrayList<>();
        JSONObject data = r == null ? null : r.json().optJSONObject("data");
        if (data == null) {
            return out;
        }
        org.json.JSONArray arr = data.optJSONArray("favos");
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            WaterDevice d = new WaterDevice();
            d.id = o.optString("id", "");
            if (d.id.length() == 0) {
                continue;
            }
            d.name = o.optString("name", "");
            d.memo = o.optString("memo", "");
            JSONObject ep = o.optJSONObject("ep");
            d.site = ep == null ? "" : ep.optString("name", "");
            JSONObject bm = o.optJSONObject("bm");
            if (bm != null) {
                d.model = (bm.optString("brand", "") + " " + bm.optString("model", "")).trim();
                d.dtype = bm.optInt("dtype", -1);
            }
            // 实时状态优先取 gene.status（和 dev/status 用的同一个字段），
            // 拿不到再退回顶层 status
            JSONObject gene = o.optJSONObject("gene");
            int st = gene == null ? -1 : gene.optInt("status", -1);
            d.status = st >= 0 ? st : o.optInt("status", -1);
            out.add(d);
        }
        return out;
    }

    /** 798 的设备状态名（和 dev/status 的 gene.status 对齐）。 */
    public static String statusName(int s) {
        switch (s) {
            case 0: return "关闭";
            case 1: return "已激活";
            case 2: return "异常";
            case 10: return "运行中";
            case 20: return "冲洗中";
            case 30: return "准备中";
            case 98: return "已停用";
            // DeviceStatus.LOCKED(99)。它不是"运行中"，而是**待机可启动**：
            // 官方 p.x() 就是拿 status==99 判定"该显示开始按钮"，
            // r4/a.java 收到 status==99 也记 "启动设备"。
            case 99: return "待机";
            default: return s < 0 ? "—" : ("状态 " + s);
        }
    }

    /** 实时状态。 */
    public static Resp deviceStatus(String deviceId, String token) {
        return get("/api/v1/ui/app/dev/status?did=" + enc(deviceId) + "&more=true", token);
    }

    /**
     * 激活（等价于扫码）。
     *
     * ptype 是支付方式，取值来自 798 自己的 PayTypeInfo 列表：
     *   91 = 钱包支付（用钱包余额）
     *   21 = 支付宝免密支付（开通免密，先使用后付款） ← 默认用这个
     *   41 = 银联免密支付   93 = 一卡通支付   52 = 农行免密支付
     * 这里面**没有主动支付渠道**，全是后付型或余额型，所以取水天然是先享后付。
     */
    public static Resp start(String deviceId, int ptype, String token) {
        return start(deviceId, ptype, "{\"touch\":1}", token);
    }

    /**
     * 带 args 的激活。
     *
     * 官方 App 的 /dev/start 其实有 6 个参数（j4/e.java）：
     *     did, upgrade, ptype, args, rcp, cnt
     * 只发 did + ptype 是不够的，设备工作模式会整个漏掉。
     *
     * 对直饮水机（DeviceType.WATER_METER = 5）来说，args 是 {"touch":1}：
     * 见 DevControlHelper.m() 第 499~521 行 —— 扫码/按键进入设备详情页时，
     * 官方会把这个**一次性**标志塞进 args，含义是"用户人就在机器旁，
     * 用机器上的按键出水"。取水本来就是站在机器前按键取水，所以这里默认带上。
     */
    public static Resp start(String deviceId, int ptype, String args, String token) {
        StringBuilder u = new StringBuilder("/api/v1/dev/start?did=");
        u.append(enc(deviceId));
        u.append("&upgrade=true");          // 官方恒定传 true（DevControlHelper.F 第 409 行）
        u.append("&ptype=").append(ptype);
        u.append("&rcp=false");
        if (args != null && args.length() > 0) {
            u.append("&args=").append(enc(args));
        }
        return get(u.toString(), token);
    }

    /**
     * 结束本次用水。
     *
     * rcp = "receipt"。官方「结束使用」的调用链是
     *     DevControlHelper.i() → w() → DeviceViewModel.deviceEnd(id, rcp)
     * 而 rcp 的默认值是 **false**（合成方法 G/j 里 `bool = Boolean.FALSE`）。
     *
     * rcp=true 时只回一份回执快照 —— 反复调用会拿到**完全相同**的
     * 加密 data 串 —— 那更像"取一份回执快照"，而不是真正执行结束动作。
     * 现在按官方语义：动作走 rcp=false，回执另外用 receipt() 取。
     */
    public static Resp end(String deviceId, String token) {
        return end(deviceId, false, token);
    }

    public static Resp end(String deviceId, boolean rcp, String token) {
        return get("/api/v1/dev/end?did=" + enc(deviceId) + "&rcp=" + rcp, token);
    }

    /** 只取加密回执（结束动作请用 end(did, token)）。回执是密文，金额解不出来。 */
    public static Resp receipt(String deviceId, String token) {
        return end(deviceId, true, token);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * 是否是「登录状态已过期」。
     *
     * 慧生活798 用 code = -99 表示 token 失效，msg 一般是「登录状态已过期」；
     * 个别接口会返回 -1 或 401 之类的码，所以再兜一层文案匹配。
     */
    public static boolean isExpired(Resp r) {
        if (r == null || r.err != null) {
            return false;
        }
        JSONObject j = r.json();
        int code = j.optInt("code", 0);
        if (code == -99) {
            return true;
        }
        String msg = j.optString("msg", "");
        return msg.contains("登录状态已过期") || msg.contains("请重新登录")
                || msg.contains("登录失效") || msg.contains("登录已失效")
                || msg.contains("token失效") || msg.contains("token过期");
    }

    /**
     * 查免密协议（pga）状态。这是 798 判断「有没有开通支付宝免密」的接口。
     *
     * 返回 data = AccountSignData { sign, status, type, code, appId, bTime, cTime, id, lId, oId, uid, vTime }
     * type 就是 ptype（21 = 支付宝免密支付），status 表示协议是否有效。
     * eid 来自登录返回的 data.al.eid。
     */
    public static Resp pgaCheck(String eid, String token) {
        return get("/api/v1/ui/app/pga-check?eid=" + enc(eid) + "&isCombine=false", token);
    }

    /** 已签的免密协议列表。 */
    public static Resp pgaSignedList(String eid, String token) {
        return get("/api/v1/ui/app/acc-pgas?eid=" + enc(eid), token);
    }

    /** 从 pga-check 的返回里取 AccountSignData，取不到返回 null。 */
    public static JSONObject pgaData(Resp r) {
        return r == null ? null : r.json().optJSONObject("data");
    }

    /** ptype 名称（798 的 PayTypeInfo 列表原文）。 */
    public static String ptypeName(int t) {
        switch (t) {
            case 91: return "钱包支付";
            case 21: return "支付宝免密支付";
            case 41: return "银联免密支付";
            case 93: return "一卡通支付";
            case 52: return "农行免密支付";
            default: return "支付方式 " + t;
        }
    }

    // ---------------------------------------------------- 免密签约（pga）
    //
    // ！！注意：`/api/v1/ui/app/pga-check` 返回的 `type` / `status` 用的是
    // **PGAgreementType / PGAgreementStatus**，和 `/dev/start` 的 `ptype` 不是同一套枚举。
    // 早期版本拿 ptype 的「21 = 支付宝免密」去比 pga 的 type，而 pga 枚举里只有
    // 10 / 20 / 40 —— 21 永远不可能命中，于是**所有人**都被判成「未开通免密」，
    // 一直弹「建议开通支付宝免密支付」。
    // 枚举原文来自反编译的
    // com.ilife.lib.coremodel.data.enums.PGAgreement{Type,Status}。
    public static final int PGA_WECHAT = 10;
    public static final int PGA_ALIPAY = 20;
    public static final int PGA_UNIONPAY = 40;

    public static final int PGA_ST_PREPARE = 0;   // 准备申请
    public static final int PGA_ST_PENDING = 1;   // 审核中（需等待）
    public static final int PGA_ST_NORMAL = 2;    // 正常
    public static final int PGA_ST_STOP = 3;      // 暂停
    public static final int PGA_ST_TEMP = 4;      // 暂时

    public static String pgaTypeName(int t) {
        switch (t) {
            case PGA_WECHAT: return "微信";
            case PGA_ALIPAY: return "支付宝";
            case PGA_UNIONPAY: return "云闪付";
            default: return "未知渠道 " + t;
        }
    }

    public static String pgaStatusName(int s) {
        switch (s) {
            case PGA_ST_PREPARE: return "准备申请";
            case PGA_ST_PENDING: return "审核中";
            case PGA_ST_NORMAL: return "正常";
            case PGA_ST_STOP: return "暂停";
            case PGA_ST_TEMP: return "暂时";
            case 5: return "无需签约";
            case 6: return "按需签约";
            case 7: return "强制签约";
            default: return "状态 " + s;
        }
    }

    /** 从登录返回里取字段。 */
    public static String pick(JSONObject root, String key) {        try {
            JSONObject d = root.optJSONObject("data");
            if (d == null) {
                return null;
            }
            JSONObject al = d.optJSONObject("al");
            if (al == null) {
                return null;
            }
            String v = al.optString(key, null);
            return (v == null || v.length() == 0) ? null : v;
        } catch (Exception e) {
            return null;
        }
    }
}
