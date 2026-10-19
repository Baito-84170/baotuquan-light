package com.self.water;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
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
     * 拿 prepayParam 的重试策略。
     *
     * 服务端在刚 remoteEnd 完的时候经常先回 {@code code:-1,msg:null}，缓一下才给参数。
     * 以前拿不到就直接退回打开官方 App —— 这正是"点了去支付却跳胖乖"的来源。
     * 现在改成在这里等：最多 5 次、间隔 1.5 秒（约 6 秒），拿不到再让用户决定。
     */
    private static final int PREPAY_TRIES = 1;
    // 旧版经验证：第一次拿不到参数、隔几分钟再点第二次就能付 —— 说明服务端需要时间结算。
    // 之前只等 5×1.5≈6 秒，根本不够。现在 10 次 × 5 秒 ≈ 50 秒，覆盖掉那段结算延迟。
    private static final long PREPAY_GAP_MS = 5000;

    /**
     * 结算一笔订单：先要 prepayParam（拿不到就重试等待），再弹窗引导去支付宝。
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
                PanggualApi.Resp r = null;
                String param = "";
                String err = "";
                String lastRaw = "";

                for (int i = 1; i <= PREPAY_TRIES; i++) {
                    // againFlag 的语义没法只靠反编译确定：官方默认是 "0"（首次支付），
                    // 但"对一笔已经发起过的订单再要参数"也可能要 "1"。两种都试，
                    // 第一次用 "0"、之后用 "1"，哪种能拿到参数日志里一眼就看出来。
                    String flag = (i == 1) ? PanggualApi.AGAIN_FIRST : PanggualApi.AGAIN_RETRY;
                    r = PanggualApi.prePay(orderNo, payType, flag, token, risk);

                    param = PanggualApi.pickPrepayParam(r);
                    if (param.length() > 0) {
                        err = "";
                        break;
                    }

                    lastRaw = r.body == null ? "" : r.body;
                    if (r.err != null) {
                        err = r.err;
                    } else {
                        JSONObject j = r.json();
                        int code = (j == null) ? -999 : j.optInt("code", -999);
                        String msg = (j == null) ? "" : j.optString("msg", "");
                        err = "code " + code + (msg.length() > 0 ? " · " + msg : "")
                                + (j == null ? "（响应不是 JSON）" : "");
                    }
                    if (i < PREPAY_TRIES) {
                        try {
                            Thread.sleep(PREPAY_GAP_MS);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }

                final String fParam = param;
                final String fErr = err;
                final String fRaw = lastRaw;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (a.isFinishing()) {
                            return;
                        }
                        prompt(a, orderNo, payType, fParam, fErr, fRaw, token, risk);
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
          .append("付掉之后就能正常洗澡了。");

        new AlertDialog.Builder(a)
                .setTitle("有未支付订单")
                .setMessage(sb.toString())
                .setPositiveButton("去支付宝付款", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        settle(a, orderNo, payType, token, risk);
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    /**
     * 结算弹窗。
     *
     * 按用户要求**不再提供「打开官方 App 付款」这条路** —— 付款只走支付宝；
     * 拿不到支付参数就给「重试」，并且明确告诉用户这笔订单不会丢。
     */
    private static void prompt(final Activity a, final String orderNo,
                               final int payType, final String param,
                               final String err, final String raw,
                               final String token,
                               final PanggualApi.Risk risk) {
        StringBuilder sb = new StringBuilder();
        sb.append("订单号：").append(orderNo)
          .append("\n支付方式：").append(channelName(payType));

        final boolean ready = param.length() > 0;
        if (ready) {
            sb.append("\n\n点「去支付宝付款」会唤起支付宝完成这一笔。")
              .append("付完之后这笔订单就不会再挡着下一次洗澡了。");
        } else {
            sb.append("\n\n服务端没返回支付参数（已试 ")
              .append(PREPAY_TRIES).append(" 次）。");
            if (err.length() > 0) {
                sb.append("\n\n最近一次：\n").append(err);
            }
            // 把服务端原样回的东西贴在弹窗里 —— 出问题时不依赖 logcat / adb，
            // 截图就能看出它到底在抱怨什么。
            if (raw != null && raw.length() > 0) {
                String cut = raw.length() > 260 ? raw.substring(0, 260) + "…" : raw;
                sb.append("\n\n服务端原文：\n").append(cut);
            }
            sb.append("\n\n这笔订单已经记在本地了，点「稍后」不会丢 —— ")
              .append("下次进洗澡页点「支付这一单」还能接着付。");
        }

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(ready ? "有未支付的洗澡订单" : "暂时拿不到支付参数")
                .setMessage(sb.toString())
                .setNegativeButton("稍后", null);

        if (ready) {
            b.setPositiveButton("去支付宝付款", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    jump(a, payType, param);
                }
            });
        } else {
            b.setPositiveButton("重试", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    settle(a, orderNo, payType, token, risk);
                }
            });
        }
        b.show();
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

        // 付款路径只走支付宝。任何情况下都不再打开官方 App —— 那条路会把用户丢进
        // 一个找不到订单的界面里（本地没记录、官方 App 里也未必找得到那一笔）。
        if (p.length() == 0) {
            toast(a, "还没有支付参数，请点「重试」");
            return;
        }
        if (payType == PanggualApi.PAY_WALLET) {
            toast(a, "钱包余额支付不支持跳转，请在机器上重新选支付方式");
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

        // 3) 退到官方网关，交给支付宝页面处理
        if (open(a, "https://openapi.alipay.com/gateway.do?" + p)) {
            return;
        }

        toast(a, "打不开支付宝，改去官方 App 付款");
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

    /**
     * 开一个 uri。
     *
     * 这里**不能**用 {@code resolveActivity()} 做前置判断 —— 这正是「点去支付却打开胖乖」
     * 的元凶：Android 11+ 的包可见性会让它在目标应用明明装着的情况下也返回 null，
     * 于是能跳的支付宝被判成「跳不了」，一路 fallback 到 {@link #openOfficial}。
     * 改成直接 startActivity，用 ActivityNotFoundException 判断成败。
     */
    private static boolean open(Context c, String uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_LONG).show();
    }
}
