package com.self.water;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 洗澡页。
 *
 * **不再让用户自己看机器屏幕 —— 占用状态直接查得到。**
 *
 * 早先这个页面有个"开始前请看一眼机器屏幕"的确认弹窗，理由是我当时以为
 * 没有查询设备占用的接口（我说过"客户端无法预判"，那是错的）。
 * 后来在胖乖里找到了 `appointNew/near/newMachines`，每台机器都带
 * `deviceWorkStatus`（10 空闲 / 20 使用中）。既然有数据，就不该再让用户肉眼判断。
 *
 * 现在的策略：
 *   - 查到「使用中」→ 直接拦住，并把**同层空闲的机器**列出来让用户换
 *   - 查到「空闲」  → 直接开始，不再弹确认框
 *   - 查不到（未登录/网络/老设备没有 goodsId）→ 退回旧的"看屏幕"弹窗，保守处理
 *
 * 钱的安全仍然靠另外三道：下单前本地账本拦截、解锁失败自动撤单、结束/撤销常驻卡片。
 */
public class ShowerActivity extends BaseActivity {

    private TextView nameTv;
    private TextView metaTv;
    private TextView skuTv;
    private TextView occupyTv;
    private TextView statusTv;
    private TextView payModeTv;
    private Button btnStart;
    private LinearLayout loginBox;
    private LinearLayout pendingBox;
    private TextView pendingInfo;
    private LinearLayout floorBox;
    private TextView floorTitle;
    private LinearLayout floorChips;

    private JSONObject device;
    private String previewInfo;

    private List<PanggualApi.PayOption> payTypes = new ArrayList<>();
    private int payType = PanggualApi.PAY_ALIPAY;
    private boolean payTypeChosenByUser;

    private PayStatus.Status nopwd = new PayStatus.Status();
    private boolean naggedThisVisit;

    /** 当前设备的实时占用：null 表示还不知道。 */
    private PanggualApi.Machine occupy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_shower);

        nameTv = findViewById(R.id.showerName);
        metaTv = findViewById(R.id.showerMeta);
        skuTv = findViewById(R.id.showerSku);
        occupyTv = findViewById(R.id.showerOccupy);
        statusTv = findViewById(R.id.showerStatus);
        payModeTv = findViewById(R.id.payMode);
        btnStart = findViewById(R.id.btnShowerStart);
        loginBox = findViewById(R.id.loginBox);
        pendingBox = findViewById(R.id.pendingBox);
        pendingInfo = findViewById(R.id.pendingInfo);
        floorBox = findViewById(R.id.floorBox);
        floorTitle = findViewById(R.id.floorTitle);
        floorChips = findViewById(R.id.floorChips);

        findViewById(R.id.showerPick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 设备选择改成"直播浏览"：先选楼号，再按层列出该楼所有机器。
                // 不再走本地设备库那套（手动输入/批量/扫码）—— 机器列表能完整拉到，
                // 而且机器二维码印在低分辨率点阵 LCD 上根本扫不出来。
                startActivityForResult(
                        new Intent(ShowerActivity.this, ShowerDevicePickerActivity.class),
                        REQ_DEVICE);
            }
        });
        findViewById(R.id.payRow).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                payModeDialog();
            }
        });
        findViewById(R.id.btnShowerMore).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                moreMenu();
            }
        });
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmStart();
            }
        });
        findViewById(R.id.btnShowerEnd).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                endPending();
            }
        });
        findViewById(R.id.btnPendingEnd).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                endPending();
            }
        });
        findViewById(R.id.btnPendingCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelPending();
            }
        });
        findViewById(R.id.btnPgLogin).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(
                        new Intent(ShowerActivity.this, PanggualLoginActivity.class), REQ_LOGIN);
            }
        });

        refreshLoginState();
        refreshPending();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean logged = Prefs.pgLoggedIn(this);
        loginBox.setVisibility(logged ? View.GONE : View.VISIBLE);
        if (!logged) {
            btnStart.setEnabled(false);
        }
        refreshPending();
        if (logged) {
            // ③ 进页面自动查询，不用再手点「查询设备」
            autoQuery();
            refreshPayStatus();
        }
    }

    @Override
    protected void onRelogin(boolean shower) {
        if (Prefs.pgLoggedIn(this)) {
            refreshLoginState();
            autoQuery();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_LOGIN) {
            refreshLoginState();
        } else if (req == REQ_DEVICE && res == RESULT_OK && data != null) {
            String id = data.getStringExtra("id");
            if (id != null && id.length() > 0) {
                device = null;
                btnStart.setEnabled(false);
                refreshPayModeUnknown();
                autoQuery();
            }
        }
    }

    private static final int REQ_LOGIN = 1;
    private static final int REQ_DEVICE = 2;

    // ---------------------------------------------------- 设备

    /** 当前设备标识：设备库里的 id（可能是 SN，也可能是 goodsId）。 */
    private String currentId() {
        return DeviceStore.currentId(this, DeviceStore.TYPE_SHOWER);
    }

    private void autoQuery() {
        if (currentId().length() == 0) {
            nameTv.setText(R.string.shower_not_queried);
            metaTv.setText("还没有设备。点上面这块，用「从列表添加」加一台。");
            btnStart.setEnabled(false);
            return;
        }
        queryDevice();
    }

    private void queryDevice() {
        final String id = currentId();
        if (id.length() == 0) {
            return;
        }
        btnStart.setEnabled(false);
        say("正在查询设备…", R.color.primary_light, R.color.primary);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject dev = PanggualApi.resolveDevice(id, Prefs.pgToken(ShowerActivity.this));
                    final PanggualApi.Resp prev = PanggualApi.tradePreview(
                            dev.optString("orgId"), dev.optString("goodsId"),
                            dev.optString("shopId"), dev.optString("categoryCode"),
                            dev.optString("imei"), buildItems(dev),
                            Prefs.pgToken(ShowerActivity.this),
                            new PanggualApi.Risk(dev.optString("imei"),
                                    dev.optString("categoryCode"), LAT, LNG));
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            device = dev;
                            showDevice(dev, prev);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            String m = e.getMessage() == null ? "" : e.getMessage();
                            if (PanggualApi.isExpiredMsg(m)) {
                                refreshLoginState();
                                promptExpired(true);
                                return;
                            }
                            say("查询失败：" + m, R.color.warn_bg, R.color.warn);
                            nameTv.setText(id);
                            metaTv.setText("解析不出来。点右上「⋮」→「手动输入编号 / 扫码」可以重来。");
                        }
                    });
                }
            }
        }).start();
    }

    private void showDevice(JSONObject dev, PanggualApi.Resp prev) {
        if (checkShower(prev)) {
            refreshLoginState();
            return;
        }
        String goodsId = dev.optString("goodsId", "");
        String goodsName = dev.optString("goodsName", dev.optString("machineName", ""));
        String orgName = dev.optString("orgName", "");
        String imei = dev.optString("imei", "");

        // 记进设备库（老数据可能缺 goodsId，这里补上）
        DeviceStore.remember(this, DeviceStore.TYPE_SHOWER, currentId(), orgName, goodsName);
        DeviceStore.setExtra(this, DeviceStore.TYPE_SHOWER, currentId(), goodsId);

        // ⑤ 标题用机器名（30601），比 goodsId 好认得多。
        // 只在这台设备还没有备注名时自动填，不覆盖用户自己起的名字。
        DeviceStore.Device rec = DeviceStore.find(this, DeviceStore.TYPE_SHOWER, currentId());
        if (rec != null && (rec.name == null || rec.name.length() == 0)
                && goodsName.length() > 0) {
            DeviceStore.rename(this, DeviceStore.TYPE_SHOWER, currentId(), goodsName);
            rec = DeviceStore.find(this, DeviceStore.TYPE_SHOWER, currentId());
        }

        String note = rec == null ? "" : rec.name;
        nameTv.setText(note != null && note.length() > 0
                ? note : (goodsName.length() > 0 ? goodsName : goodsId));

        String floor = ShowerMachines.floorLabel(goodsName);
        StringBuilder meta = new StringBuilder();
        if (floor.length() > 0) {
            meta.append(floor).append("  ·  ");
        }
        if (orgName.length() > 0) {
            meta.append(orgName).append("  ·  ");
        }
        meta.append(dev.optString("categoryName", "淋浴"));
        if (imei.length() > 0) {
            meta.append("\n").append(imei);
        }
        metaTv.setText(meta);

        JSONObject pdata = prev.json().optJSONObject("data");
        String price = pdata == null ? null : pdata.optString("realPrice", null);
        previewInfo = price;
        StringBuilder sb = new StringBuilder();
        sb.append("出水 ").append(dev.optString("skuName", ""))
          .append("  ·  ¥").append(dev.optString("skuPrice", ""));
        if (price != null) {
            sb.append("  ·  实付 ¥").append(price);
        }
        skuTv.setText(sb);

        btnStart.setEnabled(price != null);
        if (price == null) {
            say("设备已找到，但订单预览失败：" + prev.msg(), R.color.warn_bg, R.color.warn);
        } else {
            say("就绪", R.color.idle_bg, R.color.idle);
        }

        loadPayTypes(dev);
        loadOccupancy(goodsId);
    }

    /** ⑥ 查这台机器的实时占用，并列出同层空闲的。 */
    private void loadOccupancy(final String goodsId) {
        occupyTv.setText(R.string.occupy_unknown);
        occupyTv.setTextColor(getColor(R.color.text_hint));
        floorBox.setVisibility(View.GONE);

        if (goodsId == null || goodsId.length() == 0) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<PanggualApi.Machine> free =
                        ShowerMachines.sameFloorFree(ShowerActivity.this, goodsId);
                PanggualApi.Machine self = ShowerMachines.find(ShowerActivity.this, goodsId);
                final PanggualApi.Machine fSelf = self;
                final List<PanggualApi.Machine> fFree = free;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        occupy = fSelf;
                        applyOccupancy(fSelf, fFree);
                    }
                });
            }
        }).start();
    }

    private void applyOccupancy(PanggualApi.Machine self, List<PanggualApi.Machine> free) {
        if (self == null) {
            occupyTv.setText(R.string.occupy_unknown);
            occupyTv.setTextColor(getColor(R.color.text_hint));
            occupyTv.setBackgroundTintList(android.content.res.ColorStateList
                    .valueOf(getColor(R.color.idle_bg)));
            floorBox.setVisibility(View.GONE);
            return;
        }
        boolean busy = self.busy();
        occupyTv.setText(busy ? R.string.occupy_busy : R.string.occupy_idle);
        occupyTv.setTextColor(getColor(busy ? R.color.warn : R.color.ok));
        occupyTv.setBackgroundTintList(android.content.res.ColorStateList
                .valueOf(getColor(busy ? R.color.warn_bg : R.color.ok_bg)));

        // ① 同层空闲机器
        String floor = ShowerMachines.floorLabel(self.name);
        if (floor.length() == 0) {
            floor = ShowerMachines.buildingLabel(self.name);
        }
        floorChips.removeAllViews();
        if (free.isEmpty()) {
            floorTitle.setText(getString(R.string.floor_none_free, floor));
            floorTitle.setTextColor(getColor(R.color.text_sub));
        } else {
            floorTitle.setText(getString(R.string.floor_free_title, floor, free.size()));
            floorTitle.setTextColor(getColor(R.color.ok));
        }
        for (final PanggualApi.Machine m : free) {
            Button chip = new Button(this);
            chip.setText(m.name);
            chip.setAllCaps(false);
            chip.setTextSize(13f);
            chip.setBackground(getDrawable(R.drawable.btn_outline));
            chip.setTextColor(getColor(R.color.primary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
            lp.setMargins(0, 0, dp(8), 0);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTo(m);
                }
            });
            floorChips.addView(chip);
        }
        floorBox.setVisibility(View.VISIBLE);
    }

    /** 换一台同层空闲的机器。 */
    private void switchTo(PanggualApi.Machine m) {
        DeviceStore.remember(this, DeviceStore.TYPE_SHOWER, m.goodsId,
                ShowerMachines.buildingLabel(m.name)
                        + (ShowerMachines.floorLabel(m.name).length() > 0
                            ? ShowerMachines.floorLabel(m.name) : ""),
                m.name);
        DeviceStore.setExtra(this, DeviceStore.TYPE_SHOWER, m.goodsId, m.goodsId);
        DeviceResolver.invalidate(DeviceStore.TYPE_SHOWER, m.goodsId);
        DeviceStore.setCurrent(this, DeviceStore.TYPE_SHOWER, m.goodsId);
        device = null;
        btnStart.setEnabled(false);
        refreshPayModeUnknown();
        toast("已切到 " + m.name);
        autoQuery();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ---------------------------------------------------- 支付方式

    private void refreshPayModeUnknown() {
        payTypes = new ArrayList<>();
        payModeTv.setText(R.string.pay_type_unknown);
    }

    private void loadPayTypes(final JSONObject dev) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<PanggualApi.PayOption> list = PanggualApi.availablePayTypes(
                        dev.optString("goodsId"), dev.optString("shopId"),
                        dev.optString("orgId"), dev.optString("imei"),
                        dev.optString("categoryCode"), Prefs.pgToken(ShowerActivity.this));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        payTypes = list;
                        if (!payTypeChosenByUser) {
                            payType = PanggualApi.defaultPayType(list);
                        }
                        payModeTv.setText(PanggualApi.payTypeName(payTypes, payType));
                        if (list.isEmpty()) {
                            say(getString(R.string.pay_type_none), R.color.warn_bg, R.color.warn);
                        }
                        if (payTypeAvailable(PanggualApi.PAY_ALIPAY_NOPWD)) {
                            applyPayStatus(PayStatus.fromOptions(list));
                        } else {
                            refreshPayStatus();
                        }
                    }
                });
            }
        }).start();
    }

    private boolean payTypeAvailable(int type) {
        for (PanggualApi.PayOption p : payTypes) {
            if (p.payType == type) {
                return true;
            }
        }
        return false;
    }

    private void payModeDialog() {
        if (payTypes.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.pay_type_pick)
                    .setMessage("还没拿到这台机器支持的支付方式，先等设备查询完成。")
                    .setPositiveButton(R.string.dlg_ok, null)
                    .show();
            return;
        }
        final CharSequence[] names = new CharSequence[payTypes.size()];
        int checked = 0;
        boolean hasNopwd = false;
        StringBuilder listing = new StringBuilder();
        for (int i = 0; i < payTypes.size(); i++) {
            PanggualApi.PayOption p = payTypes.get(i);
            names[i] = p.name + (p.isDefault ? "（官方默认）" : "");
            listing.append("\n  · ").append(p.name).append("（").append(p.payType).append("）");
            if (p.payType == PanggualApi.PAY_ALIPAY_NOPWD) {
                hasNopwd = true;
            }
            if (p.payType == payType) {
                checked = i;
            }
        }
        StringBuilder msg = new StringBuilder(getString(R.string.pay_type_hint));
        msg.append("\n\n服务端为这台机器返回了 ").append(payTypes.size())
           .append(" 个渠道：").append(listing);
        if (!hasNopwd) {
            msg.append("\n\n⚠️ 列表里没有「支付宝免密（15）」。\n")
               .append("这说明这台机器本身不接收免密渠道 —— 结算时扣不动就会变成「待支付」。");
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.pay_type_pick)
                .setMessage(msg.toString())
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        payType = payTypes.get(which).payType;
                        payTypeChosenByUser = true;
                        payModeTv.setText(PanggualApi.payTypeName(payTypes, payType));
                        d.dismiss();
                        toast("已切换为「" + PanggualApi.payTypeName(payTypes, payType)
                                + "」，本次下单生效。");
                    }
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    // ---------------------------------------------------- 免密提示

    /** 当前选中机器的 goodsId；没选就是空串（免密检查退化成「未知」，不会误报）。 */
    private String currentGoodsId() {
        JSONObject d = device;
        return d == null ? "" : d.optString("goodsId", "");
    }

    private void refreshPayStatus() {
        if (!Prefs.pgLoggedIn(this)) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final PayStatus.Status st = PayStatus.checkShower(
                        Prefs.pgToken(ShowerActivity.this), null,
                        currentGoodsId(), ShowerMachines.SHOP_ID,
                        "200130814", Prefs.showerSn(ShowerActivity.this), "08");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        applyPayStatus(st);
                    }
                });
            }
        }).start();
    }

    private void applyPayStatus(PayStatus.Status st) {
        nopwd = st;
        if (!naggedThisVisit && PayStatus.shouldNag(this, PayStatus.SVC_SHOWER, st)) {
            naggedThisVisit = true;
            PayStatus.nag(this, PayStatus.SVC_SHOWER, st);
        }
    }

    // ---------------------------------------------------- 在途订单

    private void refreshPending() {
        Prefs.PendingOrder p = Prefs.pgPending(this);
        if (p.isEmpty()) {
            pendingBox.setVisibility(View.GONE);
            findViewById(R.id.btnShowerEnd).setVisibility(View.GONE);
            return;
        }
        boolean running = p.orderNo.length() > 0;
        pendingBox.setVisibility(View.VISIBLE);
        findViewById(R.id.btnShowerEnd).setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.pendingTitle)).setText(running
                ? R.string.shower_pending_running : R.string.shower_pending_title);
        pendingInfo.setText(getString(
                running ? R.string.shower_pending_running_info : R.string.shower_pending_info,
                running ? p.orderNo : getString(R.string.shower_pending_no_order),
                p.title.length() > 0 ? p.title : p.sn,
                p.at == 0 ? "—" : new SimpleDateFormat("MM-dd HH:mm", Locale.US)
                        .format(new Date(p.at))));
        findViewById(R.id.btnPendingCancel).setVisibility(running ? View.GONE : View.VISIBLE);
    }

    // ---------------------------------------------------- 通用

    private static final String LAT = "36.651077673510976";
    private static final String LNG = "117.05471881019238";

    private void refreshLoginState() {
        boolean logged = Prefs.pgLoggedIn(this);
        loginBox.setVisibility(logged ? View.GONE : View.VISIBLE);
        if (!logged) {
            btnStart.setEnabled(false);
            say("请先登录洗澡账号", R.color.warn_bg, R.color.warn);
        }
    }

    private void say(final String s) {
        say(s, R.color.idle_bg, R.color.idle);
    }

    private void say(final String s, final int bg, final int fg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusTv.setText(s);
                statusTv.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(getColor(bg)));
                statusTv.setTextColor(getColor(fg));
            }
        });
    }

    private void toast(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ShowerActivity.this, s, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String buildItems(JSONObject dev) {
        try {
            JSONObject it = new JSONObject();
            it.put("goodsId", dev.optString("goodsId"));
            it.put("skuId", dev.optString("skuId"));
            it.put("amount", "1");
            it.put("soldType", "1");
            it.put("num", "1");
            it.put("price", dev.optString("skuPrice", "0"));
            return new JSONArray().put(it).toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** ⑦ 次要入口收进「⋮」。 */
    private void moreMenu() {
        final String[] labels = {
                getString(R.string.shower_menu_refresh),
                getString(R.string.shower_menu_orders),
                getString(R.string.shower_menu_paytype),
                getString(R.string.shower_menu_logout)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.shower_title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                ShowerMachines.invalidate();
                                autoQuery();
                                break;
                            case 1:
                                startActivity(new Intent(ShowerActivity.this, OrdersActivity.class));
                                break;
                            case 2:
                                payModeDialog();
                                break;
                            default:
                                confirmLogout();
                                break;
                        }
                    }
                })
                .show();
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setMessage("确定退出洗澡账号？")
                .setPositiveButton(R.string.shower_menu_logout, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.pgLogout(ShowerActivity.this);
                        refreshLoginState();
                        toast("已退出洗澡账号");
                    }
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }


    // ---------------------------------------------------- 开始 / 结束

    /**
     * ② 开始前的检查。
     *
     * 拿到占用状态就不弹"看屏幕"了 —— 那是没有数据时的兜底。
     */
    private void confirmStart() {
        if (device == null) {
            toast("设备还没查询好，稍等或点「⋮」→「刷新设备状态」");
            return;
        }
        if (occupy != null && occupy.busy()) {
            AlertDialog.Builder b = new AlertDialog.Builder(this)
                    .setTitle("这台现在有人在用")
                    .setMessage(getString(R.string.shower_busy_blocked, occupy.name))
                    .setNegativeButton("取消", null);
            final List<PanggualApi.Machine> free =
                    ShowerMachines.sameFloorFree(this, device.optString("goodsId", ""));
            if (!free.isEmpty()) {
                final CharSequence[] names = new CharSequence[free.size()];
                for (int i = 0; i < free.size(); i++) {
                    names[i] = free.get(i).name;
                }
                b.setPositiveButton("换一台", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        switchTo(free.get(0));
                    }
                });
            }
            b.show();
            return;
        }
        if (occupy != null && occupy.idle()) {
            // 有数据说是空闲 → 直接开始，不再啰嗦
            doStart();
            return;
        }
        // 拿不到占用状态，退回"看屏幕"这条保守路径
        String price = previewInfo == null ? "?" : previewInfo;
        new AlertDialog.Builder(this)
                .setTitle("查不到占用状态，请看一眼机器屏幕")
                .setMessage("屏幕上显示「请扫码」= 空闲，可以开始。\n"
                        + "如果显示计时 / 余额 / 使用中等字样，说明有人在用，请不要继续。\n\n"
                        + "预计费用：¥" + price)
                .setPositiveButton("屏幕显示「请扫码」，开始", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        doStart();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doStart() {
        final Prefs.PendingOrder existing = Prefs.pgPending(this);
        if (!existing.isEmpty()) {
            promptExistingOrder(existing);
            return;
        }
        btnStart.setEnabled(false);
        say("正在检查在途订单…", R.color.primary_light, R.color.primary);
        final JSONObject dev = device;
        final String token = Prefs.pgToken(this);
        final String goodsId = dev.optString("goodsId");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final PanggualApi.Resp last = PanggualApi.lastOrder(goodsId, token);
                if (last.err == null) {
                    final String lastNo = PanggualApi.pickLastOrderNo(last);
                    if (lastNo.length() > 0) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btnStart.setEnabled(true);
                                say(getString(R.string.shower_busy, lastNo),
                                        R.color.warn_bg, R.color.warn);
                                Prefs.PendingOrder p = Prefs.pgPending(ShowerActivity.this);
                                if (p.isEmpty()) {
                                    p.orderNo = lastNo;
                                    p.goodsId = goodsId;
                                    p.sn = dev.optString("imei", "");
                                    p.title = dev.optString("orgName", "")
                                            + " " + dev.optString("goodsName", "");
                                    p.at = System.currentTimeMillis();
                                    Prefs.savePgPending(ShowerActivity.this, p);
                                    refreshPending();
                                }
                            }
                        });
                        return;
                    }
                }

                PanggualApi.Resp chk = PanggualApi.checkDeviceStatus(goodsId, token);
                if (chk.err == null) {
                    JSONObject cj0 = chk.json();
                    JSONObject cd0 = cj0.optJSONObject("data");
                    if (cj0.optInt("code", -999) == 0 && cd0 != null
                            && !cd0.optBoolean("health", true)) {
                        fail("设备状态异常，已中止：" + cd0.optString("faultDesc", "未知原因"));
                        return;
                    }
                }

                final PanggualApi.Risk risk = new PanggualApi.Risk(
                        dev.optString("imei"), dev.optString("categoryCode"), LAT, LNG);

                final PanggualApi.Resp create = PanggualApi.tradeCreate(
                        dev.optString("orgId"), goodsId,
                        dev.optString("shopId"), dev.optString("categoryCode"),
                        dev.optString("imei"), buildItems(dev), token, risk);

                if (create.err != null) {
                    fail("下单请求失败：" + create.err);
                    return;
                }
                if (PanggualApi.isExpired(create)) {
                    relogin();
                    return;
                }
                if (create.code() != 0) {
                    fail("下单失败：" + create.msg() + "\n\n（把这条发我，我来对参数）");
                    return;
                }

                final String orderId = PanggualApi.pickOrderId(create);
                if (orderId.length() == 0) {
                    fail("下单返回里没有 orderId，已中止解锁（避免产生无法撤销的订单）。\n\n"
                            + "原始返回：\n" + create.body);
                    return;
                }
                Prefs.PendingOrder p = new Prefs.PendingOrder();
                p.orderId = orderId;
                p.goodsId = goodsId;
                p.sn = dev.optString("imei", "");
                p.title = dev.optString("orgName", "") + " " + dev.optString("goodsName", "");
                p.at = System.currentTimeMillis();
                Prefs.savePgPending(ShowerActivity.this, p);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshPending();
                        say(getString(R.string.shower_order_created, orderId),
                                R.color.primary_light, R.color.primary);
                    }
                });

                PanggualApi.Resp unlock;
                try {
                    unlock = PanggualApi.showerUnlock(
                            dev.optString("skuId"), "", String.valueOf(payType),
                            orderId, token, risk);
                } catch (Exception e) {
                    recover(orderId, goodsId, "", "解锁异常：" + e.getMessage(), token, risk);
                    return;
                }
                if (unlock.err != null) {
                    recover(orderId, goodsId, "", "解锁请求失败：" + unlock.err, token, risk);
                    return;
                }
                if (PanggualApi.isExpired(unlock)) {
                    relogin();
                    return;
                }
                if (unlock.code() != 0) {
                    recover(orderId, goodsId, "", "解锁失败：" + unlock.msg(), token, risk);
                    return;
                }

                final String orderNo = PanggualApi.pickUnlockOrderNo(unlock);
                Prefs.setPgPendingOrderNo(ShowerActivity.this, orderNo);
                ShowerMachines.invalidate();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnStart.setEnabled(true);
                        refreshPending();
                        say("✅ 已解锁，出水开始。" + (orderNo.length() > 0
                                        ? "\n订单号 " + orderNo + "，洗完记得点「结束」。"
                                        : ""),
                                R.color.ok_bg, R.color.ok);
                        loadOccupancy(goodsId);
                    }
                });
            }
        }).start();
    }

    private void recover(final String orderId, String goodsId, String orderNo,
                         final String why, String token, PanggualApi.Risk risk) {
        PanggualApi.Resp cancel = PanggualApi.orderCancel(orderId, token);
        boolean cancelled = cancel.err == null && cancel.code() == 0;
        if (cancelled) {
            Prefs.clearPgPending(this);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    btnStart.setEnabled(true);
                    refreshPending();
                    say(why + "\n" + getString(R.string.shower_recover_ok, orderId),
                            R.color.warn_bg, R.color.warn);
                }
            });
            return;
        }
        PanggualApi.Resp end = null;
        boolean ended = false;
        if (orderNo.length() > 0) {
            end = PanggualApi.showerEnd(goodsId, orderNo, token);
            ended = end.err == null && end.code() == 0;
        }
        if (ended) {
            Prefs.clearPgPending(this);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    btnStart.setEnabled(true);
                    refreshPending();
                    say(why + "\n" + getString(R.string.shower_recover_end_ok, orderNo),
                            R.color.warn_bg, R.color.warn);
                }
            });
            return;
        }
        Prefs.setPgPendingOrderNo(this, orderNo);
        final String cancelMsg = cancel.err != null ? cancel.err : cancel.msg();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                btnStart.setEnabled(true);
                refreshPending();
                say(why + "\n" + getString(R.string.shower_recover_fail, cancelMsg, orderId),
                        R.color.warn_bg, R.color.warn);
            }
        });
    }

    private void promptExistingOrder(final Prefs.PendingOrder p) {
        final String no = p.orderNo.length() > 0 ? p.orderNo : p.orderId;
        boolean running = p.orderNo.length() > 0;
        new AlertDialog.Builder(this)
                .setTitle("已经有一笔没结束的订单")
                .setMessage("订单号 " + no + "\n\n"
                        + "再开一笔会产生两笔订单，可能重复扣费，所以先拦下来。\n\n"
                        + (running ? "请点「结束这一单」结算掉。" : "这笔还没拿到订单号，先点「撤销这一单」。")
                        + "\n\n如果这笔其实已经在官方 App 里结束了，选「已结束」清掉本地记录即可。")
                .setPositiveButton(running ? "结束这一单" : "撤销这一单",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                if (running) {
                                    endPending();
                                } else {
                                    cancelPending();
                                }
                            }
                        })
                .setNeutralButton("已结束，清除记录", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.clearPgPending(ShowerActivity.this);
                        refreshPending();
                        toast("已清除本地记录");
                    }
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    private void endPending() {
        final Prefs.PendingOrder p = Prefs.pgPending(this);
        if (p.isEmpty()) {
            toast("没有进行中的订单。");
            return;
        }
        if (p.orderNo.length() == 0) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.btn_pending_end)
                    .setMessage(R.string.shower_end_need_order)
                    .setPositiveButton(R.string.btn_pending_cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    cancelPending();
                                }
                            })
                    .setNegativeButton(R.string.dlg_cancel, null)
                    .show();
            return;
        }
        say("正在结束订单…", R.color.primary_light, R.color.primary);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final PanggualApi.Resp r = PanggualApi.showerEnd(
                        p.goodsId, p.orderNo, Prefs.pgToken(ShowerActivity.this));
                ShowerMachines.invalidate();
                final String gid = p.goodsId;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (r.err == null && r.code() == 0) {
                            Prefs.clearPgPending(ShowerActivity.this);
                            refreshPending();
                            say(getString(R.string.shower_ended_ok, p.orderNo),
                                    R.color.ok_bg, R.color.ok);
                            loadOccupancy(gid);
                        } else {
                            say("结束失败：" + (r.err != null ? r.err : r.msg())
                                    + "\n订单号 " + p.orderNo, R.color.warn_bg, R.color.warn);
                        }
                    }
                });
            }
        }).start();
    }

    private void cancelPending() {
        final Prefs.PendingOrder p = Prefs.pgPending(this);
        if (p.isEmpty()) {
            toast("没有进行中的订单。");
            return;
        }
        if (p.orderId.length() == 0) {
            say("本地没记到 orderId，撤销不了。请到胖乖生活「我的订单」里处理。",
                    R.color.warn_bg, R.color.warn);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_pending_cancel)
                .setMessage("如果这单还没开始出水，撤销不会扣费。\n\n订单号 "
                        + (p.orderNo.length() > 0 ? p.orderNo : p.orderId))
                .setPositiveButton(R.string.btn_pending_cancel,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                doCancel(p);
                            }
                        })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    private void doCancel(final Prefs.PendingOrder p) {
        say("正在撤销订单…", R.color.primary_light, R.color.primary);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final PanggualApi.Resp r = PanggualApi.orderCancel(
                        p.orderId, Prefs.pgToken(ShowerActivity.this));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (r.err == null && r.code() == 0) {
                            Prefs.clearPgPending(ShowerActivity.this);
                            refreshPending();
                            say(getString(R.string.shower_cancelled_ok, p.orderId),
                                    R.color.ok_bg, R.color.ok);
                        } else {
                            say("撤销失败：" + (r.err != null ? r.err : r.msg())
                                    + "\n订单号 " + p.orderId, R.color.warn_bg, R.color.warn);
                        }
                    }
                });
            }
        }).start();
    }

    private void fail(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                btnStart.setEnabled(true);
                say("❌ " + msg, R.color.warn_bg, R.color.warn);
            }
        });
    }

    private void relogin() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                refreshLoginState();
                promptExpired(true);
            }
        });
    }
}
