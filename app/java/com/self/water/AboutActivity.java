package com.self.water;

import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.widget.TextView;

/** 关于页：开发者署名、版本信息、隐私与边界说明。 */
public class AboutActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_about);

        TextView ver = findViewById(R.id.aboutVersion);
        ver.setText(getString(R.string.about_version, versionName(), versionCode()));

        // 单独一行、大字号再显示一次 —— 之前只有应用名下面那行 12sp 灰字，不好找
        TextView big = findViewById(R.id.aboutVersionBig);
        big.setText("v" + versionName() + "（构建 " + versionCode() + "）");
    }

    private String versionName() {
        try {
            PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            return p.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    @SuppressWarnings("deprecation")
    private int versionCode() {
        try {
            PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            return p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }
}
