package com.self.water;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/** 主界面：一个「激活取水」按钮，外加结束与状态。 */
public class WaterActivity extends BaseActivity {

    private EditText deviceEt;
    private TextView nameTv;
    private TextView metaTv;
    private TextView liveStatusTv;
    private TextView statusTv;
    private Button btnActivate;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean polling = false;

    /** 免密状态，以及本次进入页面是否已经提醒过。 */
    private PayStatus.Status nopwd = new PayStatus.Status();
    private boolean naggedThisVisit;

    /**
     * 免密状态查询的节流窗口。
     *
     * resume 触发得很频繁（切出去看一眼官方 App 再切回来、锁屏解锁…），每次都打一次
     * 接口既没必要，也容易和设备轮询挤在一起。pga-check 经验证约 0.1 秒，15 秒的窗口
     * 足够新鲜，又能挡住抖动。
     */
    private static final long PAY_STATUS_MIN_INTERVAL_MS = 15_000;
    private long payStatusAt = 0;
    private boolean payStatusLoading = false;
    /** 页面是否在前台 —— 慢网下结果回来时据此丢弃过期结果，避免弹窗迟到。 */
    private boolean resumed = false;

    private static final int REQ_LOGIN = 1;
    private static final int REQ_DEVICE = 2;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        deviceEt = findViewById(R.id.deviceId);
        nameTv = findViewById(R.id.deviceName);
        metaTv = findViewById(R.id.deviceMeta);
        liveStatusTv = findViewById(R.id.liveStatus);
        statusTv = findViewById(R.id.status);
        btnActivate = findViewById(R.id.btnActivate);

        deviceEt.setText(DeviceStore.currentId(this, DeviceStore.TYPE_WATER));

        // 整块设备区域可点 → 打开设备选择页
        findViewById(R.id.devicePick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 取水也改成"拉取名单"：按站点列出官方账号里收藏过的设备。
                // 798 没有"列出整栋楼机器"的接口（/dev/near 不给设备 id、
                // /ep/station/lst 只到站点一层），所以名单来自收藏
                // —— ui/app/master 返回的 data.favos。
                startActivityForResult(
                        new Intent(WaterActivity.this, WaterDevicePickerActivity.class),
                        REQ_DEVICE);
            }
        });

        btnActivate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doActivate();
            }
        });

        findViewById(R.id.btnEnd).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doEnd();
            }
        });

        findViewById(R.id.btnLogout).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(WaterActivity.this)
                        .setMessage("确定退出登录？")
                        .setPositiveButton("退出", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                Prefs.logout(WaterActivity.this);
                                setResult(RESULT_OK);
                                finish();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        ensureLogin();
    }

    /**
     * 查 798 的免密协议状态并按需提醒。
     *
     * 免密决定结算时能不能自动扣款，没开就会留下待支付订单。提示到开通或用户点
     * 「不再提示」为止。
     *
     * 这个查询**必须允许重复触发**：用户看到「建议开通免密」后，很可能直接跳到
     * 「慧生活798」开通再切回来 —— 只在 onCreate 查一次的话，切回来看到的还是旧结果，
     * 表现就是「免密状态拉取有延迟」。配套做了三件事：
     *
     *   1. 节流 —— 15 秒内不重复查，避免 resume 反复触发时打爆接口；
     *   2. 去重 —— 同一时刻只允许一个查询在飞，不会出现多个响应互相覆盖；
     *   3. 前台守卫 —— 结果回来时页面已经不在前台或已销毁，就直接丢弃。
     *      慢网下这一步很关键：否则弹窗会等你离开页面之后才冒出来。
     *
     * @param force true 表示跳过节流（页面首次进入、刚重新登录过）
     */
    private void refreshPayStatus(boolean force) {
        if (!Prefs.loggedIn(this) || payStatusLoading) {
            return;
        }
        if (!force && System.currentTimeMillis() - payStatusAt < PAY_STATUS_MIN_INTERVAL_MS) {
            return;
        }
        payStatusLoading = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final PayStatus.Status st = PayStatus.checkWater(
                        Prefs.token(WaterActivity.this), Prefs.eid(WaterActivity.this));
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        payStatusLoading = false;
                        payStatusAt = System.currentTimeMillis();
                        if (!resumed || isFinishing()) {
                            return;     // 页面已经不在了，这次结果作废
                        }
                        nopwd = st;
                        if (!naggedThisVisit
                                && PayStatus.shouldNag(WaterActivity.this,
                                        PayStatus.SVC_WATER, st)) {
                            naggedThisVisit = true;
                            PayStatus.nag(WaterActivity.this, PayStatus.SVC_WATER, st);
                        }
                    }
                });
            }
        }).start();
    }

    /** 未登录取水账号时，页内引导登录；取消则退回首页。 */
    private void ensureLogin() {
        if (!Prefs.loggedIn(this)) {
            nameTv.setText(R.string.main_title);
            metaTv.setText(R.string.water_need_login);
            btnActivate.setEnabled(false);
            liveStatusTv.setText("未登录");
            tintBadge(R.color.warn_bg, R.color.warn);
            statusTv.setText("");
            startActivityForResult(
                    new Intent(this, LoginActivity.class), REQ_LOGIN);
        } else {
            btnActivate.setEnabled(true);
            loadDevice();
            refreshPayStatus(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        polling = true;
        poll();
        // 这里必须重查免密状态：看到「建议开通免密」后，用户很可能直接跳到
        // 「慧生活798」开通、再切回来。之前 onResume 只重起了设备轮询，没重查
        // 免密状态 —— 切回来看到的还是旧结果，表现就是「免密状态拉取有延迟」。
        // （洗澡页一直是重查的，只有取水页漏了这一句。）
        refreshPayStatus(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        polling = false;
    }

    @Override
    protected void onRelogin(boolean shower) {
        // 重新登录回来后自动恢复：按钮可用 + 重新拉设备信息 + 起重轮询
        if (Prefs.loggedIn(this)) {
            btnActivate.setEnabled(true);
            liveStatusTv.setText("●  读取中…");
            tintBadge(R.color.idle_bg, R.color.idle);
            loadDevice();
            polling = true;
            poll();
            refreshPayStatus(true);
        }
    }

    private void say(final String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                statusTv.setText(s);
            }
        });
    }

    /** 带配色的版本：结果是好是坏一眼能看出来。 */
    private void say(final String s, final int bgRes, final int fgRes) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                statusTv.setText(s);
                statusTv.setTextColor(getColor(fgRes));
                statusTv.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(getColor(bgRes)));
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_DEVICE && res == RESULT_OK && data != null) {
            String id = data.getStringExtra("id");
            if (id != null && id.length() > 0) {
                deviceEt.setText(id);
                DeviceStore.setCurrent(this, DeviceStore.TYPE_WATER, id);
                loadDevice();
            }
        } else if (req == REQ_LOGIN) {
            if (Prefs.loggedIn(this)) {
                btnActivate.setEnabled(true);
                loadDevice();
                refreshPayStatus(true);
            } else {
                // 放弃登录 —— 退回首页
                finish();
            }
        }
    }

    private String deviceId() {
        String s = deviceEt.getText().toString().trim();
        if (s.length() == 0) {
            s = DeviceStore.currentId(this, DeviceStore.TYPE_WATER);
        }
        return s;
    }

    /** 拉设备信息，填标题。 */
    private void loadDevice() {
        final String did = deviceId();
        Prefs.saveDevice(this, did);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Api.Resp r = Api.deviceHome(did, Prefs.token(WaterActivity.this));
                final JSONObject j = r.json();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (checkWater(r)) {
                            nameTv.setText(R.string.main_title);
                            metaTv.setText(R.string.water_need_login);
                            btnActivate.setEnabled(false);
                            return;
                        }
                        JSONObject d = j.optJSONObject("data");
                        JSONObject dev = d == null ? null : d.optJSONObject("device");
                        if (dev == null) {
                            nameTv.setText(R.string.main_title);
                            metaTv.setText("设备未找到：" + j.optString("msg", r.body));
                            return;
                        }
                        JSONObject bm = dev.optJSONObject("bm");
                        JSONObject ep = dev.optJSONObject("ep");

                        // 顺手补 eid —— 798 查免密协议（pga-check）必须要它，而它原先
                        // 只在**登录响应**里取一次。用旧版本登录过的账号本地是空的，
                        // 于是 PayStatus.checkWater 直接返回 UNKNOWN、**连请求都不发**，
                        // 表现就是「免密状态拉不到 / 一直不更新」。
                        // dev/home 的响应里带着同一个企业 id（ep.id，与 deviceShare.eid 一致），
                        // 这里直接补齐，用户不必重新登录。
                        if (Prefs.eid(WaterActivity.this).length() == 0) {
                            String eid = ep == null ? "" : ep.optString("id", "");
                            if (eid.length() == 0) {
                                JSONObject share = dev.optJSONObject("deviceShare");
                                eid = share == null ? "" : share.optString("eid", "");
                            }
                            if (eid.length() > 0) {
                                Prefs.saveEid(WaterActivity.this, eid);
                                // 刚才那次免密查询是空转的（没发出请求），清掉节流状态重来一次
                                payStatusLoading = false;
                                payStatusAt = 0;
                                refreshPayStatus(true);
                            }
                        }

                        String model = bm == null ? "" : bm.optString("model", "");
                        String brand = bm == null ? "" : bm.optString("brand", "");
                        String unit = bm == null ? "" : bm.optString("unit", "");
                        int dtype = bm == null ? -1 : bm.optInt("dtype", -1);
                        String epName = ep == null ? "" : ep.optString("name", "");
                        // 自动记住这台设备（分组=校区，副标题=校区·型号）
                        String sub = (epName.length() > 0 ? epName + "  ·  " : "")
                                + brand + " " + model;
                        DeviceStore.remember(WaterActivity.this, DeviceStore.TYPE_WATER,
                                did, epName, sub);

                        DeviceStore.Device rec = DeviceStore.find(
                                WaterActivity.this, DeviceStore.TYPE_WATER, did);
                        String note = rec == null ? "" : rec.name;
                        if (note != null && note.length() > 0) {
                            nameTv.setText(note);
                            metaTv.setText((epName.length() > 0 ? epName + "  ·  " : "")
                                    + brand + " " + model + "  ·  " + dtypeName(dtype));
                        } else {
                            nameTv.setText(epName.length() > 0
                                    ? epName : getString(R.string.main_title));
                            metaTv.setText(brand + " " + model + "  ·  " + dtypeName(dtype)
                                    + (unit.length() > 0 ? "  ·  按" + unit + "计量" : ""));
                        }
                    }
                });
            }
        }).start();
    }

    /** 激活 —— 等价于扫机器上的码。 */
    private void doActivate() {
        final String did = deviceId();
        Prefs.saveDevice(this, did);
        btnActivate.setEnabled(false);
        say("正在激活…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Api.Resp r = Api.start(did, Prefs.ptype(WaterActivity.this),
                        Prefs.token(WaterActivity.this));
                final JSONObject j = r.json();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        btnActivate.setEnabled(true);
                        if (r.err != null) {
                            say("激活失败：" + r.err);
                        } else if (checkWater(r)) {
                            btnActivate.setEnabled(false);
                            say("登录已过期，请重新登录。");
                        } else if (j.optInt("code", -999) == 0) {
                            say("✅ 已激活，请在机器上按键取水。");
                        } else {
                            say("激活失败：" + j.optString("msg", r.body));
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 结束本次用水。
     *
     * 注意：不能拿返回值当成"结束成功" ——
     * **服务端对"根本没有进行中的用水"也返回 code 0**，于是这个按钮
     * 会声称做成了一件事，实际上什么都没发生。
     *
     *
     * 做法：先读一次状态（只用于提示），然后**一定**发结束请求，
     * 发完再回头读状态做校验：
     *   - 状态回到待机   → 才算结束成功
     *   - 状态没变       → 如实说"服务器受理了但机器没停"，让用户按机器上的停止键
     *
     * 另注：取加密回执要用 rcp=true，那串 160 位十六进制是密文，解不出金额，
     * 所以不假装能报账，只报状态。
     */
    private void doEnd() {
        final String did = deviceId();
        say("正在读取设备状态…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int before = readStatus(did);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        // 官方拿 status==99 判定"可启动"，所以待机时确实没有进行中的
                        // 用水；但机器上报会滞后，所以**照样发一次结束请求**，
                        // 绝不出现"用户点了结束、却一个包都没发出去"的情况。
                        reallyEnd(did, before);
                    }
                });
            }
        }).start();
    }

    /** 读一次 gene.status；失败返回 -1。 */
    private int readStatus(String did) {
        try {
            Api.Resp st = Api.deviceStatus(did, Prefs.token(WaterActivity.this));
            JSONObject sj = st.json();
            if (st.err != null || sj.optInt("code", -999) != 0) {
                return -1;
            }
            JSONObject d = sj.optJSONObject("data");
            JSONObject dev = d == null ? null : d.optJSONObject("device");
            JSONObject g = dev == null ? null : dev.optJSONObject("gene");
            return g == null ? -1 : g.optInt("status", -1);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 真正执行结束。
     *
     * 关键：官方「结束使用」用的是 /dev/end?did=&rcp=**false**
     * （DevControlHelper.i() → DeviceViewModel.deviceEnd(id, rcp=false)），
     * rcp=true 时拿到的是每次都一模一样的加密回执，
     * 机器该出水还出水。现在按官方语义发 rcp=false，并且**回头验状态**：
     * 只有机器真的回到"待机"才敢说结束成功。
     */
    private void reallyEnd(final String did, final int before) {
        say("正在结束（当前状态 " + statusName(before) + "）…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Api.Resp r = Api.end(did, false, Prefs.token(WaterActivity.this));
                final JSONObject j = r.json();
                int a = readStatus(did);
                // 机器上报有延迟：最多再等 ~5 秒，看它有没有回到待机
                for (int i = 0; i < 4 && a >= 0 && a != 99 && a != 0; i++) {
                    try {
                        Thread.sleep(1300);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                    a = readStatus(did);
                }
                final int after = a;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (checkWater(r)) {
                            say("登录已过期，请重新登录。");
                            liveStatusTv.setText("●  未登录");
                            tintBadge(R.color.warn_bg, R.color.warn);
                            polling = false;      // 停掉轮询，重登后 onResume 会重新起
                            return;
                        }
                        if (j.optInt("code", -999) != 0) {
                            say("结束失败：" + j.optString("msg", r.body)
                                    + "\n\n（当前状态 " + statusName(before) + "）",
                                    R.color.warn_bg, R.color.warn);
                            return;
                        }
                        if (after < 0) {
                            say("服务器已受理结束请求，但读不到机器状态，"
                                    + "请看一眼机器是否已经停水。",
                                    R.color.ok_bg, R.color.ok);
                            return;
                        }
                        if (after >= 0) {
                            applyLiveStatus(after, 0);   // 立刻刷新徽章
                        }
                        if (after == 99 || after == 0) {
                            if (before == 99 || before == 0) {
                                say("机器本来就是「" + statusName(before) + "」，"
                                        + "没有进行中的用水。\n\n"
                                        + "取水的正常顺序：先点「开始用水」激活 → "
                                        + "在机器上按键出水 → 取够了再点「结束本次用水」。",
                                        R.color.idle_bg, R.color.idle);
                            } else {
                                say("✅ 已结束，机器已回到待机。"
                                        + "\n状态：" + statusName(before) + " → " + statusName(after),
                                        R.color.ok_bg, R.color.ok);
                            }
                        } else {
                            // 服务端说成功、机器却没停下来 —— 不再假装成功
                            say("服务器已受理，但机器状态还是「" + statusName(after)
                                    + "」，可能还在出水。\n\n"
                                    + "如果水还在流，请在机器上按一下停止键；"
                                    + "稍等十几秒再点一次「结束本次用水」。\n"
                                    + "（状态：" + statusName(before) + " → " + statusName(after) + "）",
                                    R.color.warn_bg, R.color.warn);
                        }
                    }
                });
            }
        }).start();
    }

    /** 每 3 秒刷一次状态。 */
    private void poll() {
        if (!polling) {
            return;
        }
        final String did = deviceId();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Api.Resp r = Api.deviceStatus(did, Prefs.token(WaterActivity.this));
                final JSONObject j = r.json();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (checkWater(r)) {
                            liveStatusTv.setText("●  未登录");
                            tintBadge(R.color.warn_bg, R.color.warn);
                            polling = false;
                            return;
                        }
                        if (j.optInt("code", -999) == 0) {
                            JSONObject d = j.optJSONObject("data");
                            JSONObject dev = d == null ? null : d.optJSONObject("device");
                            JSONObject g = dev == null ? null : dev.optJSONObject("gene");
                            if (g != null) {
                                int st = g.optInt("status", -1);
                                double vel = g.optDouble("vel", 0);
                                applyLiveStatus(st, vel);
                            }
                        } else {
                            liveStatusTv.setText("●  状态读取失败");
                            tintBadge(R.color.warn_bg, R.color.warn);
                        }
                        if (polling) {
                            ui.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    poll();
                                }
                            }, 3000);
                        }
                    }
                });
            }
        }).start();
    }

    /** 把设备状态渲染成徽章。 */
    private void applyLiveStatus(int st, double vel) {
        liveStatusTv.setText("●  " + statusName(st) + "　　流速 " + vel);
        if (st == 10) {                                   // 运行中
            tintBadge(R.color.ok_bg, R.color.ok);
        } else if (st == 1 || st == 20 || st == 30) {      // 已激活 / 冲洗 / 准备
            tintBadge(R.color.primary_light, R.color.primary);
        } else if (st == 0 || st == 99) {                  // 关闭 / 空闲
            tintBadge(R.color.idle_bg, R.color.idle);
        } else {                                           // 异常 / 锁定
            tintBadge(R.color.warn_bg, R.color.warn);
        }
    }

    private void tintBadge(int bgRes, int fgRes) {
        liveStatusTv.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(getColor(bgRes)));
        liveStatusTv.setTextColor(getColor(fgRes));
    }

    private static String dtypeName(int d) {
        switch (d) {
            case 5:   return "水控/直饮机";
            case 6:   return "水表/淋浴器";
            case 8:   return "管线/饮水机";
            case 9:   return "净/热水主机";
            case 10:  return "洗衣机";
            case 20:  return "吹风机";
            case 60:  return "消费机";
            case 100: return "取件柜";
            default:  return "类型 " + d;
        }
    }

    private static String statusName(int s) {
        switch (s) {
            case 0:
                return "关闭";
            case 1:
                return "已激活";
            case 2:
                return "异常";
            case 10:
                return "运行中";
            case 20:
                return "冲洗中";
            case 30:
                return "准备中";
            case 98:
                return "强制禁用";
            case 99:
                return "空闲";
            default:
                return "未知(" + s + ")";
        }
    }
}
