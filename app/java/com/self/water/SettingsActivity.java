package com.self.water;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.Toast;

/** 设置页：外观模式 + 关于入口。 */
public class SettingsActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);

        RadioGroup group = findViewById(R.id.themeGroup);
        int mode = Prefs.themeMode(this);
        if (mode == Prefs.THEME_LIGHT) {
            group.check(R.id.themeLight);
        } else if (mode == Prefs.THEME_DARK) {
            group.check(R.id.themeDark);
        } else {
            group.check(R.id.themeSystem);
        }

        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                int picked = Prefs.THEME_SYSTEM;
                if (id == R.id.themeLight) {
                    picked = Prefs.THEME_LIGHT;
                } else if (id == R.id.themeDark) {
                    picked = Prefs.THEME_DARK;
                }
                if (picked == Prefs.themeMode(SettingsActivity.this)) {
                    return;
                }
                Prefs.setThemeMode(SettingsActivity.this, picked);
                // 重建自己让新外观立即生效。压在后面的页面（比如主页）会在自己
                // onResume 时发现偏好变了，也各自重建 —— 这样全 App 配色才一致。
                reloadTheme();
            }
        });

        // 小组件样式
        RadioGroup wg = findViewById(R.id.widgetStyleGroup);
        int ws = Prefs.widgetStyle(this);
        if (ws == Prefs.WIDGET_LIGHT) {
            wg.check(R.id.widgetLight);
        } else if (ws == Prefs.WIDGET_GLASS) {
            wg.check(R.id.widgetGlass);
        } else {
            wg.check(R.id.widgetDark);
        }
        wg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                int picked = Prefs.WIDGET_DARK;
                if (id == R.id.widgetLight) {
                    picked = Prefs.WIDGET_LIGHT;
                } else if (id == R.id.widgetGlass) {
                    picked = Prefs.WIDGET_GLASS;
                }
                if (picked == Prefs.widgetStyle(SettingsActivity.this)) {
                    return;
                }
                Prefs.setWidgetStyle(SettingsActivity.this, picked);
                // 光写偏好桌面不会重画，必须主动推一次
                WidgetProvider.refreshAll(SettingsActivity.this);
                Toast.makeText(SettingsActivity.this,
                        "小组件样式已更新", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.rowOnboard).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, OnboardingActivity.class));
            }
        });

        findViewById(R.id.rowPayNag).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.resetPayNag(SettingsActivity.this);
                Toast.makeText(SettingsActivity.this,
                        "已恢复免密支付提示", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.rowUpdate).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UpdateChecker.start(SettingsActivity.this, true);
            }
        });

        findViewById(R.id.rowAbout).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, AboutActivity.class));
            }
        });
    }
}
