package com.self.water;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/**
 * 首页：取水 / 洗澡 / 洗衣 三个平级入口，各自独立登录与状态。
 *
 * 也负责两件事：
 *   1. 首次启动时先走一遍使用引导（只走一次，可在设置里重看）；
 *   2. 接收桌面小组件带过来的快捷动作，直接跳到对应服务。
 */
public class HomeActivity extends BaseActivity {

    private static final int REQ_WATER = 11;
    private static final int REQ_SHOWER = 12;
    private static final int REQ_ONBOARD = 13;

    /** 小组件快捷方式使用：值为 water / shower / laundry。 */
    public static final String EXTRA_ACTION = "action";

    private TextView waterBadge;
    private TextView waterSub;
    private TextView showerBadge;
    private TextView showerSub;
    private TextView laundrySub;

    /** 已经处理过的小组件动作，避免 onResume 反复触发。 */
    private String handledAction;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        if (!Prefs.onboarded(this)) {
            startActivityForResult(new Intent(this, OnboardingActivity.class), REQ_ONBOARD);
            // 引导页盖在上面，首页先铺好，返回时就是完整界面
        }

        setContentView(R.layout.activity_home);

        waterBadge = findViewById(R.id.waterBadge);
        waterSub = findViewById(R.id.waterSub);
        showerBadge = findViewById(R.id.showerBadge);
        showerSub = findViewById(R.id.showerSub);
        laundrySub = findViewById(R.id.laundrySub);

        findViewById(R.id.cardWater).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openWater();
            }
        });

        findViewById(R.id.cardShower).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openShower();
            }
        });

        findViewById(R.id.cardLaundry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLaundry();
            }
        });

        findViewById(R.id.aboutLink).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, SettingsActivity.class));
            }
        });

        handleAction(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAction(intent);
    }

    /** 桌面小组件点进来时直接跳到对应服务。 */
    private void handleAction(Intent intent) {
        String action = intent == null ? null : intent.getStringExtra(EXTRA_ACTION);
        if (action == null || action.equals(handledAction)) {
            return;
        }
        handledAction = action;
        if ("water".equals(action)) {
            openWater();
        } else if ("shower".equals(action)) {
            openShower();
        } else if ("laundry".equals(action)) {
            openLaundry();
        }
    }

    private void openWater() {
        startActivityForResult(new Intent(this, WaterActivity.class), REQ_WATER);
    }

    private void openShower() {
        startActivityForResult(new Intent(this, ShowerActivity.class), REQ_SHOWER);
    }

    private void openLaundry() {
        Launcher.open(this, Launcher.xiaolianLaundry());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        // 自动查更新。onResume 会频繁触发，节流在 UpdateChecker 里（12 小时一次），
        // 而且「稍后」过的版本不会再弹。
        UpdateChecker.start(this, false);
        // 升级后第一次打开时说明这次改了什么。放在引导页之后，
        // 免得全新安装时两个界面叠在一起。
        if (Prefs.onboarded(this)) {
            WhatsNew.showIfNeeded(this);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        refresh();
    }

    private void refresh() {
        // 取水
        boolean waterIn = Prefs.loggedIn(this);
        setBadge(waterBadge, waterIn);
        waterSub.setText(waterIn
                ? DeviceStore.currentTitle(this, DeviceStore.TYPE_WATER,
                        getString(R.string.svc_water_desc))
                : getString(R.string.svc_water_desc));

        // 洗澡
        boolean showerIn = Prefs.pgLoggedIn(this);
        setBadge(showerBadge, showerIn);
        showerSub.setText(showerIn
                ? DeviceStore.currentTitle(this, DeviceStore.TYPE_SHOWER,
                        getString(R.string.svc_shower_desc))
                : getString(R.string.svc_shower_desc));

        // 洗衣
        laundrySub.setText(R.string.svc_laundry_desc);
    }

    private void setBadge(TextView tv, boolean loggedIn) {
        tv.setText(loggedIn ? R.string.badge_logged_in : R.string.badge_not_logged_in);
        tv.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getColor(loggedIn ? R.color.ok_bg : R.color.idle_bg)));
        tv.setTextColor(getColor(loggedIn ? R.color.ok : R.color.idle));
    }
}
