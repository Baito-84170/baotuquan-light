package com.self.water;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 扫码页：用相机识别机器上的二维码，把内容回传给调用方。
 * 用 ZXing core 解码 + 系统 Camera API，不依赖任何 UI 库。
 */
@SuppressWarnings("deprecation")
public class ScanActivity extends BaseActivity
        implements SurfaceHolder.Callback, Camera.PreviewCallback {

    public static final String EXTRA_RESULT = "result";

    private static final int REQ_CAMERA = 101;

    private SurfaceView surfaceView;
    private TextView hintTv;
    private TextView resultTv;

    private Camera camera;
    private MultiFormatReader reader;
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean done = false;

    /** 实际生效的对焦模式；AUTO/MACRO 需要我们定时触发。 */
    private String focusMode;
    private boolean torchOn;
    private boolean torchSupported;
    private int frames;
    private boolean widened;

    /** 快路径：只找二维格式。绝大多数机器码是二维码，这样每帧快好几倍。 */
    private static final List<BarcodeFormat> FAST_FORMATS = java.util.Arrays.asList(
            BarcodeFormat.QR_CODE,
            BarcodeFormat.DATA_MATRIX,
            BarcodeFormat.AZTEC,
            BarcodeFormat.PDF_417);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_scan);

        surfaceView = findViewById(R.id.preview);
        hintTv = findViewById(R.id.scanHint);
        resultTv = findViewById(R.id.scanResult);

        findViewById(R.id.scanCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.scanTorch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTorch();
            }
        });

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        // 先只找二维格式。ZXing 的 MultiFormatReader 如果不给 POSSIBLE_FORMATS，
        // 会把 CODE_128/EAN 那些一维码阅读器也挨个跑一遍 —— 每帧都慢好几倍，
        // 结果就是一秒钟解不了几帧，手一晃就错过。绝大多数机器码都是二维码。
        hints.put(DecodeHintType.POSSIBLE_FORMATS, FAST_FORMATS);
        reader = new MultiFormatReader();
        reader.setHints(hints);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            startPreview();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_CAMERA) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
                startPreview();
            } else {
                Toast.makeText(this, R.string.scan_need_camera, Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void startPreview() {
        surfaceView.getHolder().addCallback(this);
    }

    // ---------------------------------------------------------- 相机

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            camera = Camera.open();
        } catch (Exception e) {
            Toast.makeText(this, "打不开相机：" + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        try {
            Camera.Parameters p = camera.getParameters();

            // 预览尺寸：之前卡在 1280，现代手机传感器是 4000x3000 级别，
            // 压到 1280 之后二维码的模块可能只剩一两个像素，直接解不出来。
            // 放宽到 1920，同时不至于太慢。
            Camera.Size best = null;
            for (Camera.Size s : p.getSupportedPreviewSizes()) {
                if (s.width <= 1920 && (best == null || s.width > best.width)) {
                    best = s;
                }
            }
            if (best != null) {
                p.setPreviewSize(best.width, best.height);
            }

            // 明确指定 NV21。解码时我们直接把前 w*h 字节当灰度平面用，
            // 如果设备默认给的是别的格式（YV12 之类），这个假设就是错的，
            // 拿到的会是花屏 —— 之前没设，等于在赌。
            List<Integer> fmts = p.getSupportedPreviewFormats();
            if (fmts != null && fmts.contains(ImageFormat.NV21)) {
                p.setPreviewFormat(ImageFormat.NV21);
            }

            // 对焦：这是"扫不上"最常见的原因。
            // 之前只在支持 CONTINUOUS_PICTURE 时才设置，不支持就什么都不设 →
            // 固定焦距 → 近距离拍二维码一片模糊。
            List<String> modes = p.getSupportedFocusModes();
            focusMode = null;
            if (modes != null) {
                String[] prefer = {
                        Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE,
                        Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO,
                        Camera.Parameters.FOCUS_MODE_MACRO,
                        Camera.Parameters.FOCUS_MODE_AUTO,
                };
                for (String m : prefer) {
                    if (modes.contains(m)) {
                        focusMode = m;
                        p.setFocusMode(m);
                        break;
                    }
                }
            }
            camera.setParameters(p);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
            camera.startPreview();

            // 修正预览比例。
            //
            // SurfaceView 会把相机画面**拉伸**到自己的尺寸上，而我们的 layout 是
            // match_parent/maxh_parent —— 于是预览被按屏幕比例拉长，画面又细又长
            // （实机上就是这个现象）。相机不会替我们保持比例，得自己算：
            // 让 SurfaceView 的宽高比 = 预览的宽高比，多出来的部分裁掉。
            if (best != null) {
                fixPreviewAspect(best.width, best.height);
            }

            // 如果只能 AUTO / MACRO，就自己周期性触发对焦 —— 否则永远不会重新合焦
            if (Camera.Parameters.FOCUS_MODE_AUTO.equals(focusMode)
                    || Camera.Parameters.FOCUS_MODE_MACRO.equals(focusMode)) {
                ui.postDelayed(focusTick, 900);
            }
        } catch (Exception e) {
            Toast.makeText(this, "相机初始化失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            releaseCamera();
            finish();
        }
    }

    /**
     * 让预览不变形：把 SurfaceView 设成和相机画面同样的宽高比。
     *
     * 相机给的是 sensor 方向的宽高，竖屏下 displayOrientation=90 会把画面转 90°，
     * 所以要先把宽高对调再算。用 max 缩放（而不是 min）是为了铺满屏幕不留黑边 ——
     * 裁掉的只是边缘，中心区域照样在解码范围内（我们解的是整帧）。
     */
    private void fixPreviewAspect(int pw, int ph) {
        int viewW = ph;   // 转 90° 后宽高对调
        int viewH = pw;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float scale = Math.max((float) dm.widthPixels / viewW,
                               (float) dm.heightPixels / viewH);
        ViewGroup.LayoutParams lp = surfaceView.getLayoutParams();
        lp.width = Math.round(viewW * scale);
        lp.height = Math.round(viewH * scale);
        if (lp instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) lp).gravity = Gravity.CENTER;
        }
        surfaceView.setLayoutParams(lp);
    }

    /** 周期性触发一次自动对焦（仅用于 AUTO / MACRO 模式）。 */
    private final Runnable focusTick = new Runnable() {
        @Override
        public void run() {
            if (camera == null || done) {
                return;
            }
            try {
                camera.cancelAutoFocus();
                camera.autoFocus(null);
            } catch (Exception ignored) {
            }
            ui.postDelayed(this, 1500);
        }
    };

    @Override
    public void surfaceChanged(SurfaceHolder holder, int fmt, int w, int h) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    private void releaseCamera() {
        ui.removeCallbacks(focusTick);
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
        }
    }

    /**
     * 开关闪光灯。
     *
     * 机器上的二维码常贴在塑料壳或亚克力后面，浴室灯光又暗，
     * 这种时候补光几乎决定能不能扫上 —— 官方 App 也带这个能力。
     */
    private void toggleTorch() {
        if (camera == null) {
            return;
        }
        Button btn = findViewById(R.id.scanTorch);
        try {
            Camera.Parameters p = camera.getParameters();
            if (!torchSupported) {
                if (p.getSupportedFlashModes() == null
                        || !p.getSupportedFlashModes().contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                    Toast.makeText(this, R.string.scan_no_flash, Toast.LENGTH_SHORT).show();
                    return;
                }
                torchSupported = true;
            }
            torchOn = !torchOn;
            p.setFlashMode(torchOn ? Camera.Parameters.FLASH_MODE_TORCH
                                   : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(p);
            btn.setText(torchOn ? R.string.scan_torch_on : R.string.scan_torch_off);
        } catch (Exception e) {
            Toast.makeText(this, "切换闪光灯失败：" + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseCamera();
    }

    // ---------------------------------------------------------- 解码

    @Override
    public void onPreviewFrame(final byte[] data, Camera cam) {
        if (done || data == null) {
            return;
        }
        if (!decoding.compareAndSet(false, true)) {
            return;
        }
        final Camera.Parameters p = cam.getParameters();
        final int w = p.getPreviewSize().width;
        final int h = p.getPreviewSize().height;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // NV21 的 Y 平面就是灰度图，ZXing 能处理任意旋转
                    PlanarYUVLuminanceSource src =
                            new PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false);
                    BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(src));
                    final Result r = reader.decodeWithState(bmp);
                    if (r != null && r.getText() != null && r.getText().length() > 0) {
                        onFound(r.getText());
                    }
                } catch (Exception ignored) {
                    // NotFoundException 是常态，忽略
                } finally {
                    decoding.set(false);
                }
            }
        }).start();

        // 试了若干帧还是只找二维码，就放宽到全部格式再来一遍 ——
        // 万一机器上贴的是一维条码，前面那套快路径永远找不到。
        frames++;
        if (frames == 60 && !widened) {
            widened = true;
            Map<DecodeHintType, Object> all = new EnumMap<>(DecodeHintType.class);
            all.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            reader.setHints(all);
            ui.post(new Runnable() {
                @Override
                public void run() {
                    hintTv.setText(R.string.scan_hint_all_formats);
                }
            });
        }
    }

    private void onFound(final String text) {
        if (done) {
            return;
        }
        done = true;
        ui.post(new Runnable() {
            @Override
            public void run() {
                hintTv.setText(R.string.scan_ok);
                resultTv.setText(text);
                releaseCamera();
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT, text));
                        finish();
                    }
                }, 350);
            }
        });
    }
}
