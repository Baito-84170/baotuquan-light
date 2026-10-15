package com.self.water;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * 桌面小组件：一行三个胶囊，点哪个直接进对应服务。
 *
 * 两件事值得记下来：
 *
 * 1. **配色不能跟着主题走。** 桌面是独立进程，按自己的配置渲染 RemoteViews，
 *    而且不会因为我们 App 内改了主题就重画 —— 实机上出现过"系统已经是浅色、
 *    小组件还留着夜里那套深色"。所以样式改成由用户显式选，存在 Prefs 里。
 *
 * 2. **改了样式必须主动刷新。** 光写 Prefs 桌面不会重画，得调
 *    {@link #refreshAll(Context)} 推一次 —— 设置页改完就调它。
 */
public class WidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        RemoteViews v = build(c);
        for (int id : ids) {
            m.updateAppWidget(id, v);
        }
    }

    /** 让桌面上所有本组件实例按当前样式重画。设置页改样式后调用。 */
    public static void refreshAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, WidgetProvider.class));
        if (ids == null || ids.length == 0) {
            return;
        }
        RemoteViews v = build(c);
        for (int id : ids) {
            m.updateAppWidget(id, v);
        }
    }

    /** 供外部（和 onUpdate）复用。 */
    public static RemoteViews build(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_home);

        v.setOnClickPendingIntent(R.id.widgetWater, go(c, "water", 101));
        v.setOnClickPendingIntent(R.id.widgetShower, go(c, "shower", 102));
        v.setOnClickPendingIntent(R.id.widgetLaundry, go(c, "laundry", 103));

        // 按用户在设置里选的样式套配色
        int bg;
        int chip;
        int text;
        switch (Prefs.widgetStyle(c)) {
            case Prefs.WIDGET_LIGHT:
                bg = R.drawable.widget_bg_light;
                chip = R.drawable.widget_btn_light;
                text = 0xFF151A22;
                break;
            case Prefs.WIDGET_GLASS:
                bg = R.drawable.widget_bg_glass;
                chip = R.drawable.widget_btn_glass;
                // 玻璃是奶白半透明，文字要用深的才看得清
                text = 0xFF141A24;
                break;
            case Prefs.WIDGET_DARK:
            default:
                bg = R.drawable.widget_bg;
                chip = R.drawable.widget_btn;
                text = 0xFFEAF2FF;
                break;
        }

        v.setInt(R.id.widgetRoot, "setBackgroundResource", bg);
        v.setInt(R.id.widgetWater, "setBackgroundResource", chip);
        v.setInt(R.id.widgetShower, "setBackgroundResource", chip);
        v.setInt(R.id.widgetLaundry, "setBackgroundResource", chip);

        v.setTextColor(R.id.widgetWaterLabel, text);
        v.setTextColor(R.id.widgetShowerLabel, text);
        v.setTextColor(R.id.widgetLaundryLabel, text);

        return v;
    }

    private static PendingIntent go(Context c, String action, int code) {
        Intent i = new Intent(c, HomeActivity.class);
        // 三个 PendingIntent 的 Intent 必须互不相同，否则会被覆盖成同一个
        i.setAction("com.self.water.widget." + action);
        i.putExtra(HomeActivity.EXTRA_ACTION, action);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
