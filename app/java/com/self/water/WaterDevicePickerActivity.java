package com.self.water;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 取水设备选择页：按**站点**分组列出名单。
 *
 * ⚠️ 和洗澡不一样，798 **没有"列出整栋楼所有机器"的接口**：
 *   - `/api/v1/dev/near` 只给 {name, distance}，没有设备 id，选不了；
 *   - `/api/v1/ep/station/lst` 只到"站点"这一层，下面没有再列设备的口子。
 *
 * 所以这里的名单来自 **`/api/v1/ui/app/master` 的 `data.favos`** ——
 * 也就是你在官方「慧生活798」里**收藏（关注）过的设备**。
 *
 * 名单为空时页面会明说这一点，并给出两条路：去官方 App 收藏，或者在这边扫一次码
 * （取水的码是印上去的标准二维码，扫得出来）然后点「收藏到官方账号」。
 */
public class WaterDevicePickerActivity extends BaseActivity {

    private TextView status;
    private ListView list;

    private final List<Object> rows = new ArrayList<>();
    private final List<Api.WaterDevice> devices = new ArrayList<>();
    private String currentId = "";
    private String lastRaw = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_shower_picker);

        // 取水没有"楼号"这一层，把那一行藏掉，其余复用同一套布局
        findViewById(R.id.pkBuildings).setVisibility(View.GONE);
        ((TextView) findViewById(R.id.pkStatus)).setText(R.string.picker_loading);

        status = findViewById(R.id.pkStatus);
        list = findViewById(R.id.pkList);

        currentId = DeviceStore.currentId(this, DeviceStore.TYPE_WATER);

        list.setAdapter(new RowAdapter());
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Object o = rows.get(pos);
                if (o instanceof Api.WaterDevice) {
                    select((Api.WaterDevice) o);
                }
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                Object o = rows.get(pos);
                if (o instanceof Api.WaterDevice) {
                    Toast.makeText(WaterDevicePickerActivity.this,
                            ((Api.WaterDevice) o).id + "（已在官方账号的收藏里）",
                            Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });

        findViewById(R.id.pkRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });
        findViewById(R.id.pkClose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // 扫码收藏：798 的机器码是印上去的标准二维码，扫得出来。
        // 扫到之后自动收藏到官方账号 —— 这样以后它就出现在这个名单里了，
        // 不用再去官方 App 里手动关注。收藏完直接选中，扫一次就能用。
        Button scanFav = findViewById(R.id.pkScanFav);
        scanFav.setVisibility(View.VISIBLE);
        scanFav.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(WaterDevicePickerActivity.this,
                        R.string.water_fav_scan_hint, Toast.LENGTH_LONG).show();
                startActivityForResult(
                        new Intent(WaterDevicePickerActivity.this, ScanActivity.class), REQ_SCAN);
            }
        });

        if (!Prefs.loggedIn(this)) {
            status.setText(R.string.picker_need_login_water);
            scanFav.setEnabled(false);
            return;
        }
        load();
    }

    private static final int REQ_SCAN = 1;

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SCAN || res != RESULT_OK || data == null) {
            return;
        }
        String text = data.getStringExtra(ScanActivity.EXTRA_RESULT);
        String id = extractWaterId(text);
        if (id.length() == 0) {
            // 认不出来就明说，别把 URL 里的乱码当编号
            new AlertDialog.Builder(this)
                    .setTitle(R.string.water_scan_bad)
                    .setMessage(getString(R.string.water_scan_bad, text))
                    .setPositiveButton(R.string.dlg_ok, null)
                    .show();
            return;
        }
        favoThenSelect(id);
    }

    /**
     * 收藏到官方账号，然后直接选中这台。
     *
     * 收藏失败也不拦着用 —— 设备本身是有效的，收藏只是"下次还在名单里"的便利。
     * 所以失败时如实说明，但仍然把它选中。
     */
    private void favoThenSelect(final String id) {
        status.setText(R.string.picker_loading);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Api.Resp r = Api.favo(id, false,
                        Prefs.token(WaterDevicePickerActivity.this));
                final boolean ok = r != null && r.ok()
                        && r.json().optInt("code", -999) == 0;
                final String why = r == null ? "无响应"
                        : (r.err != null ? r.err : r.json().optString("msg", r.body));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(WaterDevicePickerActivity.this,
                                ok ? getString(R.string.water_fav_ok)
                                   : getString(R.string.water_fav_fail, why),
                                Toast.LENGTH_LONG).show();
                        // 不管收藏成没成，都选中它并回到取水页
                        DeviceStore.remember(WaterDevicePickerActivity.this,
                                DeviceStore.TYPE_WATER, id, "", "");
                        DeviceResolver.invalidate(DeviceStore.TYPE_WATER, id);
                        DeviceStore.setCurrent(WaterDevicePickerActivity.this,
                                DeviceStore.TYPE_WATER, id);
                        setResult(RESULT_OK, new Intent().putExtra("id", id));
                        finish();
                    }
                });
            }
        }).start();
    }

    /** 从扫码结果里抽设备编号；认不出来返回空串。 */
    private static String extractWaterId(String raw) {
        if (raw == null || raw.length() == 0) {
            return "";
        }
        // 取水：https://i.ilife798.com/q/1/b?id=扫码得到的设备编号
        int i = raw.toLowerCase().indexOf("id=");
        String s = null;
        if (i >= 0) {
            s = raw.substring(i + 3);
            int amp = s.indexOf('&');
            if (amp >= 0) {
                s = s.substring(0, amp);
            }
        } else if (raw.toLowerCase().contains("/q/")) {
            String[] parts = raw.split("/");
            s = parts[parts.length - 1];
        }
        if (s == null) {
            return "";
        }
        s = s.trim();
        // 只接受纯数字编号 —— 取水经验证是 15 位数字
        return s.matches("\\d{6,32}") ? s : "";
    }

    private void load() {
        status.setText(R.string.picker_loading);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Api.Resp r = Api.master(Prefs.token(WaterDevicePickerActivity.this));
                final List<Api.WaterDevice> ds = Api.parseFavos(r);
                final String raw = r == null || r.body == null ? "" : r.body;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        lastRaw = raw;
                        devices.clear();
                        devices.addAll(ds);
                        rebuild();
                    }
                });
            }
        }).start();
    }

    /** 按站点分组。 */
    private void rebuild() {
        rows.clear();
        if (devices.isEmpty()) {
            ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
            status.setText(getString(R.string.water_picker_empty));
            return;
        }
        Map<String, List<Api.WaterDevice>> bySite = new LinkedHashMap<>();
        for (Api.WaterDevice d : devices) {
            String site = d.site.length() > 0 ? d.site : "未分组";
            List<Api.WaterDevice> bucket = bySite.get(site);
            if (bucket == null) {
                bucket = new ArrayList<>();
                bySite.put(site, bucket);
            }
            bucket.add(d);
        }
        int idle = 0;
        for (Map.Entry<String, List<Api.WaterDevice>> e : bySite.entrySet()) {
            rows.add(e.getKey());
            List<Api.WaterDevice> bucket = e.getValue();
            Collections.sort(bucket, new java.util.Comparator<Api.WaterDevice>() {
                @Override
                public int compare(Api.WaterDevice a, Api.WaterDevice b) {
                    return a.title().compareToIgnoreCase(b.title());
                }
            });
            for (Api.WaterDevice d : bucket) {
                rows.add(d);
                if (d.idle()) {
                    idle++;
                }
            }
        }
        ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
        status.setText(getString(R.string.water_picker_summary,
                devices.size(), idle, bySite.size()));
    }

    private void select(Api.WaterDevice d) {
        String group = d.site.length() > 0 ? d.site : "未分组";
        String sub = (d.model.length() > 0 ? d.model : d.name);
        DeviceStore.remember(this, DeviceStore.TYPE_WATER, d.id, group, sub);
        DeviceResolver.invalidate(DeviceStore.TYPE_WATER, d.id);
        DeviceStore.setCurrent(this, DeviceStore.TYPE_WATER, d.id);
        setResult(RESULT_OK, new android.content.Intent().putExtra("id", d.id));
        finish();
    }

    private class RowAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return rows.get(position) instanceof Api.WaterDevice;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof Api.WaterDevice ? 1 : 0;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Object o = rows.get(position);

            if (!(o instanceof Api.WaterDevice)) {
                TextView t = convert instanceof TextView ? (TextView) convert
                        : new TextView(WaterDevicePickerActivity.this);
                t.setText((String) o);
                t.setTextSize(13f);
                t.setTypeface(null, Typeface.BOLD);
                t.setTextColor(getColor(R.color.text_sub));
                t.setGravity(Gravity.START);
                int v = Math.round(getResources().getDisplayMetrics().density * 14);
                t.setPadding(0, v, 0, v / 2);
                return t;
            }

            View vw = (convert != null && convert.getId() == android.R.id.text1) ? convert
                    : getLayoutInflater().inflate(
                            android.R.layout.simple_list_item_2, parent, false);
            Api.WaterDevice d = (Api.WaterDevice) o;

            TextView t1 = vw.findViewById(android.R.id.text1);
            TextView t2 = vw.findViewById(android.R.id.text2);

            boolean isCurrent = d.id.equals(currentId);
            t1.setText((isCurrent ? "✓ " : "") + d.title() + "    " + d.statusText());
            t1.setTextColor(getColor(d.idle() ? R.color.ok
                    : d.busy() ? R.color.warn : R.color.text));
            t1.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);
            t2.setText(isCurrent ? "当前使用这一台" : d.id);
            return vw;
        }
    }
}
