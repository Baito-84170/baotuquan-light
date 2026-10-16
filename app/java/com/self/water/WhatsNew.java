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
            49,
            48,
            47,
            46,
            45,
    };

    /** 对应的版本名，和 CODES 一一对应。 */
    private static final String[] NAMES = {
            "7.10.1",
            "7.10",
            "7.9",
            "7.8",
            "7.7",
    };

    /** 对应的更新说明，和 CODES 一一对应。 */
    private static final String[] NOTES = {
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
