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
                            say("发送失败：" + j.optString("msg", "code=" + code)
                                    + "（可点「刷新图片」重试）");
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
