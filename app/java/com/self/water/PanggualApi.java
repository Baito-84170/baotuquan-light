package com.self.water;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 胖乖生活（洗澡）的接口客户端。
 *
 * 协议要点（逆向所得）：
 *   - 表单提交 application/x-www-form-urlencoded
 *   - 请求头 sign = SHA256("appSecret=<密钥>&channel=android_app&timestamp=<ts>&token=<tk>&version=<ver>&<路径>")
 *   - 解锁/下单类接口额外带 orderRiskSign（密钥同上，另含 imei/categoryCode/lat/lng）
 *   - 请求体里自动附加 token
 */
public final class PanggualApi {

    public static final String BASE = "https://userapi.qiekj.com";
    private static final String SECRET = "nFU9pbG8YQoAe1kFh+E7eyrdlSLglwEJeA0wwHB1j5o=";
    private static final String APP_VERSION = "1.143.0";
    private static final String APP_VERSION_CODE = "290";
    private static final String PKG = "com.qiekj.user";
    private static final String CHANNEL = "android_app";

    private static final int TIMEOUT = 15000;

    private PanggualApi() {
    }

    // ------------------------------------------------------------ 结果

    public static final class Resp {
        public int http;
        public String body;
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

        /** 服务端业务码，0 表示成功。 */
        public int code() {
            return json().optInt("code", -999);
        }

        public String msg() {
            return json().optString("msg", body);
        }
    }

    // ------------------------------------------------------------ 签名

    /**
     * 是否是「未登录 / 登录过期」。
     *
     * 胖乖生活经验证返回 {"code":2,"msg":"未登录"}；有的老接口用 code = 401，
     * 所以文案也一起兜。
     */
    public static boolean isExpired(Resp r) {
        if (r == null || r.err != null) {
            return false;
        }
        JSONObject j = r.json();
        int code = j.optInt("code", 0);
        if (code == 2 || code == 401) {
            return true;
        }
        String msg = j.optString("msg", "");
        return msg.contains("未登录") || msg.contains("登录已过期")
                || msg.contains("登录过期") || msg.contains("token失效")
                || msg.contains("请重新登录") || msg.contains("授权失效");
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                String h = Integer.toHexString(b & 0xFF);
                if (h.length() == 1) {
                    sb.append('0');
                }
                sb.append(h);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String reqSign(String path, String token, String ts) {
        return sha256Hex("appSecret=" + SECRET
                + "&channel=" + CHANNEL
                + "&timestamp=" + ts
                + "&token=" + (token == null ? "" : token)
                + "&version=" + APP_VERSION
                + "&" + path);
    }

    private static String riskSign(String path, String token, String ts,
                                   String imei, String categoryCode,
                                   String lat, String lng) {
        return sha256Hex("appSecret=" + SECRET
                + "&categoryCode=" + categoryCode
                + "&channel=" + CHANNEL
                + "&imei=" + imei
                + "&lat=" + lat
                + "&lng=" + lng
                + "&timestamp=" + ts
                + "&token=" + (token == null ? "" : token)
                + "&version=" + APP_VERSION
                + "&" + path);
    }

    // ------------------------------------------------------------ 请求

    /** 风控参数：解锁 / 下单类接口需要。 */
    public static final class Risk {
        public final String imei;
        public final String categoryCode;
        public final String lat;
        public final String lng;

        public Risk(String imei, String categoryCode, String lat, String lng) {
            this.imei = imei;
            this.categoryCode = categoryCode;
            this.lat = lat;
            this.lng = lng;
        }
    }

    public static Resp post(String path, Map<String, String> fields,
                            String token, Risk risk) {
        Resp r = new Resp();
        HttpURLConnection c = null;
        String reqForLog = "";
        try {
            String ts = String.valueOf(System.currentTimeMillis());

            Map<String, String> body = new LinkedHashMap<>();
            if (fields != null) {
                body.putAll(fields);
            }
            if (token != null && token.length() > 0) {
                body.put("token", token);
            }

            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : body.entrySet()) {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                  .append('=')
                  .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
            }

            URL url = new URL(BASE + path);
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded;charset=UTF-8");
            c.setRequestProperty("Authorization", token == null ? "" : token);
            c.setRequestProperty("Version", APP_VERSION);
            c.setRequestProperty("channel", CHANNEL);
            c.setRequestProperty("phoneBrand", android.os.Build.BRAND);
            c.setRequestProperty("deviceId", "android");
            c.setRequestProperty("timestamp", ts);
            c.setRequestProperty("sign", reqSign(path, token, ts));
            c.setRequestProperty("User-Agent",
                    "QEUser/" + APP_VERSION + " (" + PKG + "; build:" + APP_VERSION_CODE
                            + "; Android " + android.os.Build.VERSION.RELEASE
                            + "; userChannel:" + CHANNEL + "; version:" + APP_VERSION + ")");

            if (risk != null) {
                c.setRequestProperty("imei", risk.imei);
                c.setRequestProperty("categoryCode", risk.categoryCode);
                c.setRequestProperty("lat", risk.lat);
                c.setRequestProperty("lng", risk.lng);
                c.setRequestProperty("orderRiskSign",
                        riskSign(path, token, ts, risk.imei, risk.categoryCode,
                                risk.lat, risk.lng));
                c.setRequestProperty("orderRiskTimestamp", ts);
            }

            OutputStream os = c.getOutputStream();
            os.write(sb.toString().getBytes("UTF-8"));
            os.flush();
            os.close();
            reqForLog = sb.toString();

            r.http = c.getResponseCode();
            InputStream is = (r.http >= 200 && r.http < 400)
                    ? c.getInputStream() : c.getErrorStream();
            r.body = is == null ? "" : new String(readAll(is), "UTF-8");
        } catch (Exception e) {
            r.err = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        // 落盘日志：MIUI 会吞掉 Log.d/Log.i，但 adb 能读 App 外部目录，
        // 所以直接写文件，用 adb pull 拿。token 打码。
        try {
            String req = maskToken(reqForLog);
            String resp = r.err != null ? ("ERR " + r.err)
                    : (r.body == null ? "" : r.body);
            lastBody = resp;
        } catch (Exception ignored) {
        }
        return r;
    }

    private static final String TAG = "PGAPI";

    /** 最近一次接口的原始返回。给调试页面显示用 —— 拿不到 logcat 时靠它。 */
    public static volatile String lastBody = "";

    private static String maskToken(String s) {
        try {
            return s.replaceAll("token=[^&\\s]*", "token=****");
        } catch (Exception e) {
            return s;
        }
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

    private static Map<String, String> map(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        }
        return m;
    }

    // ------------------------------------------------------------ 业务接口

    /** 是否需要图形验证码。 */
    public static Resp isNeedCaptcha(String phone) {
        return post("/common/isNeedCaptcha", map("phone", phone), null, null);
    }

    /** 发送短信验证码。template 经验证用 "1"。 */
    public static Resp sendSms(String phone) {
        return post("/common/sms/sendCode", map("phone", phone, "template", "1"), null, null);
    }

    /** 登录（手机号 + 短信验证码）。成功时 data.token 可用。 */
    public static Resp login(String phone, String code) {
        return post("/user/reg",
                map("channel", CHANNEL, "phone", phone, "verify", code), null, null);
    }

    /** 扫码：SN 来自机器二维码。 */
    public static Resp scanV2(String sn, String token) {
        return post("/goods/scan/v2", map("SN", sn), token, null);
    }

    /** 设备详情。 */
    public static Resp goodsDetails(String goodsId, String token) {
        return post("/goods/normal/details", map("goodsId", goodsId), token, null);
    }

    /** SKU 列表。 */
    public static Resp goodsSkus(String goodsId, String token) {
        return post("/goods/normal/skus", map("goodsId", goodsId), token, null);
    }

    /** 由 SN 解析出设备（扫码 → 详情 → SKU 三步合一）。 */
    /**
     * 「有未支付订单」拦截（服务端 code 2128）。
     *
     * 为什么单独做一个异常：被拦时 `/goods/normal/skus` 的 data 是一个**对象**
     * （带 incompleteOrderId），不是正常的数组，所以 skuId 会是空的；继续走下去
     * `/trade/preview` 只会返回 code -1 而且 msg 为 null —— 用户看到的就是一句
     * 没头没脑的「预览失败」。把订单号抛出来，界面才能给一条走得通的路。
     */
    public static final class UnpaidOrderException extends Exception {
        public final String orderId;

        public UnpaidOrderException(String orderId) {
            super("有未支付订单需要完成");
            this.orderId = orderId == null ? "" : orderId;
        }
    }

    public static JSONObject resolveDevice(String sn, String token) throws Exception {
        JSONObject out = new JSONObject();

        // 先按 SN 走扫码接口
        Resp scan = scanV2(sn, token);
        JSONObject scanData = scan.json().optJSONObject("data");

        String goodsId;
        String categoryCode;
        String categoryName;
        if (scanData != null) {
            goodsId = scanData.optString("id", "");
            categoryCode = scanData.optString("categoryCode", "");
            categoryName = scanData.optString("categoryName", "");
        } else {
            // 扫码失败时，把这个编号**当成 goodsId** 再试一次。
            //
            // 这不是兜底猜测：设备库里的洗澡设备有两种来源 ——
            //   1. 扫码加的，id 是机器 SN；
            //   2. 从「按列表添加」加的（机器二维码印在低分辨率点阵 LCD 上，很难扫），
            //      id 直接就是 goodsId。
            // 两种情况都得能解析，所以这里退回用详情接口。
            goodsId = sn;
            categoryCode = "";
            categoryName = "";
        }
        if (goodsId.length() == 0) {
            throw new Exception("扫码失败：" + scan.msg());
        }
        out.put("goodsId", goodsId);
        out.put("categoryCode", categoryCode);
        out.put("categoryName", categoryName);

        Resp det = goodsDetails(goodsId, token);
        JSONObject d = det.json().optJSONObject("data");
        if (d == null) {
            throw new Exception("查询设备失败：" + det.msg());
        }
        if (categoryCode.length() == 0) {
            // 从详情里补上分类码（解锁的风控头要用）
            String cc = d.optString("categoryCode", "");
            if (cc.length() > 0) {
                out.put("categoryCode", cc);
            }
        }
        out.put("goodsName", d.optString("name", ""));
        out.put("orgId", d.optString("orgId", ""));
        out.put("orgName", d.optString("orgName", ""));
        out.put("imei", d.optString("imei", ""));
        out.put("shopId", d.optString("shopId", ""));
        out.put("positionId", d.optString("positionId", ""));
        out.put("isBluetooth", d.optInt("isBluetooth", 0));
        out.put("payment", d.optString("payment", ""));
        out.put("supportAppUserFinish", d.optBoolean("supportAppUserFinish", false));

        Resp sku = goodsSkus(goodsId, token);
        if (isUnpaidBlock(sku)) {
            throw new UnpaidOrderException(incompleteOrderId(sku));
        }
        org.json.JSONArray arr = sku.json().optJSONArray("data");
        if (arr != null && arr.length() > 0) {
            JSONObject first = arr.optJSONObject(0);
            if (first != null) {
                out.put("skuId", first.optString("skuId", ""));
                out.put("skuName", first.optString("name", ""));
                out.put("skuPrice", first.optString("price", ""));
            }
        }
        return out;
    }

    /** 订单预览。itemsJson 由调用方按 OrderPreviewQuest 结构构造。 */
    public static Resp tradePreview(String orgId, String goodsId, String shopId,
                                    String categoryCode, String imei,
                                    String itemsJson, String token, Risk risk) {
        return post("/trade/preview/v4", map(
                "orgId", orgId,
                "goodsId", goodsId,
                "shopId", shopId,
                "categoryCode", categoryCode,
                "imei", imei,
                "items", itemsJson), token, risk);
    }

    /** 下单。 */
    public static Resp tradeCreate(String orgId, String goodsId, String shopId,
                                   String categoryCode, String imei,
                                   String itemsJson, String token, Risk risk) {
        return post("/trade/create", map(
                "orgId", orgId,
                "goodsId", goodsId,
                "shopId", shopId,
                "categoryCode", categoryCode,
                "imei", imei,
                "items", itemsJson), token, risk);
    }

    /** 解锁出水。 */
    public static Resp showerUnlock(String skuId, String promotions,
                                    String payType, String iotBOrderNo,
                                    String token, Risk risk) {
        Map<String, String> f = map("skuId", skuId, "promotions", promotions);
        if (payType != null && payType.length() > 0) {
            f.put("payType", payType);
        }
        if (iotBOrderNo != null && iotBOrderNo.length() > 0) {
            f.put("iotBOrderNo", iotBOrderNo);
        }
        return post("/goods/shower/unlock", f, token, risk);
    }

    /** 结束（洗澡）：字段名经验证为 goodsId + orderNo，orderNo 来自解锁响应。 */
    public static Resp showerEnd(String goodsId, String orderNo, String token) {
        return post("/shower/goods/remoteEnd", map(
                "goodsId", goodsId == null ? "" : goodsId,
                "orderNo", orderNo == null ? "" : orderNo), token, null);
    }

    /**
     * 撤销一笔已创建但还没开始的订单。
     *
     * 字段名经验证为 orderId（来自 trade/create 的返回）。
     * 这是「下单成功但解锁失败」时唯一正确的收尾动作——留着不管会被结算。
     */
    public static Resp orderCancel(String orderId, String token) {
        return post("/order/cancel", map("orderId", orderId == null ? "" : orderId), token, null);
    }

    /** 当前进行中的洗澡订单（用于判断是否已在用）。返回 data.lastOrderNo。 */
    public static Resp lastOrder(String goodsId, String token) {
        return post("/shower/goods/lastOrder", map("goodsId", goodsId), token, null);
    }

    // ---------------------------------------------------- 支付

    /**
     * 预支付：拿到拉起支付渠道需要的参数（data.prepayParam）。
     *
     * 胖乖生活是「先用后付」——门开了、洗完了才结算，所以这个调用发生在
     * 结束洗澡**之后**，不是下单时。官方顺序：
     *     remoteEnd → order/afterPay/creating → payChannelRoute/againPay
     *              → pay/unify/prePay{orderNo,payType} → 拉起支付
     */
    public static Resp prePay(String orderNo, int payType, String token, Risk risk) {
        return post("/pay/unify/prePay", map(
                "orderNo", orderNo == null ? "" : orderNo,
                "payType", String.valueOf(payType)), token, risk);
    }

    /** 钱包余额支付（payType 恒为 2）。 */
    public static Resp walletPay(String orderNo, String token, Risk risk) {
        return post("/pay/unify/pay", map(
                "orderNo", orderNo == null ? "" : orderNo,
                "payType", String.valueOf(PAY_WALLET)), token, risk);
    }

    /** 查订单是否已支付。返回 data 为 boolean。 */
    public static Resp payStatus(String orderNo, int payType, String token) {
        return post("/order/pay_status", map(
                "orderNo", orderNo == null ? "" : orderNo,
                "payType", String.valueOf(payType)), token, null);
    }

    /** 取 prepayParam（交给支付渠道处理的串）。 */
    public static String pickPrepayParam(Resp r) {
        JSONObject j = r == null ? null : r.json();
        JSONObject d = j == null ? null : j.optJSONObject("data");
        return d == null ? "" : d.optString("prepayParam", "");
    }

    /**
     * code 2128「有未支付订单需要完成」时，服务端会带回堵住的那笔订单号。
     * 这是继续下单的唯一出路 —— 先把它付掉。
     */
    public static String incompleteOrderId(Resp r) {
        JSONObject j = r == null ? null : r.json();
        if (j == null || j.optInt("code", 0) != 2128) {
            return "";
        }
        JSONObject d = j.optJSONObject("data");
        if (d == null) {
            return "";
        }
        String id = d.optString("incompleteOrderId", "");
        return id.length() == 0 ? d.optString("incompleteOrderNo", "") : id;
    }

    /** 是不是「有未支付订单」的拦截。 */
    public static boolean isUnpaidBlock(Resp r) {
        JSONObject j = r == null ? null : r.json();
        return j != null && j.optInt("code", 0) == 2128;
    }

    /** 钱包余额。返回 data.balance。 */
    public static Resp userBalance(String token) {
        return post("/user/balance", null, token, null);
    }

    // ------------------------------------------------------------ 支付方式
    //
    // payType 取值来自反编译出来的 PayTypeEnum，不是猜的：
    //   2   WALLET                 钱包          —— 需要钱包里有钱，经验证余额 0.00 时不可用
    //   13  ALIPAY                 支付宝        —— 主动支付
    //   15  ALIPAY_NOT_PASSWORD    支付宝免密    —— 需要先签约，未签约时不在可用列表里
    //   32  WECHAT                 微信
    //   53  CUP_UNIONPAY           云闪付无感支付
    // 别再把 2 当成"默认值"——它是钱包，而钱包通常不在可用列表里。

    public static final int PAY_WALLET = 2;
    public static final int PAY_ALIPAY = 13;
    public static final int PAY_ALIPAY_NOPWD = 15;
    public static final int PAY_WECHAT = 32;

    /** 一个可用的支付方式。 */
    public static final class PayOption {
        public int payType;
        public String name = "";
        public boolean isDefault;
    }

    /** 这台设备的可用支付方式。data 是数组，元素形如 {payType, payTypeName, defaultFlag}。 */
    public static List<PayOption> availablePayTypes(String goodsId, String shopId, String orgId,
                                                    String imei, String categoryCode,
                                                    String token) {
        List<PayOption> out = new ArrayList<>();
        Resp r = post("/pay/ticket/getAvailablePayTypeList", map(
                "goodsId", goodsId,
                "shopId", shopId,
                "orgId", orgId,
                "imei", imei,
                "categoryCode", categoryCode), token, null);
        org.json.JSONArray arr = r.json().optJSONArray("data");
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            PayOption p = new PayOption();
            p.payType = o.optInt("payType", -1);
            p.name = o.optString("payTypeName", "");
            p.isDefault = o.optInt("defaultFlag", 0) == 1;
            if (p.payType > 0) {
                out.add(p);
            }
        }
        return out;
    }

    /** 默认支付方式：优先 defaultFlag，其次第一个，都没有就退回支付宝。 */
    public static int defaultPayType(List<PayOption> list) {
        if (list != null && !list.isEmpty()) {
            for (PayOption p : list) {
                if (p.isDefault) {
                    return p.payType;
                }
            }
            return list.get(0).payType;
        }
        return PAY_ALIPAY;
    }

    /** 根据 payType 找名字。 */
    public static String payTypeName(List<PayOption> list, int payType) {
        if (list != null) {
            for (PayOption p : list) {
                if (p.payType == payType) {
                    return p.name;
                }
            }
        }
        return knownPayTypeName(payType);
    }

    private static String knownPayTypeName(int t) {
        switch (t) {
            case PAY_WALLET: return "钱包";
            case PAY_ALIPAY: return "支付宝";
            case PAY_ALIPAY_NOPWD: return "支付宝免密";
            case PAY_WECHAT: return "微信";
            case 53: return "云闪付无感支付";
            case 52: return "银联支付";
            default: return "支付方式 " + t;
        }
    }

    /** 我的订单列表。displayTag 传 "0" 表示不加筛选（返回全部）。 */
    public static Resp orderList(String page, String pageSize, String displayTag, String token) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("page", page);
        f.put("pageSize", pageSize);
        if (displayTag != null && displayTag.length() > 0 && !"0".equals(displayTag)) {
            f.put("displayTag", displayTag);
        }
        return post("/order/list", f, token, null);
    }

    /** 一条订单里我们关心的字段。 */
    public static final class Order {
        public String orderNo = "";
        public String createTime = "";
        public String payTime = "";
        public String payPrice = "";
        public String markPrice = "";
        public String machineName = "";
        public String shopName = "";
        public int orderStatus = -1;
        public int payType = -1;
        public int markMinutes;
        public int addMinutes;
        /** 原始 JSON，一字不改。判断不准的时候靠它，别靠猜字段。 */
        public String raw = "";
    }

    /**
     * 解析订单列表。
     *
     * ItemsList 的具体包裹字段名没核实过，所以把几种常见形状都兜住 ——
     * 与其猜一个键名然后静默返回空列表，不如都试一遍。
     */
    public static List<Order> parseOrders(Resp r) {
        List<Order> out = new ArrayList<>();
        JSONObject root = r == null ? new JSONObject() : r.json();
        org.json.JSONArray arr = null;
        Object d = root.opt("data");
        if (d instanceof org.json.JSONArray) {
            arr = (org.json.JSONArray) d;
        } else {
            JSONObject dj = root.optJSONObject("data");
            if (dj != null) {
                for (String k : new String[]{"list", "items", "records", "rows", "data"}) {
                    org.json.JSONArray a = dj.optJSONArray(k);
                    if (a != null) {
                        arr = a;
                        break;
                    }
                }
            }
        }
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Order od = new Order();
            od.orderNo = o.optString("orderNo", "");
            od.createTime = o.optString("createTime", "");
            od.payTime = o.optString("payTime", "");
            od.payPrice = o.optString("payPrice", "");
            od.markPrice = o.optString("markPrice", "");
            od.machineName = o.optString("machineName", "");
            od.shopName = o.optString("shopName", "");
            od.orderStatus = o.optInt("orderStatus", -1);
            od.payType = o.optInt("payType", -1);
            od.markMinutes = o.optInt("markMinutes", 0);
            od.addMinutes = o.optInt("addMinutes", 0);
            od.raw = o.toString();
            out.add(od);
        }
        return out;
    }

    // ------------------------------------------------------------ 按列表找机器
    //
    // 为什么需要这个：洗澡机器的二维码印在一块**低分辨率点阵 LCD** 上
    // （"请扫码"三个字都是点阵拼的），模块只有几个像素、
    // 还是小圆点状 —— 这种码天生难扫，官方 App 也未必好扫。
    //
    // 但胖乖有接口能**直接列出这个商户下的所有机器**，每个还带
    // `deviceWorkStatus`：10 = 空闲，20 = 使用中（从官方 UI 代码里读到的）。
    // 所以完全可以不扫码：列出机器 → 按名字选 → 加入设备库。

    /** 一栋楼。注意：这里的 orgId 是**楼栋 id**，不是商户的 shopAutoId。 */
    public static final class Building {
        public String orgId = "";
        public String name = "";

        public String label() {
            return name.length() > 0 ? name : orgId;
        }
    }

    /**
     * 商户下的楼栋列表：POST appointNew/point/listByShopId，字段 shopId + machineTypeId。
     *
     * ⚠️ 这个接口揭示了 `orgId` 有**两个不同含义**，踩过一次：
     *   - 商户的 autoId（机器详情里的 shopAutoId，比如 200130814）
     *   - **楼栋 id**（这里返回的，3号楼/4号楼/5号楼/6号楼）
     * `appointNew/near/newMachines` 要的是**楼栋 id**。
     * 传错的话服务端不会报错，只会返回 code 0 + 空的 items —— 极难察觉。
     */
    public static List<Building> buildings(String shopId, String machineTypeId, String token) {
        List<Building> out = new ArrayList<>();
        Map<String, String> f = new LinkedHashMap<>();
        f.put("shopId", shopId);
        f.put("machineTypeId", machineTypeId);
        Resp r = post("/appointNew/point/listByShopId", f, token, null);
        Object d = r.json().opt("data");
        if (!(d instanceof org.json.JSONArray)) {
            return out;
        }
        org.json.JSONArray arr = (org.json.JSONArray) d;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String id = o.optString("orgId", "");
            if (id.length() == 0) {
                continue;
            }
            Building b = new Building();
            b.orgId = id;
            b.name = o.optString("orgName", "");
            out.add(b);
        }
        return out;
    }

    /** 机器类型（淋浴 / 吹风机 …）。 */
    public static final class MachineType {
        public String id = "";
        public String name = "";
    }

    /** 一台机器。 */
    public static final class Machine {
        public String goodsId = "";
        public String name = "";
        public int workStatus = -1;   // 10 空闲 / 20 使用中
        public int isReserve;

        public boolean idle() {
            return workStatus == 10;
        }

        public boolean busy() {
            return workStatus == 20;
        }

        public String statusText() {
            if (idle()) {
                return "空闲";
            }
            if (busy()) {
                return "使用中";
            }
            return workStatus < 0 ? "—" : ("状态 " + workStatus);
        }
    }

    /** 机器类型列表：POST appointNew/newNearByList，字段 shopId。 */
    public static List<MachineType> machineTypes(String shopId, String token) {
        List<MachineType> out = new ArrayList<>();
        Resp r = post("/appointNew/newNearByList", map("shopId", shopId), token, null);
        org.json.JSONArray arr = findArray(r.json());
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String id = o.optString("machineTypeId", "");
            if (id.length() == 0) {
                continue;
            }
            MachineType t = new MachineType();
            t.id = id;
            t.name = o.optString("machineTypeName", "");
            out.add(t);
        }
        return out;
    }

    /**
     * 某个类型下的机器：POST appointNew/near/newMachines，
     * 字段 goodsPage(页码) / pageSize / shopId / machineTypeId / orgId。
     */
    public static List<Machine> pointMachines(String shopId, String machineTypeId, String orgId,
                                              int page, int pageSize, String token) {
        List<Machine> out = new ArrayList<>();
        Map<String, String> f = new LinkedHashMap<>();
        f.put("goodsPage", String.valueOf(page));
        f.put("pageSize", String.valueOf(pageSize));
        f.put("shopId", shopId);
        f.put("machineTypeId", machineTypeId);
        f.put("orgId", orgId);
        Resp r = post("/appointNew/near/newMachines", f, token, null);
        org.json.JSONArray arr = findArray(r.json());
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String gid = o.optString("goodsId", "");
            if (gid.length() == 0) {
                continue;
            }
            Machine m = new Machine();
            m.goodsId = gid;
            m.name = o.optString("goodsName", "");
            m.workStatus = o.optInt("deviceWorkStatus", -1);
            m.isReserve = o.optInt("isReserve", 0);
            out.add(m);
        }
        return out;
    }

    /**
     * 从返回里找出"装着机器的那一层数组"。
     *
     * 这些接口的包裹形状（ItemsList 里到底是 list / items / records）我没核实过，
     * 与其猜一个键名然后静默返回空列表，不如在 data 里递归找**元素带 goodsId
     * 或 machineTypeId 的数组**。找不到就返回 null，调用方会把原始返回摊出来。
     */
    public static org.json.JSONArray findArray(JSONObject root) {
        Object data = root == null ? null : root.opt("data");
        if (data instanceof org.json.JSONArray) {
            return (org.json.JSONArray) data;
        }
        if (!(data instanceof JSONObject)) {
            return null;
        }
        return search((JSONObject) data, 0);
    }

    private static org.json.JSONArray search(JSONObject o, int depth) {
        if (depth > 4) {
            return null;
        }
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.opt(k);
            if (v instanceof org.json.JSONArray) {
                org.json.JSONArray a = (org.json.JSONArray) v;
                if (a.length() > 0) {
                    JSONObject first = a.optJSONObject(0);
                    if (first != null && (first.has("goodsId") || first.has("machineTypeId"))) {
                        return a;
                    }
                }
            } else if (v instanceof JSONObject) {
                org.json.JSONArray hit = search((JSONObject) v, depth + 1);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** 判断一句错误文案是不是「登录过期」。异常信息拿不到结构化字段时用它。 */
    public static boolean isExpiredMsg(String msg) {
        if (msg == null) {
            return false;
        }
        return msg.contains("未登录") || msg.contains("登录已过期")
                || msg.contains("登录过期") || msg.contains("请重新登录")
                || msg.contains("登录失效") || msg.contains("token");
    }

    /**
     * 只读探测：按给定端点发一个 POST，body 里自动补 token。
     *
     * 仅供诊断页使用。**只能传只读端点**——这个方法是通用的，
     * 它不会替你判断端点有没有副作用。
     */
    public static Resp probe(String path, Map<String, String> fields, String token) {
        return post(path, fields, token, null);
    }

    // ------------------------------------------------------------ 返回字段提取

    /**
     * trade/create 的返回：{@code data = {orderId, tradeNo, tradeType}}。
     * 经验证字段名就是 orderId —— 不是 orderNo，也不是 iotBOrderNo。
     */
    public static String pickOrderId(Resp r) {
        JSONObject d = r == null ? null : r.json().optJSONObject("data");
        return d == null ? "" : d.optString("orderId", "");
    }

    /**
     * goods/shower/unlock 的返回：{@code data = {msgId, orderNo}}。
     * 结束要用的就是这个 orderNo，它只有在解锁成功后才拿得到。
     */
    public static String pickUnlockOrderNo(Resp r) {
        JSONObject d = r == null ? null : r.json().optJSONObject("data");
        return d == null ? "" : d.optString("orderNo", "");
    }

    /** shower/goods/lastOrder 的返回：{@code data.lastOrderNo}。空串表示没有在途订单。 */
    public static String pickLastOrderNo(Resp r) {
        JSONObject d = r == null ? null : r.json().optJSONObject("data");
        return d == null ? "" : d.optString("lastOrderNo", "");
    }

    /** 设备健康状态：data.health / data.faultDesc。 */
    public static Resp checkDeviceStatus(String goodsId, String token) {
        return post("/trade/check_device_status", map("goodsId", goodsId), token, null);
    }
}
