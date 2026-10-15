package com.self.water;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

/** 胖乖生活（洗澡）登录页：手机号 + 短信验证码。 */
public class PanggualLoginActivity extends BaseActivity {

    private EditText phoneEt;
    private EditText smsEt;
    private TextView statusTv;
    private Button btnSend;
    private Button btnLogin;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_pg_login);

        phoneEt = findViewById(R.id.pgPhone);
        smsEt = findViewById(R.id.pgSms);
        statusTv = findViewById(R.id.pgStatus);
        btnSend = findViewById(R.id.btnPgSendSms);
        btnLogin = findViewById(R.id.btnPgDoLogin);

        String saved = Prefs.phone(this);
        if (saved != null && saved.length() > 0) {
            phoneEt.setText(saved);
        }

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendSms();
            }
        });
        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doLogin();
            }
        });
    }

    private void say(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusTv.setText(s);
            }
        });
    }

    private void sendSms() {
        final String phone = phoneEt.getText().toString().trim();
        if (phone.length() != 11) {
            say("请输入 11 位手机号。");
            return;
        }
        btnSend.setEnabled(false);
        say("正在发送…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final PanggualApi.Resp r = PanggualApi.sendSms(phone);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnSend.setEnabled(true);
                        if (r.err != null) {
                            say("发送失败：" + r.err);
                        } else if (r.code() == 0) {
                            say("短信已发送，请查看手机。");
                        } else {
                            say("发送失败：" + r.msg());
                        }
                    }
                });
            }
        }).start();
    }

    private void doLogin() {
        final String phone = phoneEt.getText().toString().trim();
        final String code = smsEt.getText().toString().trim();
        if (code.length() == 0) {
            say("请输入短信验证码。");
            return;
        }
        btnLogin.setEnabled(false);
        say("正在登录…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final PanggualApi.Resp r = PanggualApi.login(phone, code);
                final JSONObject j = r.json();
                final JSONObject d = j.optJSONObject("data");
                final String token = d == null ? null : d.optString("token", null);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnLogin.setEnabled(true);
                        if (r.err != null) {
                            say("登录失败：" + r.err);
                        } else if (token != null && token.length() > 0) {
                            Prefs.savePgLogin(PanggualLoginActivity.this, phone, token);
                            setResult(RESULT_OK);
                            finish();
                        } else {
                            say("登录失败：" + r.msg());
                        }
                    }
                });
            }
        }).start();
    }
}
