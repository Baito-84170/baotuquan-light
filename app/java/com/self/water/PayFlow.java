package com.self.water;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.net.URLEncoder;

/**
 * 洗澡订单的支付。
 *
 * 胖乖生活是「先用后付」：门开了、洗完了才结算，所以支付发生在
 * {@code shower/goods/remoteEnd} **之后**。官方 App 的顺序是
 *
 *     remoteEnd → order/afterPay/creating → PayStateAct
 *        → payChannelRoute/againPay → pay/unify/prePay{orderNo,payType}
 *        → prepayParam → 拉起支付宝 → 轮询 order/pay_status
 *
 * 我们**不集成支付宝 SDK**（依赖太重，还要注册 scheme），改走「跳转支付」：
 * 把 {@code prepayParam} 交给支付宝自己去处理。经验证 prepayParam 是支付宝的
 * orderString，用 {@code alipays://…&orderSuffix=} 就能唤起支付宝，已验证可用。
 *
 * 任何一步失败都有兜底：打开官方 App 让用户在那里付款 —— 至少不会卡死。
 */
public final class PayFlow {

    /** 官方 App 包名，兜底时打开它去付款。 */
    private static final String OFFICIAL_PKG = "com.qiekj.user";

    private PayFlow() {
    }

    public static String channelName(int payType) {
        if (payType == PanggualApi.PAY_ALIPAY) {
            return "支付宝";
        }
        if (payType == PanggualApi.PAY_ALIPAY_NOPWD) {
            return "支付宝免密";
        }
        if (payType == PanggualApi.PAY_WECHAT) {
            return "微信";
        }
        if (payType == PanggualApi.PAY_WALLET) {
            return "钱包余额";
        }
        return "渠道 " + payType;
    }

    /**
     * 结算一笔订单：先要 prepayParam，再弹窗让用户去付款。
     *
     * @param orderNo remoteEnd / unlock 拿到的订单号
     */
    public static void settle(final Activity a, final String orderNo,
                              final int payType, final String token,
                              final PanggualApi.Risk risk) {
        if (orderNo == null || orderNo.length() == 0) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final PanggualApi.Resp r = PanggualApi.prePay(orderNo, payType, token, risk);

                final String param = PanggualApi.pickPrepayParam(r);
                final JSONObject j = r.json();
                String e = "";
                if (r.err != null) {
                    e = r.err;
                } else if (j.optInt("code", -999) != 0) {
                    int code = j.optInt("code", -999);
                    e = "code " + code + (j.optString("msg", "").length() > 0
                            ? " · " + j.optString("msg") : "");
                }

                final String err = e;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (a.isFinishing()) {
                            return;
                        }
                        prompt(a, orderNo, payType, param, err);
                    }
                });
            }
        }).start();
    }

    /** 只弹「有未支付订单」的提示，用于 code 2128 拦截。 */
    public static void promptUnpaidBlock(final Activity a, final String orderNo,
                                         final int payType, final String token,
                                         final PanggualApi.Risk risk) {
        StringBuilder sb = new StringBuilder();
        sb.append("你有一笔洗澡订单还没付，它会挡住下一次开始用水。\n\n")
          .append("订单号：").append(orderNo).append("\n\n")
          .append("付掉之后就能正常洗澡了。也可以先去官方 App 把它结清。");

        new AlertDialog.Builder(a)
                .setTitle("有未支付订单")
                .setMessage(sb.toString())
                .setPositiveButton("去付款", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        settle(a, orderNo, payType, token, risk);
                    }
                })
                .setNeutralButton("打开官方 App", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        openOfficial(a);
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private static void prompt(final Activity a, final String orderNo,
                               final int payType, final String param, final String err) {
        StringBuilder sb = new StringBuilder();
        sb.append("订单号：").append(orderNo)
          .append("\n支付方式：").append(channelName(payType));

        if (err.length() > 0) {
            sb.append("\n\n服务端没给支付参数：").append(err)
              .append("\n可以直接打开官方 App 把这笔结掉。");
        } else {
            sb.append("\n\n点「去付款」会跳到").append(channelName(payType)).append("。")
              .append("付完之后这笔订单就不会再挡着下一次洗澡了。");
        }

        new AlertDialog.Builder(a)
                .setTitle("有未支付的洗澡订单")
                .setMessage(sb.toString())
                .setPositiveButton("去付款", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        jump(a, payType, param);
                    }
                })
                .setNeutralButton("打开官方 App", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        openOfficial(a);
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    /**
     * 跳去支付。
     *
     * 经验证（2026-10-15）：{@code prepayParam} 是一串**支付宝 orderString** ——
     * 形如 {@code alipay_root_cert_sn=…&app_id=2018031202359867&biz_content=…&sign=…}，
     * 既不是网址也不是 scheme。所以走的是第 2 条：把它塞进支付宝的
     * {@code alipays://platformapi/startapp?appId=20000125&orderSuffix=} 里唤起支付宝，
     * 这条已验证可用。
     *
     * 另外两条留着是为了防服务端哪天改用别的形态，代价很低。
     */
    public static void jump(Activity a, int payType, String param) {
        String p = param == null ? "" : param.trim();

        if (payType == PanggualApi.PAY_WALLET) {
            toast(a, "余额支付要在官方 App 里完成");
            openOfficial(a);
            return;
        }
        if (p.length() == 0) {
            toast(a, "没拿到支付参数，改去官方 App 付款");
            openOfficial(a);
            return;
        }


        // 1) 本身就是 scheme / 网址
        if (p.startsWith("alipays://") || p.startsWith("alipayqr://")
                || p.startsWith("http://") || p.startsWith("https://")) {
            if (open(a, p)) {
                return;
            }
        }

        // 2) 当成支付宝 orderString，用官方 scheme 唤醒支付宝
        try {
            if (open(a, "alipays://platformapi/startapp?appId=20000125&orderSuffix="
                    + URLEncoder.encode(p, "UTF-8"))) {
                return;
            }
        } catch (Exception ignored) {
        }

        // 3) 退到官方网关，交给浏览器／支付宝页面处理
        if (open(a, "https://openapi.alipay.com/gateway.do?" + p)) {
            return;
        }

        toast(a, "跳转失败，改去官方 App 付款");
        openOfficial(a);
    }

    /** 打开官方胖乖生活 App —— 付款兜底。 */
    public static void openOfficial(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(OFFICIAL_PKG);
        if (i == null) {
            toast(c, "没装官方 App。装一个就能把未支付订单结掉");
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            toast(c, "打不开官方 App");
        }
    }

    /** 开一个 uri；能解析到接收方才算成功。 */
    private static boolean open(Context c, String uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(c.getPackageManager()) == null) {
                return false;
            }
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_LONG).show();
    }
}
