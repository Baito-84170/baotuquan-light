package com.self.water;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备记忆：记住用过的设备、支持自定义备注、区分「取水」与「洗澡」两类。
 * 存成一段 JSON 放在 SharedPreferences 里。
 */
public final class DeviceStore {

    public static final String TYPE_WATER = "water";
    public static final String TYPE_SHOWER = "shower";

    private static final String FILE = "water_devices";
    private static final String KEY = "data";

    // 排序方式
    public static final int SORT_RECENT = 0;
    public static final int SORT_NAME = 1;
    public static final int SORT_MANUAL = 2;

    private DeviceStore() {
    }

    /** 一条设备记录。 */
    public static final class Device {
        public String type;
        public String id;
        public String name;   // 用户备注，可为空
        public String group;  // 归属，如校区 / 浴室名
        public String sub;    // 自动带出的位置/型号，可为空
        public String extra;  // 附加标识（洗澡设备存 goodsId，用于查状态）
        public long usedAt;
        public boolean pinned;  // 置顶
        public int order;       // 手动排序时的名次，越小越靠前

        public Device() {
        }

        public Device(String type, String id, String name, String group, String sub) {
            this.type = type;
            this.id = id;
            this.name = name;
            this.group = group;
            this.sub = sub;
            this.usedAt = System.currentTimeMillis();
        }

        /** 界面上显示的主标题：有备注用备注，否则用位置/型号，再否则用编号。 */
        public String title() {
            if (name != null && name.length() > 0) {
                return name;
            }
            if (sub != null && sub.length() > 0) {
                return sub;
            }
            return id;
        }

        /** 副标题。 */
        public String subtitle() {
            StringBuilder sb = new StringBuilder();
            if (sub != null && sub.length() > 0 && name != null && name.length() > 0) {
                sb.append(sub);
            }
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append(id);
            return sb.toString();
        }

        /** 分组名，没有则归入「未分组」。 */
        public String groupName() {
            return (group == null || group.length() == 0) ? "未分组" : group;
        }

        /** 是否匹配搜索词。 */
        public boolean matches(String q) {
            if (q == null || q.length() == 0) {
                return true;
            }
            String s = q.toLowerCase();
            return contains(name, s) || contains(group, s)
                    || contains(sub, s) || contains(id, s);
        }

        private static boolean contains(String v, String q) {
            return v != null && v.toLowerCase().contains(q);
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("id", id);
            o.put("name", name == null ? "" : name);
            o.put("group", group == null ? "" : group);
            o.put("sub", sub == null ? "" : sub);
            o.put("extra", extra == null ? "" : extra);
            o.put("usedAt", usedAt);
            o.put("pinned", pinned);
            o.put("order", order);
            return o;
        }

        static Device from(JSONObject o) {
            Device d = new Device();
            d.type = o.optString("type", TYPE_WATER);
            d.id = o.optString("id", "");
            d.name = o.optString("name", "");
            d.group = o.optString("group", "");
            d.sub = o.optString("sub", "");
            d.extra = o.optString("extra", "");
            d.usedAt = o.optLong("usedAt", 0);
            d.pinned = o.optBoolean("pinned", false);
            d.order = o.optInt("order", 0);
            return d;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static JSONObject root(Context c) {
        String raw = sp(c).getString(KEY, "");
        if (raw != null && raw.length() > 0) {
            try {
                return new JSONObject(raw);
            } catch (Exception ignored) {
                // 损坏则重建
            }
        }
        JSONObject o = new JSONObject();
        try {
            o.put("devices", new JSONArray());
            o.put("current", new JSONObject());
        } catch (Exception ignored) {
        }
        return o;
    }

    private static void save(Context c, JSONObject root) {
        sp(c).edit().putString(KEY, root.toString()).apply();
    }

    // ---------------------------------------------------------- 排序方式

    private static String sortKey(String type) {
        return "sort_" + (type == null ? "all" : type);
    }

    public static int sortMode(Context c, String type) {
        return sp(c).getInt(sortKey(type), SORT_RECENT);
    }

    public static void setSortMode(Context c, String type, int mode) {
        sp(c).edit().putInt(sortKey(type), mode).apply();
    }

    /** 排序方式的显示名。 */
    public static String sortName(Context c, String type) {
        int m = sortMode(c, type);
        if (m == SORT_NAME) {
            return "按名称";
        }
        if (m == SORT_MANUAL) {
            return "手动排序";
        }
        return "最近使用";
    }

    // ---------------------------------------------------------- 列表

    public static List<Device> list(Context c, String type) {
        List<Device> out = new ArrayList<>();
        JSONArray arr = root(c).optJSONArray("devices");
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Device d = Device.from(o);
            if (type == null || type.equals(d.type)) {
                out.add(d);
            }
        }
        final int mode = sortMode(c, type);
        java.util.Collections.sort(out, new java.util.Comparator<Device>() {
            @Override
            public int compare(Device a, Device b) {
                // 置顶的永远在最前
                if (a.pinned != b.pinned) {
                    return a.pinned ? -1 : 1;
                }
                if (mode == SORT_NAME) {
                    int r = a.title().compareToIgnoreCase(b.title());
                    if (r != 0) {
                        return r;
                    }
                } else if (mode == SORT_MANUAL) {
                    int r = Integer.compare(a.order, b.order);
                    if (r != 0) {
                        return r;
                    }
                }
                // 兜底：最近使用的靠前
                return Long.compare(b.usedAt, a.usedAt);
            }
        });
        return out;
    }

    public static Device find(Context c, String type, String id) {
        if (id == null) {
            return null;
        }
        for (Device d : list(c, type)) {
            if (id.equals(d.id)) {
                return d;
            }
        }
        return null;
    }

    // ---------------------------------------------------------- 增改删

    /** 记住一台设备（存在则更新 group / sub / usedAt，不覆盖用户备注）。 */
    public static void remember(Context c, String type, String id, String group, String sub) {
        if (id == null || id.length() == 0) {
            return;
        }
        JSONObject r = root(c);
        JSONArray arr = r.optJSONArray("devices");
        if (arr == null) {
            arr = new JSONArray();
            try {
                r.put("devices", arr);
            } catch (Exception ignored) {
            }
        }
        boolean found = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (type.equals(o.optString("type")) && id.equals(o.optString("id"))) {
                try {
                    if (group != null && group.length() > 0) {
                        o.put("group", group);
                    }
                    if (sub != null && sub.length() > 0) {
                        o.put("sub", sub);
                    }
                    o.put("usedAt", System.currentTimeMillis());
                } catch (Exception ignored) {
                }
                found = true;
                break;
            }
        }
        if (!found) {
            try {
                Device d = new Device(type, id, "", group, sub);
                d.order = arr.length();   // 新设备排在手动顺序的最后
                arr.put(d.toJson());
            } catch (Exception ignored) {
            }
        }
        save(c, r);
    }

    /** 手动添加一台设备。 */
    public static void add(Context c, String type, String id, String name, String group) {
        remember(c, type, id, group, "");
        if (name != null && name.length() > 0) {
            rename(c, type, id, name);
        }
        setCurrent(c, type, id);
    }

    /** 写入附加标识（洗澡设备用 goodsId，查状态时需要）。 */
    public static void setExtra(Context c, String type, String id, String extra) {
        if (id == null || extra == null || extra.length() == 0) {
            return;
        }
        JSONObject r = root(c);
        JSONArray arr = r.optJSONArray("devices");
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (type.equals(o.optString("type")) && id.equals(o.optString("id"))) {
                try {
                    o.put("extra", extra);
                } catch (Exception ignored) {
                }
                break;
            }
        }
        save(c, r);
    }

    /** 该类型下出现过的所有分组名。 */
    public static List<String> groups(Context c, String type) {
        List<String> out = new ArrayList<>();
        for (Device d : list(c, type)) {
            String g = d.groupName();
            if (!out.contains(g)) {
                out.add(g);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** 按分组归类。 */
    public static java.util.Map<String, List<Device>> grouped(Context c, String type, String query) {
        java.util.Map<String, List<Device>> map = new java.util.LinkedHashMap<>();
        for (Device d : list(c, type)) {
            if (!d.matches(query)) {
                continue;
            }
            String g = d.groupName();
            List<Device> bucket = map.get(g);
            if (bucket == null) {
                bucket = new ArrayList<>();
                map.put(g, bucket);
            }
            bucket.add(d);
        }
        return map;
    }

    // ---------------------------------------------------------- 置顶 / 排序

    /** 置顶或取消置顶。 */
    public static void setPinned(Context c, String type, String id, boolean pinned) {
        JSONObject r = root(c);
        JSONArray arr = r.optJSONArray("devices");
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (type.equals(o.optString("type")) && id.equals(o.optString("id"))) {
                try {
                    o.put("pinned", pinned);
                } catch (Exception ignored) {
                }
                break;
            }
        }
        save(c, r);
    }

    /**
     * 把一台设备在「当前显示顺序」里上移 / 下移一位（delta = -1 / +1）。
     *
     * 做法是先把可见顺序固化下来，交换两台设备的位置，再整体写回 order。
     * 这样无论之前用的是哪种排序方式，操作之后都会变成手动排序，
     * 用户看到的顺序就跟他刚才看到的一致。
     */
    public static void move(Context c, String type, String id, int delta) {
        List<Device> vis = list(c, type);
        int from = -1;
        for (int i = 0; i < vis.size(); i++) {
            if (vis.get(i).id.equals(id)) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            return;
        }
        int to = from + delta;
        if (to < 0 || to >= vis.size()) {
            return;
        }
        Device moved = vis.remove(from);
        vis.add(to, moved);
        setSortMode(c, type, SORT_MANUAL);
        commitOrder(c, type, vis);
    }

    /** 把给定的显示顺序写回 order 字段（跨分组也按可见顺序排）。 */
    public static void commitOrder(Context c, String type, List<Device> ordered) {
        JSONObject r = root(c);
        JSONArray arr = r.optJSONArray("devices");
        if (arr == null) {
            return;
        }
        // id -> 名次
        java.util.Map<String, Integer> rank = new java.util.HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            rank.put(ordered.get(i).id, i);
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || !type.equals(o.optString("type"))) {
                continue;
            }
            Integer k = rank.get(o.optString("id"));
            if (k == null) {
                continue;
            }
            try {
                o.put("order", k.intValue());
            } catch (Exception ignored) {
            }
        }
        save(c, r);
    }

    /** 该设备在可见列表里的名次（0 起），找不到返回 -1。 */
    public static int indexOf(Context c, String type, String id) {
        List<Device> vis = list(c, type);
        for (int i = 0; i < vis.size(); i++) {
            if (vis.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    public static void rename(Context c, String type, String id, String name) {
        JSONObject r = root(c);
        JSONArray arr = r.optJSONArray("devices");
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (type.equals(o.optString("type")) && id.equals(o.optString("id"))) {
                try {
                    o.put("name", name == null ? "" : name);
                } catch (Exception ignored) {
                }
                break;
            }
        }
        save(c, r);
    }

    public static void remove(Context c, String type, String id) {
        JSONObject r = root(c);
        JSONArray arr = r.optJSONArray("devices");
        if (arr == null) {
            return;
        }
        JSONArray kept = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (type.equals(o.optString("type")) && id.equals(o.optString("id"))) {
                continue;
            }
            kept.put(o);
        }
        try {
            r.put("devices", kept);
        } catch (Exception ignored) {
        }
        // 删的是当前设备则清空当前
        JSONObject cur = r.optJSONObject("current");
        if (cur != null && id != null && id.equals(cur.optString(type, ""))) {
            cur.remove(type);
        }
        save(c, r);
    }

    // ---------------------------------------------------------- 当前设备

    public static String currentId(Context c, String type) {
        JSONObject cur = root(c).optJSONObject("current");
        String v = cur == null ? "" : cur.optString(type, "");
        if (v != null && v.length() > 0) {
            return v;
        }
        // 回退到旧版本的单设备设置
        String legacy = TYPE_SHOWER.equals(type) ? Prefs.showerSn(c) : Prefs.device(c);
        return legacy == null ? "" : legacy;
    }

    public static void setCurrent(Context c, String type, String id) {
        JSONObject r = root(c);
        JSONObject cur = r.optJSONObject("current");
        if (cur == null) {
            cur = new JSONObject();
            try {
                r.put("current", cur);
            } catch (Exception ignored) {
            }
        }
        try {
            cur.put(type, id == null ? "" : id);
        } catch (Exception ignored) {
        }
        save(c, r);
    }

    /** 当前设备的显示名（用于主界面标题）。 */
    public static String currentTitle(Context c, String type, String fallback) {
        Device d = find(c, type, currentId(c, type));
        if (d == null) {
            return fallback;
        }
        return d.title();
    }
}
