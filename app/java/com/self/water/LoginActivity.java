package com.self.water;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Random;

/** 登录页：手机号 + 图形验证码 + 短信验证码。 */
public class LoginActivity extends BaseActivity {

    private EditText phoneEt;
    private EditText captchaEt;
    private EditText smsEt;
    private ImageView captchaImg;
    private TextView statusTv;
    private Button btnGetSms;
    private Button btnLogin;

    private double seed = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Prefs.loggedIn(this)) {
            setResult(RESULT_OK);
            finish();
            return;
        }
        setContentView(R.layout.activity_login);

        phoneEt = findViewById(R.id.phone);
        captchaEt = findViewById(R.id.captchaCode);
        smsEt = findViewById(R.id.smsCode);
        captchaImg = findViewById(R.id.captchaImg);
        statusTv = findViewById(R.id.status);
        btnGetSms = findViewById(R.id.btnGetSms);
        btnLogin = findViewById(R.id.btnLogin);

        String savedPhone = Prefs.phone(this);
        if (savedPhone != null && savedPhone.length() > 0) {
            phoneEt.setText(savedPhone);
        }

        findViewById(R.id.btnRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadCaptcha();
            }
        });

        btnGetSms.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSendSms();
            }
        });

        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doLogin();
            }
        });

        loadCaptcha();
    }

    private void say(String s) {
        statusTv.setText(s);
    }

    /** 拉取图形验证码图片。 */
    private void loadCaptcha() {
        seed = new Random().nextDouble();
        say("正在获取验证码图片…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Api.Resp r = Api.captcha(seed);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!r.ok() || r.raw == null || r.raw.length < 100) {
                            say("验证码获取失败：" + (r.err != null ? r.err : ("HTTP " + r.http)));
                            return;
                        }
                        captchaImg.setImageBitmap(
                                BitmapFactory.decodeByteArray(r.raw, 0, r.raw.length));
                        say("请输入图片上的字符，再点「获取短信验证码」。");
                    }
                });
            }
        }).start();
    }

    private void doSendSms() {
        final String phone = phoneEt.getText().toString().trim();
        final String cap = captchaEt.getText().toString().trim();
        if (phone.length() != 11) {
            say("请输入 11 位手机号。");
            return;
        }
        if (cap.length() == 0) {
            say("请输入图形验证码。");
            return;
        }
        // 服务端把验证码当整数解析：带字母会直接回「系统错误」，用户根本看不懂。
        // 输入框已经限制成数字键盘，这里再兜一层（粘贴、第三方输入法都可能绕过）。
        if (!cap.matches("\\d+")) {
            say("图形验证码只能填数字（图片上是纯数字）。");
            return;
        }
        btnGetSms.setEnabled(false);
        say("正在发送…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Api.Resp r = Api.sendSms(phone, cap, seed);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnGetSms.setEnabled(true);
                        JSONObject j = r.json();
                        int code = j.optInt("code", -999);
                        if (r.err != null) {
                            say("发送失败：" + r.err);
                        } else if (code == 0) {
                            say("短信已发送，请查看手机。");
                        } else {
                            // 服务端的错误码不够友好：验证码过期/找不到时会直接抛
                            // {"code":-100,"msg":"系统错误"}，看着像服务器挂了，
                            // 其实换一张验证码就行。这里按码给出可操作的说法。
                            String hint;
                            switch (code) {
                                case -2:
                                    hint = "图形验证码不对，已换一张，请重新输入";
                                    break;
                                case -100:
                                    // 经验证：验证码里带非数字、或字段为空时，服务端 parseInt
                                    // 抛异常，直接回 {"code":-100,"msg":"系统错误"}。
                                    // 图片上的验证码是**纯数字**，所以这里只说人话。
                                    hint = "验证码只能填数字，请核对后重试（已换一张）";
                                    break;
                                case -1:
                                    hint = "请求参数不对，已换一张；反复失败请更新版本";
                                    break;
                                default:
                                    hint = j.optString("msg", "code=" + code) + "（已换一张）";
                                    break;
                            }
                            say("发送失败：" + hint);
                            loadCaptcha();
                        }
                    }
                });
            }
        }).start();
    }

    private void doLogin() {
        final String phone = phoneEt.getText().toString().trim();
        final String sms = smsEt.getText().toString().trim();
        if (sms.length() == 0) {
            say("请输入短信验证码。");
            return;
        }
        btnLogin.setEnabled(false);
        say("正在登录…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Api.Resp r = Api.login(phone, sms);
                final JSONObject j = r.json();
                final String token = Api.pick(j, "token");
                final String uid = Api.pick(j, "uid");
                final String eid = Api.pick(j, "eid");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnLogin.setEnabled(true);
                        if (r.err != null) {
                            say("登录失败：" + r.err);
                        } else if (token != null) {
                            Prefs.saveLogin(LoginActivity.this, phone, token, uid);
                            // eid 是查免密协议状态用的，登录时顺手存下来
                            Prefs.saveEid(LoginActivity.this, eid == null ? "" : eid);
                            // 记下登录时刻：压掉"刚登录完还弹登录已过期"的误报
                            lastLoginAtMs = System.currentTimeMillis();
                            setResult(RESULT_OK);
                            finish();
                        } else {
                            say("登录失败：" + j.optString("msg", r.body));
                        }
                    }
                });
            }
        }).start();
    }

    private void goMain() {
        setResult(RESULT_OK);
        finish();
    }
}
