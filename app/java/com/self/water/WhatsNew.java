package com.self.water;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;

/**
 * 版本更新说明。
 *
 * 每次发版都往下面三个数组的**最前面**加一条，装过旧版的用户升级后第一次打开
 * 就会看到「这次更新了什么」。
 *
 * 说明直接内置在包里，不走网络 —— 升级后可能还没联网，或者 update.json 已经
 * 指向更新的版本了，从远处取都不可靠。
 *
 * 跨版本升级（比如从 45 直接到 47）会把中间每个版本的说明都列出来。
 */
public final class WhatsNew {

    /** versionCode，从新到旧。 */
    private static final int[] CODES = {
            55,
            54,
            53,
            52,
            51,
            50,
            49,
            48,
            47,
            46,
            45,
    };

    /** 对应的版本名，和 CODES 一一对应。 */
    private static final String[] NAMES = {
            "7.11.3",
            "7.11.2",
            "7.11.1",
            "7.11",
            "7.10.2",
            "7.10.1",
            "7.10",
            "7.9",
            "7.8",
            "7.7",
    };

    /** 对应的更新说明，和 CODES 一一对应。 */
    private static final String[] NOTES = {
            "• ⚠️ 取水功能暂停使用：798 服务端给 `/dev/start` 单独加了客户端校验，只放行官方最新版 App "
            + "发出来的请求。版本号、请求头、六个参数、支付方式、同族接口我都试遍了（日志还留着），"
            + "拿到的永远是那句「请升级最新版APP」—— 这条路我们这边修不了。\n"
            + "• 现在点「激活取水」会直接把你送到 798 的设备页：在那里点一下开始，机器照常出水；"
            + "回到本页，实时状态、结束用水、结算都还在，等于多一下点击。\n"
            + "• 洗澡、洗衣不受影响。",
            "• 「取水」激活继续排：`cnt` 不是原因（1 和 2 都被拒，日志里有）。这一版把 `rcp` "
            + "也纳入试探 —— 官方那条路传的是 `true`，我们之前一直发 `false`；再加一条同族的 "
            + "`dev/mode/start` 兜底。哪一组过了会记在日志里，告诉我第几组就行。",

            "• 「取水」激活修好了 —— 而且找到的是**真原因**：服务端一直回「请升级最新版APP」，"
            + "害我们以为是版本校验，把各种版本号和请求头试了个遍。其实官方 `/dev/start` 里那个 "
            + "`cnt` 参数根本不是版本号，只是 1 或 2 的一个标志位，我们却往里塞了自己的 "
            + "versionCode（31009）。服务端收到一个越界的数，就回了那句含糊的「请升级」。"
            + "现在按官方的取值发：先试 `cnt=1`，不行再试 2。\n"
            + "• 顺带补上了 `args`（官方是 `{\"touch\":1}`，人站在机器前的标志）。",

            "• 「取水」激活不再被 798 的版本校验卡死：服务端对 `/dev/start` 只放行最新版官方 App，"
            + "我们的请求会被回一句「请升级最新版APP」。现在点激活，会先把「带不带 args」"
            + "和几种支付方式各试一遍；全被拦的话，直接帮你打开 798 的设备页 —— 在那里点一下开始，"
            + "机器就出水，回到本页看实时状态、结束用水、结算都照常。",

            "• 洗澡支付修好了：原来只在结束后才结算，服务端算不出金额，于是永远拿不到支付参数、付不了款。现在按官方做法在洗澡过程中持续上报用量，支付已恢复跳转支付宝。\n"
            + "• 顺带：结束但未付款的订单不再丢失，会留在洗澡页上，可随时点「去支付宝付款」补付。",

            "• 修好「取水」的免密支付状态判定：之前查协议时用错了枚举，会永远判成「未开通」，反复弹「建议开通支付宝免密」；同时补上 eid 自动获取（老账号不必重新登录），并改成每次回到页面都刷新一次状态。",

            "• 换了个更快的更新源（自建镜像），下载和检查更新都比以前快。",

            "• 新增「意见反馈」页：在「设置」里。写完之后一键复制，"
                    + "粘到微信或 QQ 里发给我就行；也可以发邮件或提交到 GitHub。",

            "• 洗澡支付修好了：结束洗澡后会直接跳转支付宝付款。\n"
                    + "• 免密支付暂时无法使用，会走普通支付宝支付。\n"
                    + "• ⚠️ 上个版本用过洗澡的同学，记得检查有没有卡住未支付的订单 —— "
                    + "有的话「洗澡」页会直接提示，付掉之后才能继续用水。",

            "• 新增自动检查更新：启动时自动查有没有新版本，设置里也能手动检查。",

            "• 修复「结束本次用水」点了没反应：之前传给服务端的 rcp 参数不对，"
                    + "服务端只返回回执、并不执行结束。",
    };

    private WhatsNew() {
    }

    /** 某个 versionCode 的说明；没有返回空串。 */
    private static String notesFor(int code) {
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i] == code) {
                return NOTES[i];
            }
        }
        return "";
    }

    /** (after, upTo] 区间内所有版本的说明，从旧到新拼接；没有就返回空串。 */
    private static String notesBetween(int after, int upTo) {
        StringBuilder sb = new StringBuilder();
        for (int i = CODES.length - 1; i >= 0; i--) {   // 数组是从新到旧，这里倒着走
            int code = CODES[i];
            if (code > after && code <= upTo) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append("v").append(NAMES[i]).append("\n").append(NOTES[i]);
            }
        }
        return sb.toString();
    }

    /**
     * 该弹就弹。第一次装的用户只看到当前版本的说明（他们同时还会看到使用引导）；
     * 之后每次升级，看到的是从上个版本到当前版本的全部变更。
     */
    public static void showIfNeeded(final Activity a) {
        final int now = UpdateChecker.installedCode(a);
        final int seen = Prefs.lastSeenVersion(a);

        if (now <= 0 || now < seen) {
            return;
        }
        if (now == seen) {
            return;
        }

        // seen == 0 有两种可能：全新安装，或者是装过「还没有这个功能」的版本。
        // 两种都只显示当前这一版就够了，不能把历史全糊上去。
        final String notes = seen == 0 ? notesFor(now) : notesBetween(seen, now);

        // 先记下来再弹：万一弹窗被打断，也不会每次启动都来烦人
        Prefs.setLastSeenVersion(a, now);

        if (notes.length() == 0) {
            return;
        }

        String title = "已更新到 v" + UpdateChecker.installedName(a);
        if (seen == 0) {
            title = "v" + UpdateChecker.installedName(a);
        }

        new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(notes)
                .setPositiveButton("知道了", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                    }
                })
                .show();
    }
}
