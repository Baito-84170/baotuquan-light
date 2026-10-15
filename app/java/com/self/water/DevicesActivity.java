package com.self.water;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备选择页：按分组列出已记住的设备，支持搜索、添加、重命名、删除。
 * 点一行即切换，长按或点右侧「⋮」打开菜单。
 */
public class DevicesActivity extends BaseActivity {

    public static final String EXTRA_TYPE = "type";
    /** 直接打开扫码页（从洗澡页菜单进来时用）。 */
    public static final String EXTRA_OPEN_SCAN = "open_scan";

    private static final int REQ_SCAN = 1;
    private static final int REQ_LIST = 2;

    private String type = DeviceStore.TYPE_WATER;
    private ListView listView;
    private TextView emptyTv;
    private EditText searchEt;
    private Button btnAdd;

    /** 扁平化的行：String = 分组标题，Device = 设备。 */
    private final List<Object> rows = new ArrayList<>();
    /** 设备 id → 实时状态。 */
    private final Map<String, StatusChecker.Status> statuses = new LinkedHashMap<>();
    private RowAdapter adapter;
    private String query = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_devices);

        type = getIntent().getStringExtra(EXTRA_TYPE);
        if (type == null) {
            type = DeviceStore.TYPE_WATER;
        }

        TextView title = findViewById(R.id.devTitle);
        title.setText(DeviceStore.TYPE_SHOWER.equals(type)
                ? R.string.devices_title_shower : R.string.devices_title_water);

        listView = findViewById(R.id.devList);
        emptyTv = findViewById(R.id.devEmpty);
        searchEt = findViewById(R.id.devSearch);
        btnAdd = findViewById(R.id.btnAddDevice);

        adapter = new RowAdapter();
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Object o = rows.get(pos);
                if (o instanceof DeviceStore.Device) {
                    select((DeviceStore.Device) o);
                }
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                Object o = rows.get(pos);
                if (o instanceof DeviceStore.Device && v != null) {
                    // 长按改为拖动排序，菜单走右侧「⋮」或行尾按钮
                    startDeviceDrag(v, (DeviceStore.Device) o);
                }
                return true;
            }
        });

        findViewById(R.id.btnSort).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sortMenu();
            }
        });

        searchEt.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                reload();
            }
        });

        findViewById(R.id.btnAddDevice).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addMenu();
            }
        });

        findViewById(R.id.devClose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // 从洗澡页菜单直接进扫码
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_OPEN_SCAN, false)) {
            startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        rows.clear();
        final List<DeviceStore.Device> all = new ArrayList<>();
        Map<String, List<DeviceStore.Device>> grouped =
                DeviceStore.grouped(this, type, query);
        for (Map.Entry<String, List<DeviceStore.Device>> e : grouped.entrySet()) {
            rows.add(e.getKey());
            rows.addAll(e.getValue());
            all.addAll(e.getValue());
        }
        adapter.notifyDataSetChanged();

        boolean none = grouped.isEmpty();
        emptyTv.setVisibility(none ? View.VISIBLE : View.GONE);
        emptyTv.setText(query.length() > 0
                ? getString(R.string.devices_no_match)
                : getString(R.string.devices_empty));

        TextView sortHint = findViewById(R.id.devSortHint);
        sortHint.setText(getString(R.string.devices_sort_hint,
                DeviceStore.sortName(this, type)));

        // 异步查询实时状态
        statuses.clear();
        if (!all.isEmpty()) {
            StatusChecker.checkAll(this, type, all, new StatusChecker.Callback() {
                @Override
                public void onStatus(String deviceId, StatusChecker.Status s) {
                    statuses.put(deviceId, s);
                    adapter.notifyDataSetChanged();
                }
            });
        }
    }

    private void select(DeviceStore.Device d) {
        DeviceStore.setCurrent(this, type, d.id);
        DeviceStore.remember(this, type, d.id, d.group, d.sub);
        setResult(RESULT_OK, new Intent().putExtra("id", d.id));
        finish();
    }

    // ------------------------------------------------------ 菜单

    /** 排序方式选择。 */
    private void sortMenu() {
        final CharSequence[] names = {
                getString(R.string.devices_sort_recent),
                getString(R.string.devices_sort_name),
                getString(R.string.devices_sort_manual)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.devices_sort)
                .setSingleChoiceItems(names, DeviceStore.sortMode(this, type),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dlg, int which) {
                                DeviceStore.setSortMode(DevicesActivity.this, type, which);
                                dlg.dismiss();
                                reload();
                            }
                        })
                .show();
    }

    private void menu(final DeviceStore.Device d) {
        final List<String> labels = new ArrayList<>();
        labels.add(getString(d.pinned ? R.string.devices_unpin : R.string.devices_pin));
        labels.add(getString(R.string.devices_move_up));
        labels.add(getString(R.string.devices_move_down));
        labels.add(getString(R.string.devices_reparse));
        labels.add(getString(R.string.devices_rename));
        labels.add(getString(R.string.devices_delete));

        new AlertDialog.Builder(this)
                .setTitle(d.title())
                .setItems(labels.toArray(new CharSequence[0]),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dlg, int which) {
                                if (which == 0) {
                                    DeviceStore.setPinned(DevicesActivity.this, type, d.id,
                                            !d.pinned);
                                    reload();
                                } else if (which == 1 || which == 2) {
                                    int before = DeviceStore.indexOf(
                                            DevicesActivity.this, type, d.id);
                                    DeviceStore.move(DevicesActivity.this, type, d.id,
                                            which == 1 ? -1 : 1);
                                    // 已经到顶 / 到底就提示一下
                                    if (before == DeviceStore.indexOf(
                                            DevicesActivity.this, type, d.id)) {
                                        Toast.makeText(DevicesActivity.this,
                                                R.string.devices_move_edge,
                                                Toast.LENGTH_SHORT).show();
                                    }
                                    reload();
                                } else if (which == 3) {
                                    // 重新解析：先清缓存，再后台重取位置和 goodsId
                                    DeviceResolver.invalidate(type, d.id);
                                    DeviceStore.setExtra(DevicesActivity.this, type, d.id, "");
                                    Toast.makeText(DevicesActivity.this,
                                            "正在重新解析…", Toast.LENGTH_SHORT).show();
                                    resolveOnly(d.id);
                                } else if (which == 4) {
                                    renameDialog(d);
                                } else {
                                    deleteDialog(d);
                                }
                            }
                        })
                .show();
    }

    // ------------------------------------------------------ 拖动排序

    /** 开始拖动某一行。 */
    private void startDeviceDrag(View row, DeviceStore.Device d) {
        ClipData data = ClipData.newPlainText("device", d.id);
        View.DragShadowBuilder shadow = new View.DragShadowBuilder(row);
        row.startDrag(data, shadow, d, 0);
        Toast.makeText(this, R.string.devices_drag_hint, Toast.LENGTH_SHORT).show();
    }

    /** 把 from 拖到 to 的位置上。 */
    private void dropOn(DeviceStore.Device from, DeviceStore.Device to) {
        if (from == null || to == null || from.id.equals(to.id)) {
            return;
        }
        List<DeviceStore.Device> vis = new ArrayList<>();
        for (Object o : rows) {
            if (o instanceof DeviceStore.Device) {
                vis.add((DeviceStore.Device) o);
            }
        }
        int a = -1;
        int b = -1;
        for (int i = 0; i < vis.size(); i++) {
            if (vis.get(i).id.equals(from.id)) {
                a = i;
            }
            if (vis.get(i).id.equals(to.id)) {
                b = i;
            }
        }
        if (a < 0 || b < 0) {
            return;
        }
        DeviceStore.Device moved = vis.remove(a);
        vis.add(b, moved);
        DeviceStore.commitOrder(this, type, vis);
        DeviceStore.setSortMode(this, type, DeviceStore.SORT_MANUAL);
        reload();
    }

    private void renameDialog(final DeviceStore.Device d) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.devices_name_hint);
        input.setText(d.name == null ? "" : d.name);
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
                .setTitle(R.string.devices_rename)
                .setView(pad(input))
                .setPositiveButton(R.string.dlg_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        DeviceStore.rename(DevicesActivity.this, type, d.id,
                                input.getText().toString().trim());
                        reload();
                    }
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    private void deleteDialog(final DeviceStore.Device d) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.devices_delete_confirm, d.title()))
                .setPositiveButton(R.string.devices_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        DeviceStore.remove(DevicesActivity.this, type, d.id);
                        reload();
                    }
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    /**
     * 添加设备。
     *
     * 这个页面现在**只服务取水**。洗澡那边改成了「先选楼号、再按层列出机器」的
     * 直播浏览页（`ShowerDevicePickerActivity`），因为胖乖的机器列表能完整拉到、
     * 而且机器二维码印在低分辨率点阵 LCD 上根本扫不出来 ——
     * 所以手动输入 / 批量粘贴 / 扫码这一整套在洗澡侧都删掉了。
     *
     * 取水（慧生活798）没有对应的机器列表接口，而且机器二维码是印上去的标准码、
     * 扫得出来，所以扫码和手动输入在这里保留。
     */
    private void addMenu() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.devices_add_menu)
                .setItems(new CharSequence[]{
                        getString(R.string.devices_add_scan),
                        getString(R.string.devices_add_manual),
                        getString(R.string.devices_add_batch),
                        getString(R.string.devices_export)}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        if (which == 0) {
                            startActivityForResult(new Intent(DevicesActivity.this,
                                    ScanActivity.class), REQ_SCAN);
                        } else if (which == 1) {
                            manualAdd();
                        } else if (which == 2) {
                            batchAdd();
                        } else {
                            exportLibrary();
                        }
                    }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_LIST) {
            // 「按列表添加」已经自己写进设备库了，这里只要刷新
            reload();
            return;
        }
        if (req == REQ_SCAN && res == RESULT_OK && data != null) {
            String text = data.getStringExtra(ScanActivity.EXTRA_RESULT);
            if (text == null || text.length() == 0) {
                return;
            }
            String id = extractId(text);
            if (id.length() > 0) {
                addAndResolve(id);
            } else {
                // 认不出来就明说，别把 URL 里抠出来的乱码当设备加进去。
                // 之前是静默 fallback 成"去掉所有非字母数字"，一串 URL 会变成
                // 像 httpsh5qiekjcomskip 这样的垃圾编号，加进去既没名字也没状态。
                new AlertDialog.Builder(this)
                        .setTitle("没认出这台设备")
                        .setMessage("扫到的内容里找不到设备编号。\n\n原始内容：\n" + text
                                + "\n\n（把这段发给开发者，我来加对应的解析规则）")
                        .setPositiveButton(R.string.dlg_ok, null)
                        .show();
            }
        }
    }

    /**
     * 从扫码结果里抽出设备编号。
     *
     * **认不出来就返回空串**，不做"去掉所有非字母数字"这种兜底 ——
     * 那种兜底会把整段 URL 变成一串乱码编号，静默加进设备库，
     * 表现就是"扫码添加了但没名字、状态一直是 —"。宁可当场报错。
     *
     * 两种码的经验证格式：
     *   取水  https://i.ilife798.com/q/1/b?id=扫码得到的设备编号
     *   洗澡  https://h5.qiekj.com/skip?SN=扫码得到的序列号
     */
    private String extractId(String raw) {
        String s = null;
        if (DeviceStore.TYPE_SHOWER.equals(type)) {
            s = paramOf(raw, "SN=");
        } else {
            s = paramOf(raw, "id=");
            if (s == null && raw.toLowerCase().contains("/q/")) {
                // 兜底：路径最后一段里的数字
                String[] parts = raw.split("/");
                s = parts[parts.length - 1];
            }
        }
        if (s == null) {
            return "";
        }
        s = s.trim();
        // 只接受纯数字的设备编号：两种码经验证都是 15 位数字
        if (!s.matches("\\d{6,32}")) {
            return "";
        }
        return s;
    }

    /** 取 URL 查询参数的值；找不到返回 null。 */
    private static String paramOf(String raw, String key) {
        int i = raw.toUpperCase().indexOf(key.toUpperCase());
        if (i < 0) {
            return null;
        }
        String s = raw.substring(i + key.length());
        int amp = s.indexOf('&');
        if (amp >= 0) {
            s = s.substring(0, amp);
        }
        return s;
    }

    /** 加入列表并在后台解析出位置信息。 */
    private void addAndResolve(final String id) {
        DeviceStore.remember(this, type, id, "", "");
        DeviceStore.setCurrent(this, type, id);
        // 重新扫同一台设备时，先把上次可能留下的失败缓存清掉
        DeviceResolver.invalidate(type, id);
        reload();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONObject res = DeviceResolver.resolve(DevicesActivity.this, type, id);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (res != null) {
                            DeviceStore.remember(DevicesActivity.this, type, id,
                                    res.optString("group", ""), res.optString("sub", ""));
                            DeviceStore.setExtra(DevicesActivity.this, type, id,
                                    res.optString("extra", ""));
                            reload();
                        } else {
                            // 解析失败要说清原因，否则用户只会看到一台没名字的设备
                            reload();
                            explainResolveFailure(id);
                        }
                    }
                });
            }
        }).start();
    }

    /** 只重新解析一台设备（不动列表顺序、不设成当前设备）。 */
    private void resolveOnly(final String id) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONObject res = DeviceResolver.resolve(DevicesActivity.this, type, id);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (res != null) {
                            DeviceStore.remember(DevicesActivity.this, type, id,
                                    res.optString("group", ""), res.optString("sub", ""));
                            DeviceStore.setExtra(DevicesActivity.this, type, id,
                                    res.optString("extra", ""));
                            reload();
                            Toast.makeText(DevicesActivity.this,
                                    "已解析：" + res.optString("sub", ""),
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            reload();
                            explainResolveFailure(id);
                        }
                    }
                });
            }
        }).start();
    }

    /** 解析失败时告诉用户为什么、以及怎么办。 */
    private void explainResolveFailure(final String id) {
        boolean shower = DeviceStore.TYPE_SHOWER.equals(type);
        String why;
        if (shower && !Prefs.pgLoggedIn(this)) {
            why = "还没登录胖乖生活账号。设备要登录之后才能解析出位置和编号。\n\n"
                    + "去「洗澡」页登录一次，然后回到这里长按设备选「重新解析」。";
        } else if (!shower && !Prefs.loggedIn(this)) {
            why = "还没登录慧生活798账号。\n\n去「取水」页登录一次，"
                    + "然后回到这里长按设备选「重新解析」。";
        } else {
            why = "服务端没能解析出这台设备。可能是网络问题、登录状态过期，"
                    + "或者这个编号在服务端查不到。\n\n"
                    + "设备已经加进列表了，长按它选「重新解析」可以再试一次。";
        }
        new AlertDialog.Builder(this)
                .setTitle("设备已加入，但没解析出位置")
                .setMessage("设备编号：" + id + "\n\n" + why)
                .setPositiveButton(R.string.dlg_ok, null)
                .show();
    }

    private void manualAdd() {
        final EditText idEt = new EditText(this);
        idEt.setHint(DeviceStore.TYPE_SHOWER.equals(type)
                ? "机器 SN" : "设备编号");
        idEt.setInputType(InputType.TYPE_CLASS_TEXT);

        final EditText nameEt = new EditText(this);
        nameEt.setHint(R.string.devices_name_hint);
        nameEt.setInputType(InputType.TYPE_CLASS_TEXT);

        final EditText groupEt = new EditText(this);
        groupEt.setHint(R.string.devices_add_group_hint);
        groupEt.setInputType(InputType.TYPE_CLASS_TEXT);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, (int) (pad * 0.5), pad, 0);
        box.addView(idEt);
        box.addView(nameEt);
        box.addView(groupEt);

        new AlertDialog.Builder(this)
                .setTitle(R.string.devices_add_menu)
                .setView(box)
                .setPositiveButton(R.string.dlg_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        String raw = idEt.getText().toString().trim();
                        if (raw.length() == 0) {
                            return;
                        }
                        String id = extractId(raw);
                        DeviceStore.add(DevicesActivity.this, type, id,
                                nameEt.getText().toString().trim(),
                                groupEt.getText().toString().trim());
                        addAndResolve(id);
                    }
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    /** 批量粘贴：每行一条，逐条解析。 */
    private void batchAdd() {
        final EditText input = new EditText(this);
        input.setHint(R.string.batch_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(6);
        input.setGravity(android.view.Gravity.TOP);

        LinearLayout box = new LinearLayout(this);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, (int) (pad * 0.5), pad, 0);
        box.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(R.string.batch_title)
                .setView(box)
                .setPositiveButton(R.string.dlg_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        doBatch(input.getText().toString());
                    }
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    private void doBatch(String text) {
        final List<String> ids = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            String id = extractId(line.trim());
            if (id.length() >= 6 && !ids.contains(id)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return;
        }

        btnAdd.setEnabled(false);
        final int[] progress = {0, 0, 0};   // 当前, 成功, 失败

        new Thread(new Runnable() {
            @Override
            public void run() {
                for (String id : ids) {
                    progress[0]++;
                    final int cur = progress[0];
                    final int total = ids.size();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            searchEt.setText("");
                            emptyTv.setVisibility(View.VISIBLE);
                            emptyTv.setText(getString(R.string.batch_working, cur, total));
                        }
                    });

                    DeviceStore.remember(DevicesActivity.this, type, id, "", "");
                    JSONObject res = DeviceResolver.resolve(DevicesActivity.this, type, id);
                    if (res != null) {
                        DeviceStore.remember(DevicesActivity.this, type, id,
                                res.optString("group", ""), res.optString("sub", ""));
                        DeviceStore.setExtra(DevicesActivity.this, type, id,
                                res.optString("extra", ""));
                        progress[1]++;
                    } else {
                        progress[2]++;
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnAdd.setEnabled(true);
                        reload();
                        Toast.makeText(DevicesActivity.this,
                                getString(R.string.batch_done, progress[1], progress[2]),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    /** 导出设备库为 JSON，方便交给开发者内置。 */
    private void exportLibrary() {
        List<DeviceStore.Device> list = DeviceStore.list(this, type);
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (DeviceStore.Device d : list) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("id", d.id);
                o.put("name", d.name == null ? "" : d.name);
                o.put("group", d.group == null ? "" : d.group);
                o.put("sub", d.sub == null ? "" : d.sub);
                o.put("extra", d.extra == null ? "" : d.extra);
                arr.put(o);
            }
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("type", type);
            root.put("exportedAt", System.currentTimeMillis());
            root.put("devices", arr);

            String json = root.toString(2);
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("devices", json));

            new AlertDialog.Builder(this)
                    .setTitle(R.string.export_title)
                    .setMessage(getString(R.string.export_done, list.size())
                            + "\n\n" + json)
                    .setPositiveButton(R.string.dlg_ok, null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout pad(View v) {
        LinearLayout box = new LinearLayout(this);
        int p = (int) (getResources().getDisplayMetrics().density * 20);
        box.setPadding(p, p / 2, p, 0);
        box.addView(v);
        return box;
    }

    // ------------------------------------------------------ 列表适配器

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
            return rows.get(position) instanceof DeviceStore.Device;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof DeviceStore.Device ? 1 : 0;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Object o = rows.get(position);
            LayoutInflater inf = LayoutInflater.from(DevicesActivity.this);

            if (o instanceof String) {
                View v = convert != null ? convert : inf.inflate(R.layout.item_group, parent, false);
                ((TextView) v.findViewById(R.id.groupName)).setText((String) o);
                return v;
            }

            final DeviceStore.Device d = (DeviceStore.Device) o;
            View v = convert != null ? convert : inf.inflate(R.layout.item_device, parent, false);

            boolean current = d.id.equals(DeviceStore.currentId(DevicesActivity.this, type));
            TextView check = v.findViewById(R.id.itemCheck);
            check.setText(current ? "✓" : "");

            ((TextView) v.findViewById(R.id.itemTitle)).setText(
                    d.pinned ? "★ " + d.title() : d.title());
            ((TextView) v.findViewById(R.id.itemSub)).setText(d.subtitle());

            // 实时状态徽章
            TextView st = v.findViewById(R.id.itemStatus);
            StatusChecker.Status s = statuses.get(d.id);
            if (s == null) {
                st.setVisibility(View.GONE);
            } else {
                st.setVisibility(View.VISIBLE);
                st.setText(s.label);
                st.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(
                                getColor(s.bgRes)));
                st.setTextColor(getColor(s.fgRes));
            }

            v.findViewById(R.id.itemMenu).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    menu(d);
                }
            });

            // 拖动排序：拖到哪一行，就插到那一行的位置上
            v.setOnDragListener(new View.OnDragListener() {
                @Override
                public boolean onDrag(View view, DragEvent e) {
                    if (e.getAction() == DragEvent.ACTION_DROP) {
                        Object local = e.getLocalState();
                        if (local instanceof DeviceStore.Device) {
                            dropOn((DeviceStore.Device) local, d);
                        }
                    }
                    return true;
                }
            });
            return v;
        }
    }
}
