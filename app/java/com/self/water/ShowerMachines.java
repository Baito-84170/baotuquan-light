package com.self.water;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * 洗澡机器目录：把整个商户下的机器拉全，并按机器名做楼层归组。
 *
 * 为什么要"拉全"而不是按楼栋查：洗澡设备记录里只存了 goodsId，
 * 没存楼栋。与其给设备表加字段（还得处理老数据迁移），不如扫一遍所有楼栋
 * （4 个请求，约 0.4 秒）然后缓存起来 —— 一次拉取同时满足三件事：
 *   1. 当前设备是空闲还是使用中
 *   2. 同楼层还有哪几台空着
 *   3. 「按列表添加」页的机器列表
 *
 * 机器名规则（经验证）：
 *   30601 = 3号楼 · 6层 · 01号
 *   即 [楼号1位][层2位][间2位]
 */
public final class ShowerMachines {

    /** 趵突泉 3 的商户 id。 */
    public static final String SHOP_ID = "202608011605230000091964865999";

    /** 淋浴。 */
    public static final String TYPE_SHOWER = "08";

    private static final long TTL_MS = 60_000;

    private static final Object LOCK = new Object();
    private static List<PanggualApi.Machine> cache = new ArrayList<>();
    private static List<PanggualApi.Building> buildingCache = new ArrayList<>();
    private static long fetchedAt;

    private ShowerMachines() {
    }

    /** 全部机器（跨楼栋）。force=true 时忽略缓存。 */
    public static List<PanggualApi.Machine> all(Context c, String machineTypeId, boolean force) {
        synchronized (LOCK) {
            if (!force && !cache.isEmpty()
                    && System.currentTimeMillis() - fetchedAt < TTL_MS) {
                return new ArrayList<>(cache);
            }
        }
        String token = Prefs.pgToken(c);
        if (token == null || token.length() == 0) {
            return new ArrayList<>();
        }
        List<PanggualApi.Building> bs =
                PanggualApi.buildings(SHOP_ID, machineTypeId, token);
        List<PanggualApi.Machine> out = new ArrayList<>();
        for (PanggualApi.Building b : bs) {
            out.addAll(PanggualApi.pointMachines(
                    SHOP_ID, machineTypeId, b.orgId, 1, 200, token));
        }
        synchronized (LOCK) {
            buildingCache = bs;
            cache = out;
            fetchedAt = System.currentTimeMillis();
        }
        return new ArrayList<>(out);
    }

    public static List<PanggualApi.Machine> all(Context c, boolean force) {
        return all(c, TYPE_SHOWER, force);
    }

    public static List<PanggualApi.Building> buildings(Context c, String machineTypeId) {
        synchronized (LOCK) {
            if (!buildingCache.isEmpty()) {
                return new ArrayList<>(buildingCache);
            }
        }
        List<PanggualApi.Building> bs = PanggualApi.buildings(
                SHOP_ID, machineTypeId, Prefs.pgToken(c));
        synchronized (LOCK) {
            buildingCache = bs;
        }
        return new ArrayList<>(bs);
    }

    /** 按 goodsId 找一台机器；找不到返回 null。 */
    public static PanggualApi.Machine find(Context c, String goodsId) {
        if (goodsId == null || goodsId.length() == 0) {
            return null;
        }
        for (PanggualApi.Machine m : all(c, false)) {
            if (goodsId.equals(m.goodsId)) {
                return m;
            }
        }
        return null;
    }

    /**
     * 同一楼层、当前空闲、且不是自己 —— 按房间号排序。
     *
     * 用途：站在浴室门口发现有人时，不用走回去开 App，直接在这里切一台。
     */
    public static List<PanggualApi.Machine> sameFloorFree(Context c, String goodsId) {
        List<PanggualApi.Machine> all = all(c, false);
        PanggualApi.Machine self = null;
        for (PanggualApi.Machine m : all) {
            if (m.goodsId.equals(goodsId)) {
                self = m;
                break;
            }
        }
        List<PanggualApi.Machine> out = new ArrayList<>();
        if (self == null) {
            return out;
        }
        String key = floorKey(self.name);
        if (key.length() == 0) {
            return out;
        }
        for (PanggualApi.Machine m : all) {
            if (m.goodsId.equals(goodsId)) {
                continue;
            }
            if (key.equals(floorKey(m.name)) && m.idle()) {
                out.add(m);
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<PanggualApi.Machine>() {
            @Override
            public int compare(PanggualApi.Machine a, PanggualApi.Machine b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    /** "30601" → "6层"；认不出来返回空串。 */
    public static String floorLabel(String name) {
        if (name == null || !name.matches("\\d{5,6}")) {
            return "";
        }
        try {
            int f = Integer.parseInt(name.substring(1, 3));
            return f + "层";
        } catch (Exception e) {
            return "";
        }
    }

    /** 分组键：楼号 + 层（"30601" → "306"）。 */
    private static String floorKey(String name) {
        if (name == null || name.length() < 3) {
            return "";
        }
        return name.substring(0, 3);
    }

    /** "30601" → "3号楼"。 */
    public static String buildingLabel(String name) {
        if (name == null || name.length() < 1 || !Character.isDigit(name.charAt(0))) {
            return "";
        }
        return name.charAt(0) + "号楼";
    }

    public static void invalidate() {
        synchronized (LOCK) {
            cache = new ArrayList<>();
            fetchedAt = 0;
        }
    }
}
