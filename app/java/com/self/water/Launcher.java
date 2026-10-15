package com.self.water;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 打开外部目标：支付宝小程序、其他 App、任意链接。 */
public final class Launcher {

    /** 智慧笑联小程序 appId。 */
    public static final String XIAOLIAN_APPID = "2021001189641702";

    /** 洗衣页深层链接（从分享链接解出）。 */
    private static final String LAUNDRY_PAGE =
            "&page=pages%2Fdevice%2Fwashing%2Findex%3F__id__%3D6"
            + "%26query%3D%25257B%252522deviceType%252522%25253A4%25257D"
            + "%26__appxPageId%3D14";

    private Launcher() {
    }

    /** 智慧笑联小程序首页。 */
    public static String xiaolianHome() {
        return "alipays://platformapi/startapp?appId=" + XIAOLIAN_APPID;
    }

    /** 智慧笑联洗衣页。 */
    public static String xiaolianLaundry() {
        return xiaolianHome() + LAUNDRY_PAGE;
    }

    /**
     * 打开一个目标。支持：
     * pkg:com.xxx      启动已安装的 App
     * alipays://...    完整支付宝 scheme
     * https://...appId=xxx  自动提取 appId 走原生跳转
     * 纯数字            当作小程序 appId
     */
    public static void open(Context ctx, String target) {
        if (target == null) {
            return;
        }
        String t = target.trim();
        try {
            if (t.regionMatches(true, 0, "pkg:", 0, 4)) {
                String pkg = t.substring(4).trim();
                Intent li = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (li == null) {
                    Toast.makeText(ctx, "未安装：" + pkg, Toast.LENGTH_LONG).show();
                    return;
                }
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(li);
                return;
            }

            Uri uri;
            if (t.regionMatches(true, 0, "alipays://", 0, 10)) {
                uri = Uri.parse(t);
            } else {
                String appId = extractAppId(t);
                uri = (appId != null)
                        ? Uri.parse("alipays://platformapi/startapp?appId=" + appId)
                        : Uri.parse(t);
            }
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(ctx, R.string.toast_no_app, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(ctx, "打开失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 从纯 appId 或分享链接里取出小程序 appId；取不到返回 null。 */
    public static String extractAppId(String t) {
        if (t.matches("\\d{8,}")) {
            return t;
        }
        Matcher m = Pattern.compile("(?i)appId(?:=|%3D)(\\d{8,})").matcher(t);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
