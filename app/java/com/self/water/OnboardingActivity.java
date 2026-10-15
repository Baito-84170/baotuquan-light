package com.self.water;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 首次启动引导：4 屏，讲清楚「三个服务各自登录」「扫码一次后面一键」
 * 以及数据只在本地这两件事。跳过或走完都算完成，不再自动出现。
 */
public class OnboardingActivity extends BaseActivity {

    private static final String[] GLYPH = {"💧", "🚿", "📷", "🔒"};

    private static final int[] TITLE = {
            R.string.onboard_t1, R.string.onboard_t2,
            R.string.onboard_t3, R.string.onboard_t4};

    private static final int[] DESC = {
            R.string.onboard_d1, R.string.onboard_d2,
            R.string.onboard_d3, R.string.onboard_d4};

    private TextView glyphTv;
    private TextView titleTv;
    private TextView descTv;
    private LinearLayout dots;
    private Button nextBtn;

    private int step;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_onboarding);

        glyphTv = findViewById(R.id.onboardGlyph);
        titleTv = findViewById(R.id.onboardTitle);
        descTv = findViewById(R.id.onboardDesc);
        dots = findViewById(R.id.onboardDots);
        nextBtn = findViewById(R.id.onboardNext);

        findViewById(R.id.onboardSkip).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishOnboarding();
            }
        });

        nextBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (step >= GLYPH.length - 1) {
                    finishOnboarding();
                } else {
                    step++;
                    render();
                }
            }
        });

        buildDots();
        render();
    }

    private void buildDots() {
        dots.removeAllViews();
        int size = dp(7);
        int gap = dp(8);
        for (int i = 0; i < GLYPH.length; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(gap, 0, gap, 0);
            dot.setLayoutParams(lp);
            dots.addView(dot);
        }
    }

    private void render() {
        glyphTv.setText(GLYPH[step]);
        titleTv.setText(TITLE[step]);
        descTv.setText(DESC[step]);
        nextBtn.setText(step >= GLYPH.length - 1
                ? R.string.onboard_start : R.string.onboard_next);

        for (int i = 0; i < dots.getChildCount(); i++) {
            View dot = dots.getChildAt(i);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(getColor(i == step ? R.color.primary : R.color.divider));
            dot.setBackground(d);
        }
    }

    private void finishOnboarding() {
        Prefs.setOnboarded(this);
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public void onBackPressed() {
        // 返回也当完成，避免每次启动都弹
        finishOnboarding();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
