package com.self.water;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「我的订单」。
 *
 * ⚠️ 这一页踩过两次同一个坑，都记在这里：
 *
 *   1. `payTime` 为空 ≠ 未支付。订单已支付时，返回里**根本没有
 *      `payTime` 这个字段** —— 服务端序列化把 null 省掉了。
 *   2. `payType` 同样不在返回里。
 *
 * `OrderItemBean` 的 class 里有一堆字段（payTime / payType / activeTime / imei …），
 * 但 `/order/list` 只返回其中一部分。**看 class 字段推接口返回，是不行的。**
 *
 * 所以这页现在做三件事：只显示确实存在的字段、把"缺了哪些字段"明确标出来、
 * 原始 JSON 一键可复制。
 */
public class OrdersActivity extends BaseActivity {

    /** OrderItemBean 里有、但 /order/list 经验证不返回的字段 —— 列出来免得再被坑。 */
    private static final String[] EXPECTED_BUT_OFTEN_ABSENT = {
            "payTime", "payType", "activeTime", "imei", "markUnit", "payStatus"
    };

    /**
     * orderStatus 的文字。**只写已经用真实数据核实过的值。**
     * 2 是用户全部订单（都已支付）的状态，所以它就是"正常完成/已扣款"。
     * 其余值没核实过，一律显示数字，不编。
     */
    private static String statusLabel(int s) {
        if (s == 2) {
            return "已完成";
        }
        return "状态 " + s + "（含义未核实）";
    }

    private ListView listView;
    private TextView summary;
    private TextView empty;
    private Button refresh;

    private final List<PanggualApi.Order> orders = new ArrayList<>();
    private String lastRaw = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_orders);

        listView = findViewById(R.id.orderList);
        summary = findViewById(R.id.orderSummary);
        empty = findViewById(R.id.orderEmpty);
        refresh = findViewById(R.id.btnOrderRefresh);

        listView.setAdapter(new Adapter());

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                detail(orders.get(pos));
            }
        });

        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });

        findViewById(R.id.btnOrderRaw).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (lastRaw.length() == 0) {
                    Toast.makeText(OrdersActivity.this, "先刷新一次",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                copy("orders-raw", lastRaw);
                Toast.makeText(OrdersActivity.this,
                        "原始返回已复制", Toast.LENGTH_LONG).show();
            }
        });

        findViewById(R.id.orderClose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        load();
    }

    private void load() {
        if (!Prefs.pgLoggedIn(this)) {
            orders.clear();
            notifyList();
            empty.setVisibility(View.VISIBLE);
            empty.setText("还没登录胖乖账号。先去「洗澡」页登录一次。");
            summary.setText("");
            return;
        }
        refresh.setEnabled(false);
        summary.setText("正在拉取订单…");
        empty.setVisibility(View.GONE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final PanggualApi.Resp r = PanggualApi.orderList(
                        "1", "50", "0", Prefs.pgToken(OrdersActivity.this));
                final List<PanggualApi.Order> list = PanggualApi.parseOrders(r);
                final String raw = r.body == null ? "" : r.body;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refresh.setEnabled(true);
                        lastRaw = raw;
                        orders.clear();
                        orders.addAll(list);
                        notifyList();

                        if (r.err != null) {
                            summary.setText("拉取失败：" + r.err);
                            return;
                        }
                        if (list.isEmpty()) {
                            empty.setVisibility(View.VISIBLE);
                            empty.setText("没解析出订单。点「复制原始返回」把原文发我。\n\n"
                                    + "原始返回：\n" + raw);
                            summary.setText("");
                            return;
                        }
                        Map<Integer, Integer> byStatus = new LinkedHashMap<>();
                        for (PanggualApi.Order o : list) {
                            Integer c = byStatus.get(o.orderStatus);
                            byStatus.put(o.orderStatus, c == null ? 1 : c + 1);
                        }
                        StringBuilder sb = new StringBuilder();
                        sb.append("共 ").append(list.size()).append(" 笔");
                        for (Map.Entry<Integer, Integer> e : byStatus.entrySet()) {
                            sb.append("　").append(statusLabel(e.getKey()))
                              .append(" × ").append(e.getValue());
                        }
                        summary.setText(sb.toString());
                    }
                });
            }
        }).start();
    }

    private void notifyList() {
        ((BaseAdapter) listView.getAdapter()).notifyDataSetChanged();
    }

    private void copy(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    /** 把"class 里写了、但这个接口没返回"的字段列出来。 */
    private static String missing(String rawJson) {
        try {
            JSONObject o = new JSONObject(rawJson);
            StringBuilder sb = new StringBuilder();
            for (String k : EXPECTED_BUT_OFTEN_ABSENT) {
                if (!o.has(k)) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(k);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void detail(final PanggualApi.Order o) {
        String miss = missing(o.raw);
        StringBuilder sb = new StringBuilder();
        sb.append("orderStatus : ").append(o.orderStatus)
          .append("  →  ").append(statusLabel(o.orderStatus)).append("\n");
        sb.append("payPrice    : ").append(quote(o.payPrice)).append("\n");
        sb.append("markPrice   : ").append(quote(o.markPrice)).append("\n");
        sb.append("createTime  : ").append(quote(o.createTime)).append("\n");
        sb.append("machineName : ").append(quote(o.machineName)).append("\n");
        sb.append("markMinutes : ").append(o.markMinutes).append("\n");
        sb.append("orderNo     : ").append(o.orderNo).append("\n");
        if (miss.length() > 0) {
            sb.append("\n⚠️ 这个接口没有返回这些字段：\n").append(miss)
              .append("\n（class 里有，返回里没有 —— 别再拿它们做判断）\n");
        }
        sb.append("\n————— 原始 JSON —————\n").append(o.raw);

        new AlertDialog.Builder(this)
                .setTitle("订单详情（原始）")
                .setMessage(sb.toString())
                .setPositiveButton("复制这一段", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        copy("order", o.raw);
                        Toast.makeText(OrdersActivity.this, "已复制",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("打开胖乖生活", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Launcher.open(OrdersActivity.this, "pkg:com.qiekj.user");
                    }
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    private static String quote(String s) {
        return (s == null || s.length() == 0) ? "(空)" : s;
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return orders.size();
        }

        @Override
        public Object getItem(int position) {
            return orders.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            View v = convert != null ? convert
                    : getLayoutInflater().inflate(
                            android.R.layout.simple_list_item_2, parent, false);
            PanggualApi.Order o = orders.get(position);

            TextView t1 = v.findViewById(android.R.id.text1);
            TextView t2 = v.findViewById(android.R.id.text2);

            t1.setText(statusLabel(o.orderStatus)
                    + "   " + (o.payPrice.length() > 0 ? "¥" + o.payPrice : "¥—")
                    + "   " + shortTime(o.createTime));

            StringBuilder sb = new StringBuilder();
            if (o.machineName.length() > 0) {
                sb.append(o.machineName);
            }
            if (o.shopName.length() > 0) {
                if (sb.length() > 0) {
                    sb.append("  ·  ");
                }
                sb.append(o.shopName);
            }
            sb.append("\n").append(o.orderNo);
            t2.setText(sb);
            return v;
        }
    }

    private static String shortTime(String t) {
        if (t == null || t.length() == 0) {
            return "";
        }
        return t.length() >= 16 ? t.substring(5, 16) : t;
    }
}
