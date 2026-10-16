package com.self.water;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检查更新。
 *
 * 更新源是本项目的 GitHub 仓库。取版本信息按这个顺序试，谁先通用谁：
 *
 *   1. jsDelivr CDN 上的 update.json —— 有全球 CDN，国内通常比 GitHub 直连稳，
 *      而且没有 API 限流；
 *   2. raw.githubusercontent.com 上的同一份 update.json；
 *   3. GitHub Releases API —— 兜底。未认证时每小时只有 60 次（按 IP 算），
 *      共享出口很容易被打满，所以只当最后手段。
 *
 * update.json 长这样（由 tools/publish-release.ps1 自动生成）：
 * <pre>
 * {
 *   "versionCode": 46,
 *   "versionName": "7.8",
 *   "notes":       "更新说明",
 *   "apkUrl":      "https://github.com/.../releases/latest/download/baotuquan-light.apk",
 *   "page":        "https://github.com/.../releases/latest"
 * }
 * </pre>
 *
 * APK 附件名固定成 {@link #APK_ASSET}（纯 ASCII），这样 `releases/latest/download/…`
 * 这个地址永远指向最新一版，不用每版改 URL，也避免中文文件名在 DownloadManager
 * 里出岔子。
 *
 * 检查本身只发一个 GET，不下载任何东西；要不要下载由用户点。
 */
public final class UpdateChecker {

    public static final String REPO = "Baito-84170/baotuquan-light";
    public static final String RELEASES_PAGE =
            "https://github.com/" + REPO + "/releases/latest";
    private static final String API =
            "https://api.github.com/repos/" + REPO + "/releases/latest";

    /** APK 附件的固定名字 —— 纯 ASCII，且让下载地址长期有效。 */
    public static final String APK_ASSET = "baotuquan-light.apk";
    public static final String APK_LATEST_URL =
            "https://github.com/" + REPO + "/releases/latest/download/" + APK_ASSET;

    /**
     * update.json 的镜像，按顺序试。
     *
     * raw.githubusercontent 是实时内容（只有几分钟 CDN 缓存）所以放第一个；
     * jsDelivr 对分支引用有约 12 小时缓存，可能拿到旧版本，因此即便它先答了
     * 「已是最新」也要继续问下一个（见 fetch）。国内 raw 有时不通，那时靠
     * jsDelivr 或 API 兜底。
     */
    private static final String[] MIRRORS = {
            // 自建镜像：同一个 WiFi / 国内直连都很快，自签证书已在
            // network_security_config 里固定。挂了会自动退回下面两个。
            "https://120.27.236.74:8443/baotuquan/update.json",
            "https://raw.githubusercontent.com/" + REPO + "/main/update.json",
            "https://cdn.jsdelivr.net/gh/" + REPO + "@main/update.json",
    };

    /** 自动检查的间隔：12 小时。手动检查不受这个限制。 */
    public static final long AUTO_INTERVAL_MS = 12L * 3600L * 1000L;

    private static final int NOTES_MAX = 700;

    private UpdateChecker() {
    }

    /** 一条可用的更新。 */
    public static final class Info {
        /** 服务端 versionCode；拿不到时为 0。 */
        public int code;
        public String name = "";
        public String notes = "";
        public String apkUrl = "";
        public String apkName = APK_ASSET;
        /** 备用下载地址（GitHub）。自建镜像只求快，挂了就退回这里。 */
        public String apkFallback = "";
        public String page = RELEASES_PAGE;
    }

    public interface Callback {
        /** info 为 null 表示已经是最新版（或还没发布过）。 */
        void onResult(Info info);

        void onError(String msg);
    }

    // ---------------------------------------------------- 对外入口

    /**
     * 检查更新并在有新版时弹窗。
     *
     * @param manual true = 用户手动点的（会提示「已是最新」/失败原因）；
     *               false = 启动时自动跑的（安静失败，且「稍后」过的版本不再打扰）
     */
    public static void start(final Activity a, final boolean manual) {
        if (manual) {
            Toast.makeText(a, R.string.update_checking, Toast.LENGTH_SHORT).show();
        }
        check(a, manual, new Callback() {
            @Override
            public void onResult(Info info) {
                if (info == null) {
                    if (manual) {
                        Toast.makeText(a,
                                a.getString(R.string.update_latest, installedName(a)),
                                Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                // 用户点过「稍后」的版本，自动检查就不再打扰；手动检查照旧提示
                if (!manual && info.name.equals(Prefs.skippedUpdate(a))) {
                    return;
                }
                prompt(a, info);
            }

            @Override
            public void onError(String msg) {
                if (manual) {
                    Toast.makeText(a, a.getString(R.string.update_failed, msg),
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** 只查不弹窗。force=false 时受 12 小时间隔限制。 */
    public static void check(final Context ctx, final boolean force, final Callback cb) {
        final Context c = ctx.getApplicationContext();
        if (!force) {
            long last = Prefs.lastUpdateCheck(c);
            if (last > 0 && System.currentTimeMillis() - last < AUTO_INTERVAL_MS) {
                return;
            }
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Info info = fetch(c);
                    Prefs.setLastUpdateCheck(c, System.currentTimeMillis());
                    post(cb, info, null);
                } catch (Exception e) {
                    post(cb, null, brief(e));
                }
            }
        }).start();
    }

    private static void post(final Callback cb, final Info info, final String err) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (cb == null) {
                    return;
                }
                if (err != null) {
                    cb.onError(err);
                } else {
                    cb.onResult(info);
                }
            }
        });
    }

    // ---------------------------------------------------- 查询

    /**
     * 依次尝试各更新源。返回 null 表示已经是最新。
     *
     * 这里**不**在拿到第一个「已是最新」时就收工 —— 镜像有缓存，可能其中
     * 一个还是旧内容。只有所有源都答了、且都没有新版本，才算真的最新。
     */
    private static Info fetch(Context c) throws Exception {
        Exception last = null;
        boolean answered = false;
        for (String url : MIRRORS) {
            try {
                Info info = fromJson(url, c);
                answered = true;
                if (info != null) {
                    return info;              // 找到新版本，立即返回
                }
            } catch (Exception e) {
                last = e;                     // 这个源不通，换下一个
            }
        }
        if (answered) {
            return null;                      // 所有源都答了，且都是最新
        }
        try {
            return fromApi(c);                // 兜底：GitHub Releases API
        } catch (Exception e) {
            throw last != null ? last : e;
        }
    }

    /** 解析 update.json。已经是最新时返回 null。 */
    private static Info fromJson(String url, Context c) throws Exception {
        JSONObject j = new JSONObject(httpGet(url, c));
        int remote = j.optInt("versionCode", 0);
        String name = j.optString("versionName", "");

        boolean newer = remote > 0
                ? remote > installedCode(c)
                : compareNums(name, installedName(c)) > 0;
        if (!newer) {
            return null;
        }

        Info info = new Info();
        info.code = remote;
        info.name = name.length() == 0 ? "?" : name;
        info.notes = cap(j.optString("notes", ""));
        String apk = j.optString("apkUrl", "");
        info.apkUrl = apk.length() == 0 ? APK_LATEST_URL : apk;
        info.apkName = APK_ASSET;
        info.apkFallback = APK_LATEST_URL;
        info.page = j.optString("page", RELEASES_PAGE);
        return info;
    }

    /** 兜底：直接问 GitHub Releases API。 */
    private static Info fromApi(Context c) throws Exception {
        String body;
        try {
            body = httpGet(API, c);
        } catch (FileNotFoundException e) {
            return null;                      // 还没发布过任何 Release
        }
        JSONObject j = new JSONObject(body);
        String tag = j.optString("tag_name", "");
        String notes = j.optString("body", "");

        Info info = new Info();
        info.name = tagVersion(tag);
        if (info.name.length() == 0) {
            info.name = tag;
        }
        info.page = j.optString("html_url", RELEASES_PAGE);
        info.notes = cap(plainNotes(notes));

        // 优先取固定名附件；没有就退而求其次拿第一个 .apk
        String fallback = "";
        String fallbackName = "";
        JSONArray assets = j.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) {
                    continue;
                }
                String n = a.optString("name", "");
                if (!n.toLowerCase().endsWith(".apk")) {
                    continue;
                }
                if (APK_ASSET.equals(n)) {
                    fallback = a.optString("browser_download_url", "");
                    fallbackName = n;
                    break;
                }
                if (fallback.length() == 0) {
                    fallback = a.optString("browser_download_url", "");
                    fallbackName = n;
                }
            }
        }
        if (fallback.length() == 0) {
            return null;                      // 没有 APK 附件就没法自动更新
        }
        info.apkUrl = fallback;
        info.apkName = fallbackName;
        info.apkFallback = "";

        int marker = markerCode(notes);
        if (marker > 0) {
            info.code = marker;
            if (marker <= installedCode(c)) {
                return null;
            }
        } else if (compareNums(info.name, installedName(c)) <= 0) {
            return null;
        }
        return info;
    }

    private static String httpGet(String url, Context c) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "baotuquan-light/" + installedName(c));

            int http = conn.getResponseCode();
            if (http == 404) {
                throw new FileNotFoundException("HTTP 404: " + url);
            }
            if (http != 200) {
                throw new Exception("HTTP " + http);
            }
            return read(conn.getInputStream());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String read(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
        }
        r.close();
        return sb.toString();
    }

    /** v7.8 → 7.8 */
    private static String tagVersion(String tag) {
        if (tag == null) {
            return "";
        }
        return tag.trim().replaceAll("^[vV]", "");
    }

    /** 从正文里取 versionCode 标记；没有就返回 0。 */
    private static int markerCode(String body) {
        if (body == null) {
            return 0;
        }
        Matcher m = Pattern.compile("versionCode\\s*[:：]\\s*(\\d+)").matcher(body);
        return m.find() ? toInt(m.group(1)) : 0;
    }

    /** 去掉版本标记那一行和 HTML 注释，得到给人看的更新说明。 */
    private static String plainNotes(String body) {
        if (body == null) {
            return "";
        }
        String s = body.replaceAll("(?m)^.*versionCode\\s*[:：]\\s*\\d+.*$", "");
        s = s.replaceAll("(?s)<!--.*?-->", "");
        s = s.replaceAll("(?m)^#{1,6}\\s*", "");      // 去掉 markdown 标题号
        s = s.replaceAll("\\*\\*", "").replaceAll("`", "");
        return cap(s.trim());
    }

    private static String cap(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        return s.length() > NOTES_MAX ? (s.substring(0, NOTES_MAX) + "…") : s;
    }

    /** 把字符串里的数字逐段抽出来比大小：v7.10 > v7.9 > v7.8。 */
    private static int compareNums(String a, String b) {
        List<Integer> x = nums(a);
        List<Integer> y = nums(b);
        int n = Math.max(x.size(), y.size());
        for (int i = 0; i < n; i++) {
            int xi = i < x.size() ? x.get(i) : 0;
            int yi = i < y.size() ? y.get(i) : 0;
            if (xi != yi) {
                return xi < yi ? -1 : 1;
            }
        }
        return 0;
    }

    private static List<Integer> nums(String v) {
        List<Integer> out = new ArrayList<Integer>();
        Matcher m = Pattern.compile("\\d+").matcher(v == null ? "" : v);
        while (m.find()) {
            out.add(toInt(m.group()));
        }
        return out;
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    public static int installedCode(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String installedName(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionName == null ? "" : p.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static String brief(Exception e) {
        String m = e.getMessage();
        if (m == null || m.length() == 0) {
            m = e.getClass().getSimpleName();
        }
        return m.length() > 90 ? m.substring(0, 90) : m;
    }

    // ---------------------------------------------------- 弹窗

    private static void prompt(final Activity a, final Info info) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前版本：v").append(installedName(a))
          .append("\n最新版本：v").append(info.name);
        if (info.notes.length() > 0) {
            sb.append("\n\n").append(info.notes);
        }
        new AlertDialog.Builder(a)
                .setTitle(R.string.update_title)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.update_now, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        downloadAndInstall(a, info);
                    }
                })
                .setNeutralButton(R.string.update_page, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        openPage(a, info);
                    }
                })
                .setNegativeButton(R.string.update_later, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.setSkippedUpdate(a, info.name);
                    }
                })
                .show();
    }

    // ---------------------------------------------------- 下载并安装

    /**
     * 下载 APK 并拉起系统安装界面。
     *
     * 这里**不再用系统的 DownloadManager**：APK 来自自建镜像、用的是自签证书，
     * 而 DownloadManager 是**系统服务**，不认我们 App 的
     * network_security_config —— 自签证书在它那里必然失败。这是方案 B 绕不开的一步。
     *
     * 所以自己下：HttpURLConnection → App 私有外部目录 → FileProvider → 安装意图。
     * 文件只有几百 KB、用户就在前台等着，用不着断点续传，也用不着后台下载。
     *
     * 主源挂了会自动退回 GitHub Releases —— 自建镜像只求「快」，不是唯一来源。
     */
    public static void downloadAndInstall(final Activity a, final Info info) {
        final ProgressDialog pd = new ProgressDialog(a);
        pd.setMessage("正在下载更新…");
        pd.setCancelable(false);
        try {
            pd.show();
        } catch (Exception ignored) {
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                File apk = null;
                String err = null;
                String[] urls = {info.apkUrl, info.apkFallback};
                for (String url : urls) {
                    if (url == null || url.length() == 0) {
                        continue;
                    }
                    try {
                        apk = fetch(a, url);
                        break;
                    } catch (Exception e) {
                        err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    }
                }

                final File file = apk;
                final String fail = err;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            pd.dismiss();
                        } catch (Exception ignored) {
                        }
                        if (a.isFinishing()) {
                            return;
                        }
                        if (file == null) {
                            Toast.makeText(a, "下载失败：" + fail + "\n可以打开发布页手动下载",
                                    Toast.LENGTH_LONG).show();
                            openPage(a, info);
                            return;
                        }
                        install(a, file, info);
                    }
                });
            }
        }).start();
    }

    /** 先写 .part，下完再改名 —— 免得半个文件被当成完整 APK 交出去。 */
    private static File fetch(Context c, String url) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "baotuquan-light/" + installedName(c));

            int http = conn.getResponseCode();
            if (http != 200) {
                throw new Exception("HTTP " + http);
            }

            File dst = new File(SimpleFileProvider.dir(c), APK_ASSET);
            File tmp = new File(dst.getParentFile(), APK_ASSET + ".part");
            in = conn.getInputStream();
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            out.close();
            out = null;
            in.close();
            in = null;

            // 太小肯定不是 APK（多半拿到的是错误页）
            if (total < 1024) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new Exception("内容异常（" + total + " 字节）");
            }
            if (dst.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
            }
            if (!tmp.renameTo(dst)) {
                throw new Exception("保存失败");
            }
            return dst;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void install(Context c, File apk, Info info) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(SimpleFileProvider.uriFor(apk),
                "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(c, "打不开安装界面，可以去发布页手动下载", Toast.LENGTH_LONG).show();
            openPage(c, info);
        }
    }
    /** 跳到系统设置里给我们开「安装未知应用」。 */
    public static void openInstallSettings(Activity a) {
        try {
            a.startActivity(new Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception ignored) {
        }
    }

    public static void openPage(Context c, Info info) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(info == null ? RELEASES_PAGE : info.page));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
