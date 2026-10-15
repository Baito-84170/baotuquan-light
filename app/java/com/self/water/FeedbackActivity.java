package com.self.water;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URLEncoder;

/**
 * 意见反馈。
 *
 * 这个 App 没有服务端，所以不做「提交」按钮 —— 点下去也没地方送。反馈走三条
 * 实在的路：
 *
 *   1. 复制到剪贴板 —— 最常用：粘到微信 / QQ 里发给开发者，一步到位
 *   2. 发邮件
 *   3. 提交到 GitHub Issues —— 公开的，好处是能看到处理进展
 *
 * 可以顺带附上诊断信息（版本、机型、登录状态）。**刻意不含**设备编号、账号、
 * token 之类的凭据，而且勾选框下面会把具体内容列出来，附什么用户看得见。
 */
public class FeedbackActivity extends BaseActivity {

    private static final String REPO = "Baito-84170/baotuquan-light";
    private static final String MAIL = "STOstudios@outlook.com";

    /** GitHub 的 issue 正文有长度上限，超了会被截断，这里先自己截。 */
    private static final int BODY_MAX = 5000;
    private static final int MAIL_MAX = 2000;

    private EditText input;
    private CheckBox diag;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_feedback);

        input = findViewById(R.id.fbInput);
        diag = findViewById(R.id.fbDiag);
        ((TextView) findViewById(R.id.fbDiagSummary)).setText(diagnostics());

        findViewById(R.id.fbCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasText()) {
                    return;
                }
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) {
                    toast("这台设备没有剪贴板");
                    return;
                }
                cm.setPrimaryClip(ClipData.newPlainText("反馈", compose()));
                toast("已复制。粘到微信 / QQ 里发给我就行");
            }
        });

        findViewById(R.id.fbMail).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasText()) {
                    return;
                }
                String url = "mailto:" + MAIL
                        + "?subject=" + enc("[趵突泉之光] " + subject())
                        + "&body=" + enc(cap(compose(), MAIL_MAX));
                open(url, "没找到邮件应用，可以先「复制反馈内容」");
            }
        });

        findViewById(R.id.fbGithub).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasText()) {
                    return;
                }
                String body = compose() + "\n\n---\n（在 App 的反馈页生成）";
                String url = "https://github.com/" + REPO + "/issues/new"
                        + "?title=" + enc(subject())
                        + "&body=" + enc(cap(body, BODY_MAX));
                open(url, "打不开浏览器，可以先「复制反馈内容」再手动打开 GitHub");
            }
        });
    }

    // ---------------------------------------------------- 内容

    private boolean hasText() {
        if (input.getText().toString().trim().length() == 0) {
            toast("先写点什么吧");
            return false;
        }
        return true;
    }

    /** 标题用第一行，太长就截断。 */
    private String subject() {
        String t = input.getText().toString().trim();
        int nl = t.indexOf('\n');
        if (nl > 0) {
            t = t.substring(0, nl);
        }
        t = t.trim();
        return t.length() > 60 ? (t.substring(0, 60) + "…") : t;
    }

    /** 反馈正文 +（可选）诊断信息。 */
    private String compose() {
        StringBuilder sb = new StringBuilder(input.getText().toString().trim());
        if (diag.isChecked()) {
            sb.append("\n\n—— 诊断信息 ——\n").append(diagnostics());
        }
        return sb.toString();
    }

    /**
     * 诊断信息。**刻意不含**设备编号和任何 token —— 报 bug 用不着它们，
     * 带上只会让用户担心。
     */
    private String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("版本：").append(UpdateChecker.installedName(this))
          .append("（构建 ").append(UpdateChecker.installedCode(this)).append("）");
        sb.append("\n系统：Android ").append(Build.VERSION.RELEASE)
          .append("（API ").append(Build.VERSION.SDK_INT).append("）");
        sb.append("\n机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL);
        sb.append("\n登录：取水 ").append(Prefs.loggedIn(this) ? "已登录" : "未登录")
          .append(" · 洗澡 ").append(Prefs.pgLoggedIn(this) ? "已登录" : "未登录");
        return sb.toString();
    }

    // ---------------------------------------------------- 小工具

    private static String cap(String s, int max) {
        return s.length() <= max ? s : (s.substring(0, max) + "\n…（内容过长已截断）");
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private void open(String url, String failMsg) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (i.resolveActivity(getPackageManager()) == null) {
                toast(failMsg);
                return;
            }
            startActivity(i);
        } catch (Exception e) {
            toast(failMsg);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
