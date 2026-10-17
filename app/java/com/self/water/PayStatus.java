package com.self.water;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;

import org.json.JSONObject;

/**
 * 「开通免密支付」这件事的统一处理。
 *
 * 为什么值得反复提醒：两个平台都是**先使用后付款**。
 *   - 开了免密 → 结算时自动扣款，用户全程无感，不会出现「待支付」。
 *   - 没开免密 → 结算时扣不动，订单掉进「待支付」，用户得事后自己去找、去补付。
 * 也就是说，免密不是一个可选的小优化，它直接决定会不会留下欠款单。
 *
 * 提醒策略（按需求）：**每次都弹**，直到满足下面任一条件为止：
 *   1. 免密已经开通（那就不用提醒了）；
 *   2. 用户点了「不再提示」（按服务分开记，在一边点过不影响另一边）。
 *
 * 判断「有没有开免密」用的都是只读接口，不会产生订单：
 *   - 胖乖：pay/ticket/getAvailablePayTypeList 里有没有 payType=15（支付宝免密）
 *   - 798 ：ui/app/pga-check 的 AccountSignData.type / status
 */
public final class PayStatus {

    public static final String SVC_WATER = "water";
    public static final String SVC_SHOWER = "shower";

    /** 检测结果。三态，因为「查不到」和「确认没开」必须区分开。 */
    public static final int UNKNOWN = 0;   // 还没查 / 查失败
    public static final int YES = 1;       // 免密可用
    public static final int NO = 2;        // 确认未开通

    private PayStatus() {
    }

    /** 一个服务的免密状态。 */
    public static final class Status {
        public int nopwd = UNKNOWN;
        public String detail = "";       // 给用户看的一句话
        public String raw = "";          // 原始返回，便于排查
    }

    // ---------------------------------------------------- 查询

    /** 胖乖：从已经拿到的支付方式列表推免密状态（不再多发一次请求）。 */
    public static Status fromOptions(java.util.List<PanggualApi.PayOption> list) {
        return merge(null, null, null, null, list);
    }

    /**
     * 胖乖：综合判断。
     *
     * **重要更正**：`getAvailablePayTypeList` 是「**这台机器**接受哪些支付方式」，
     * 是机器/商户维度的；而「**这个用户**有没有开通免密」是账号维度的。
     * 早期版本拿机器列表里有没有 15 来当作用户签约状态 —— 那是错的：
     * 用户开通免密之后，机器列表很可能依然是 [13, 32]，于是提示永远弹。
     *
     * 现在用三个独立信号一起判断，并且**读不到账号级信号时不再断言「未开通」**：
     *   1. `alipay/isSign`   —— 支付宝签约
     *   2. `unionpay/isSign` —— 云闪付无感签约
     *   3. `alipay/signDetail` 有数据
     *   4. 机器可用渠道里出现 15（支付宝免密）—— 有就是有
     */
    public static Status checkShower(String token, java.util.List<PanggualApi.PayOption> preFetched,
                                     String goodsId, String shopId, String orgId,
                                     String imei, String categoryCode) {
        Boolean alipay = boolData(PanggualApi.probe("/alipay/isSign", null, token));
        Boolean union = boolData(PanggualApi.probe("/unionpay/isSign", null, token));
        boolean signDetailHasData = false;
        PanggualApi.Resp sd = PanggualApi.probe("/alipay/signDetail", null, token);
        if (sd != null && sd.err == null && sd.code() == 0) {
            JSONObject d = sd.json().optJSONObject("data");
            signDetailHasData = d != null && d.length() > 0;
        }
        java.util.List<PanggualApi.PayOption> list = preFetched != null
                ? preFetched
                : PanggualApi.availablePayTypes(goodsId, shopId, orgId, imei, categoryCode, token);
        // signDetail 只有在「确实拿到了签约数据」时才算一个信号；
        // code=-1 / data=null 只能说明读不到，不能当成"确认未开通"
        return merge(alipay, union, signDetailHasData ? Boolean.TRUE : null, null, list);
    }

    private static Status merge(Boolean alipay, Boolean union, Boolean signDetail,
                                String ignored, java.util.List<PanggualApi.PayOption> list) {
        Status s = new Status();
        boolean listHasNopwd = false;
        boolean listHasAlipay = false;
        StringBuilder sb = new StringBuilder();
        if (list != null) {
            for (PanggualApi.PayOption p : list) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                sb.append(p.name);
                if (p.payType == PanggualApi.PAY_ALIPAY_NOPWD) {
                    listHasNopwd = true;
                }
                if (p.payType == PanggualApi.PAY_ALIPAY) {
                    listHasAlipay = true;
                }
            }
        }

        boolean anyTrue = Boolean.TRUE.equals(alipay) || Boolean.TRUE.equals(union)
                || Boolean.TRUE.equals(signDetail) || listHasNopwd;
        // 账号级信号读到了吗（哪怕值是 false）
        boolean accountSignalKnown = alipay != null || union != null || signDetail != null;

        if (anyTrue) {
            s.nopwd = YES;
        } else if (accountSignalKnown) {
            s.nopwd = NO;
        } else {
            // 只有机器列表，说明不了用户签约状态 —— 宁可不断言
            s.nopwd = UNKNOWN;
        }

        StringBuilder d = new StringBuilder();
        d.append("支付宝签约=").append(alipay == null ? "读不到" : String.valueOf(alipay));
        d.append("，云闪付签约=").append(union == null ? "读不到" : String.valueOf(union));
        if (list != null && list.size() > 0) {
            d.append("\n这台机器的可用渠道：").append(sb);
        }
        s.detail = d.toString();
        s.raw = s.detail;
        return s;
    }

    /** 把 data 字段当布尔读；不是布尔或请求失败都返回 null（表示"读不到"）。 */
    private static Boolean boolData(PanggualApi.Resp r) {
        if (r == null || r.err != null) {
            return null;
        }
        JSONObject j = r.json();
        if (j.optInt("code", -1) != 0 || !j.has("data")) {
            return null;
        }
        Object d = j.opt("data");
        return (d instanceof Boolean) ? (Boolean) d : null;
    }

    /** 798：查 pga 协议状态。 */
    public static Status checkWater(String token, String eid) {
        Status s = new Status();
        if (eid == null || eid.length() == 0) {
            s.nopwd = UNKNOWN;
            s.detail = "本地没有 eid，无法查询免密状态（重新登录一次即可补上）";
            return s;
        }
        Api.Resp r = Api.pgaCheck(eid, token);
        if (!r.ok()) {
            s.nopwd = UNKNOWN;
            s.detail = "查询失败：" + (r.err != null ? r.err : ("HTTP " + r.http));
            s.raw = r.body == null ? "" : r.body;
            return s;
        }
        s.raw = r.body;
        JSONObject d = Api.pgaData(r);
        if (d == null) {
            s.nopwd = UNKNOWN;
            s.detail = "服务端没返回签约数据";
            return s;
        }
        int type = d.optInt("type", -1);
        int status = d.optInt("status", -1);
        String code = d.optString("code", "");

        // 这里比的是 **PGAgreementType**（10 微信 / 20 支付宝 / 40 云闪付），
        // 不是 /dev/start 用的 ptype（21 支付宝免密 / 91 钱包 …）。
        // 之前误用 21 去比 —— pga 枚举里根本没有 21，所以**任何人**都会被判成
        // 「未开通免密」，提示永远弹。真实枚举见 Api.PGA_* 的注释。
        String typeName = Api.pgaTypeName(type);
        String statusName = Api.pgaStatusName(status);

        if (type < 0) {
            s.nopwd = UNKNOWN;
            s.detail = "服务端没返回签约渠道";
            return s;
        }
        if (type != Api.PGA_ALIPAY) {
            s.nopwd = NO;
            s.detail = "当前签约渠道是「" + typeName + "」（" + statusName + "），不是支付宝";
            return s;
        }

        if (status == Api.PGA_ST_NORMAL) {
            s.nopwd = YES;
            s.detail = "支付宝签约正常"
                    + (code.length() > 0 ? "（协议号 " + code + "）" : "");
        } else if (status == Api.PGA_ST_PREPARE || status == Api.PGA_ST_PENDING) {
            s.nopwd = NO;
            s.detail = "支付宝签约还在生效中（" + statusName + "）";
        } else if (status == Api.PGA_ST_STOP || status == Api.PGA_ST_TEMP) {
            s.nopwd = NO;
            s.detail = "支付宝签约已失效（" + statusName + "），需要重新开通";
        } else if (status < 0) {
            s.nopwd = UNKNOWN;
            s.detail = "服务端没返回签约状态";
        } else {
            // 5 无需签约 / 6 按需签约 / 7 强制签约 —— 这几个是策略值，不拿来断言
            s.nopwd = UNKNOWN;
            s.detail = "签约状态「" + statusName + "」，无法据此判断是否已开通";
        }
        return s;
    }

    // ---------------------------------------------------- 提醒

    /** 该不该弹提醒。 */
    public static boolean shouldNag(Activity a, String service, Status st) {
        if (st == null || st.nopwd != NO) {
            // 已开通，或状态未知 —— 不确定的事不拿来烦用户
            return false;
        }
        return !Prefs.payNagDisabled(a, service);
    }

    /**
     * 弹免密引导。三个按钮：
     *   去开通     —— 打开对应的官方 App（免密设置页是 exported=false，深链进不去）
     *   不再提示   —— 以后这个服务都不再弹
     *   知道了     —— 这次先这样，下次还会弹
     */
    public static void nag(final Activity a, final String service, final Status st) {
        if (a == null || a.isFinishing()) {
            return;
        }
        boolean water = SVC_WATER.equals(service);
        String body = (water
                ? "取水走的是「支付宝免密支付」，先出水、后扣款。"
                : "洗澡走的是「先用水、后结算」。")
                + "\n\n如果没开通免密，结算时扣不到钱，这一单会变成「待支付」，"
                + "需要你事后再去处理。开通免密之后就不用手动付了。\n\n"
                + (water
                    ? "开通路径：打开「慧生活798」→ 我的 → 找到「支付宝免密支付」并开通。"
                    : "开通路径：打开「胖乖生活」→ 我的 → 设置 → 开通「支付宝免密支付」。")
                // 把判断依据摊开：万一判断依据有问题，你一眼能看出来，也方便反馈
                + "\n\n—————\n判断依据（只读查询）：\n"
                + (st == null || st.detail.length() == 0 ? "(无)" : st.detail)
                + "\n\n如果你确实已经开通，但这里仍然提示，请把这段发给开发者。";

        new AlertDialog.Builder(a)
                .setTitle("建议先开通支付宝免密支付")
                .setMessage(body)
                .setCancelable(true)
                .setPositiveButton("去开通", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        // 深链进不去（目标页 exported=false），只能把官方 App 拉起来
                        Launcher.open(a, water ? "pkg:com.cloudora.android" : "pkg:com.qiekj.user");
                    }
                })
                .setNeutralButton("知道了", null)
                .setNegativeButton("不再提示", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.disablePayNag(a, service);
                    }
                })
                .show();
    }

    /** 立刻执行「查状态 → 需要则弹」这套动作。 */
    public static void checkAndNag(Activity a, String service, Status st) {
        if (shouldNag(a, service, st)) {
            nag(a, service, st);
        }
    }
}
