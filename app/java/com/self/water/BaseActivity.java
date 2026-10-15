package com.self.water;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;

/**
 * 所有页面的基类，统一处理两件跨页面的事：
 *
 *   1. 外观模式（跟随系统 / 浅色 / 深色）：把偏好写进 Configuration.uiMode，
 *      这样 values / values-night 两套颜色目录会自动生效。
 *   2. 登录过期：任一接口返回「登录状态已过期」时，弹一次提示并提供一键重登。
 */
public class BaseActivity extends Activity {

    /** 从「登录已过期」弹窗里跳去登录页时用的请求码。 */
    protected static final int REQ_RELOGIN = 9001;

    /** 本轮页面生命周期里是否已经弹过过期提示，避免轮询反复弹。 */
    private boolean expiredShown;
    /** 上面那次重登是去登哪个账号。 */
    private boolean reloginShower;
    /** 本实例创建时用的是哪个主题偏好，用来发现"偏好变了但界面没重建"。 */
    private int appliedThemeMode = -1;
    private boolean recreatingForTheme;

    /** 是否处于深色模式。 */
    protected boolean isDark() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 按偏好重写 uiMode。 */
    @Override
    protected void attachBaseContext(Context base) {
        appliedThemeMode = Prefs.themeMode(base);
        int pref = appliedThemeMode;
        if (pref == Prefs.THEME_SYSTEM) {
            super.attachBaseContext(base);
            return;
        }
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        int mask = Configuration.UI_MODE_NIGHT_MASK;
        cfg.uiMode = (cfg.uiMode & ~mask)
                | (pref == Prefs.THEME_DARK ? Configuration.UI_MODE_NIGHT_YES
                                            : Configuration.UI_MODE_NIGHT_NO);
        super.attachBaseContext(base.createConfigurationContext(cfg));
    }

    @Override
    protected void onCreate(Bundle b) {
        // 状态栏跟随深浅
        setTheme(isDark() ? R.style.AppTheme_Dark : R.style.AppTheme);
        super.onCreate(b);
    }

    @Override
    protected void onResume() {
        super.onResume();
        expiredShown = false;

        // 主题是在 attachBaseContext 里按偏好改写 uiMode 的，**系统并不知道**，
        // 所以改了偏好之后系统不会帮我们重建 Activity。
        // 结果就是：在设置页切了深色，设置页自己 recreate 了、变了色，
        // 但压在后面的主页还是旧配色 —— 也就是"设置改了，主页没变"。
        // 这里在每次回到前台时对一下账，不一致就重建自己。
        if (!recreatingForTheme && appliedThemeMode != Prefs.themeMode(this)) {
            recreatingForTheme = true;
            recreate();
        }
    }

    /** 改完主题偏好后调用：立刻按新主题重建自己。 */
    protected void reloadTheme() {
        recreatingForTheme = true;
        recreate();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_RELOGIN) {
            expiredShown = false;
            onRelogin(reloginShower);
        }
    }

    /**
     * 用户从过期弹窗跳去重新登录、又回到本页时调用。
     * 子类重写它来把页面状态恢复成「已登录」的样子。
     */
    protected void onRelogin(boolean shower) {
    }

    // ------------------------------------------------------------ 登录过期

    /**
     * 取水（慧生活798）登录过期处理。
     *
     * @return true 表示这个响应就是「过期」，调用方不必再报错。
     */
    protected boolean checkWater(Api.Resp r) {
        if (!Api.isExpired(r)) {
            return false;
        }
        return promptExpired(false);
    }

    /** 洗澡（胖乖生活）登录过期处理。 */
    protected boolean checkShower(PanggualApi.Resp r) {
        if (!PanggualApi.isExpired(r)) {
            return false;
        }
        return promptExpired(true);
    }

    /** 弹一次重登提示；返回 true 表示已经接管了这个错误。 */
    protected boolean promptExpired(final boolean shower) {
        if (expiredShown) {
            return true;
        }
        expiredShown = true;

        // 先把失效的 token 清掉，避免后面的轮询继续拿它去打接口
        if (shower) {
            Prefs.pgLogout(this);
        } else {
            Prefs.logout(this);
        }
        reloginShower = shower;

        new AlertDialog.Builder(this)
                .setTitle(R.string.token_expired_title)
                .setMessage(shower ? R.string.token_expired_shower : R.string.token_expired_water)
                .setCancelable(false)
                .setPositiveButton(R.string.token_relogin, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        startActivityForResult(new Intent(BaseActivity.this,
                                shower ? PanggualLoginActivity.class : LoginActivity.class),
                                REQ_RELOGIN);
                    }
                })
                .setNegativeButton(R.string.token_later, null)
                .show();
        return true;
    }
}
