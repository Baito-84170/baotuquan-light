package com.self.water;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 洗澡设备选择页：**先选楼号 → 按层列出该楼所有机器**。
 *
 * 为什么改成这样：机器列表已经能完整拉到了（`appointNew/point/listByShopId` 给楼栋，
 * `appointNew/near/newMachines` 给机器），所以原来那套"本地添加设备库"的做法
 * （手动输入编号、批量粘贴、扫码）就多余了 —— 尤其是机器二维码印在低分辨率点阵
 * LCD 上、几乎扫不出来。
 *
 * 现在不再往本地"添加"设备：进来就是实时的机器总表，选中的那台记成当前设备。
 * 好处是**状态永远是新的** —— 空闲/使用中是每次打开现查的，不是存下来的。
 *
 * 命名规则（经验证）：`30601` = 3号楼 · 6层 · 01号，所以层号取自名字的第 2-3 位。
 */
public class ShowerDevicePickerActivity extends BaseActivity {

    private TextView status;
    private LinearLayout buildingBox;
    private ListView list;

    private List<PanggualApi.Building> buildings = new ArrayList<>();
    private String curBuildingId = "";

    /** 扁平行：String = 楼层标题，Machine = 一台机器。 */
    private final List<Object> rows = new ArrayList<>();
    private final List<PanggualApi.Machine> machines = new ArrayList<>();

    private String currentDeviceId = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_shower_picker);

        status = findViewById(R.id.pkStatus);
        buildingBox = findViewById(R.id.pkBuildings);
        list = findViewById(R.id.pkList);

        currentDeviceId = DeviceStore.currentId(this, DeviceStore.TYPE_SHOWER);

        list.setAdapter(new RowAdapter());
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Object o = rows.get(pos);
                if (o instanceof PanggualApi.Machine) {
                    select((PanggualApi.Machine) o);
                }
            }
        });

        // 扫码收藏是取水专用的 —— 胖乖的机器码印在低分辨率点阵 LCD 上，扫不出来。
        // 洗澡这里的机器名单本来就是完整的，也不需要收藏。
        findViewById(R.id.pkScanFav).setVisibility(View.GONE);

        findViewById(R.id.pkRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ShowerMachines.invalidate();
                loadBuildings();
            }
        });
        findViewById(R.id.pkClose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        if (!Prefs.pgLoggedIn(this)) {
            status.setText(getString(R.string.picker_need_login));
            return;
        }
        loadBuildings();
    }

    // ---------------------------------------------------------- 加载

    private void loadBuildings() {
        status.setText(R.string.picker_loading);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<PanggualApi.Building> bs = PanggualApi.buildings(
                        ShowerMachines.SHOP_ID, ShowerMachines.TYPE_SHOWER,
                        Prefs.pgToken(ShowerDevicePickerActivity.this));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        buildings = bs;
                        if (bs.isEmpty()) {
                            status.setText(R.string.picker_no_buildings);
                            buildingBox.removeAllViews();
                            return;
                        }
                        curBuildingId = defaultBuildingId(bs);
                        buildBuildingChips();
                        loadMachines();
                    }
                });
            }
        }).start();
    }

    /** 默认选当前设备所在那栋；找不到就用第一栋。 */
    private String defaultBuildingId(List<PanggualApi.Building> bs) {
        String curName = "";
        for (DeviceStore.Device d : DeviceStore.list(this, DeviceStore.TYPE_SHOWER)) {
            if (d.id.equals(currentDeviceId)) {
                curName = d.sub == null ? "" : d.sub;
                break;
            }
        }
        String want = ShowerMachines.buildingLabel(curName);
        if (want.length() > 0) {
            for (PanggualApi.Building x : bs) {
                if (x.name.contains(want) || want.contains(x.name)) {
                    return x.orgId;
                }
            }
        }
        return bs.get(0).orgId;
    }

    /** 楼号做成一行可点的胶囊。 */
    private void buildBuildingChips() {
        buildingBox.removeAllViews();
        for (final PanggualApi.Building b : buildings) {
            boolean on = b.orgId.equals(curBuildingId);
            Button chip = new Button(this);
            chip.setText(b.label());
            chip.setAllCaps(false);
            chip.setTextSize(14f);
            chip.setBackground(getDrawable(on ? R.drawable.btn_primary : R.drawable.btn_outline));
            chip.setTextColor(getColor(on ? R.color.white : R.color.primary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
            lp.setMargins(0, 0, dp(8), 0);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!b.orgId.equals(curBuildingId)) {
                        curBuildingId = b.orgId;
                        buildBuildingChips();
                        loadMachines();
                    }
                }
            });
            buildingBox.addView(chip);
        }
    }

    private void loadMachines() {
        status.setText(R.string.picker_loading_machines);
        final String org = curBuildingId;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<PanggualApi.Machine> ms = PanggualApi.pointMachines(
                        ShowerMachines.SHOP_ID, ShowerMachines.TYPE_SHOWER, org,
                        1, 200, Prefs.pgToken(ShowerDevicePickerActivity.this));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        machines.clear();
                        machines.addAll(ms);
                        rebuildRows();
                    }
                });
            }
        }).start();
    }

    /** 按层分组。层号从名字里取（"30601" → 6 层）。 */
    private void rebuildRows() {
        rows.clear();
        if (machines.isEmpty()) {
            ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
            status.setText(getString(R.string.picker_no_machines, nameOf(curBuildingId)));
            return;
        }

        Map<String, List<PanggualApi.Machine>> byFloor = new LinkedHashMap<>();
        for (PanggualApi.Machine m : machines) {
            String floor = ShowerMachines.floorLabel(m.name);
            if (floor.length() == 0) {
                floor = "其它";
            }
            List<PanggualApi.Machine> bucket = byFloor.get(floor);
            if (bucket == null) {
                bucket = new ArrayList<>();
                byFloor.put(floor, bucket);
            }
            bucket.add(m);
        }

        // 高层在前 —— 浴室都在高层，用户大概率住上面
        List<String> floors = new ArrayList<>(byFloor.keySet());
        Collections.sort(floors, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return floorNum(b) - floorNum(a);
            }
        });

        int free = 0;
        for (int i = 0; i < floors.size(); i++) {
            String f = floors.get(i);
            rows.add(f);
            List<PanggualApi.Machine> bucket = byFloor.get(f);
            Collections.sort(bucket, new java.util.Comparator<PanggualApi.Machine>() {
                @Override
                public int compare(PanggualApi.Machine a, PanggualApi.Machine b) {
                    return a.name.compareToIgnoreCase(b.name);
                }
            });
            for (PanggualApi.Machine m : bucket) {
                rows.add(m);
                if (m.idle()) {
                    free++;
                }
            }
        }
        ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
        status.setText(getString(R.string.picker_summary,
                nameOf(curBuildingId), machines.size(), free));
    }

    private static int floorNum(String label) {
        if (label == null || label.length() < 2) {
            return -1;
        }
        try {
            return Integer.parseInt(label.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return -1;
        }
    }

    private String nameOf(String orgId) {
        for (PanggualApi.Building b : buildings) {
            if (b.orgId.equals(orgId)) {
                return b.label();
            }
        }
        return "";
    }

    // ---------------------------------------------------------- 选择

    private void select(PanggualApi.Machine m) {
        if (m.busy()) {
            Toast.makeText(this, getString(R.string.picker_busy_warn, m.name),
                    Toast.LENGTH_LONG).show();
            // 不阻止 —— 也许那是自己正在用的那一台
        }
        String floor = ShowerMachines.floorLabel(m.name);
        String group = nameOf(curBuildingId) + (floor.length() > 0 ? " " + floor : "");
        DeviceStore.remember(this, DeviceStore.TYPE_SHOWER, m.goodsId, group, m.name);
        DeviceStore.setExtra(this, DeviceStore.TYPE_SHOWER, m.goodsId, m.goodsId);
        DeviceResolver.invalidate(DeviceStore.TYPE_SHOWER, m.goodsId);
        DeviceStore.setCurrent(this, DeviceStore.TYPE_SHOWER, m.goodsId);

        setResult(RESULT_OK, new android.content.Intent().putExtra("id", m.goodsId));
        finish();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ---------------------------------------------------------- 列表

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
            return rows.get(position) instanceof PanggualApi.Machine;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof PanggualApi.Machine ? 1 : 0;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Object o = rows.get(position);

            if (!(o instanceof PanggualApi.Machine)) {
                TextView t = convert instanceof TextView ? (TextView) convert
                        : new TextView(ShowerDevicePickerActivity.this);
                t.setText((String) o);
                t.setTextSize(13f);
                t.setTypeface(null, Typeface.BOLD);
                t.setTextColor(getColor(R.color.text_sub));
                t.setGravity(Gravity.START);
                t.setPadding(0, dp(14), 0, dp(6));
                return t;
            }

            View v = (convert != null && convert.getId() == android.R.id.text1) ? convert
                    : getLayoutInflater().inflate(
                            android.R.layout.simple_list_item_2, parent, false);
            PanggualApi.Machine m = (PanggualApi.Machine) o;

            TextView t1 = v.findViewById(android.R.id.text1);
            TextView t2 = v.findViewById(android.R.id.text2);

            boolean isCurrent = m.goodsId.equals(currentDeviceId);
            t1.setText((isCurrent ? "✓ " : "") + m.name
                    + "    " + m.statusText());
            t1.setTextColor(getColor(m.idle() ? R.color.ok
                    : m.busy() ? R.color.warn : R.color.text));
            t1.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);

            t2.setText(isCurrent
                    ? "当前使用这一台"
                    : ("goodsId " + m.goodsId + (m.isReserve == 0 ? "  ·  可预约" : "")));
            return v;
        }
    }
}
