package com.self.water;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 最小可用的 FileProvider。
 *
 * 为什么要自己写：这个项目没有用 AndroidX，为了一个 FileProvider 把
 * androidx.core 整个拉进来太重了（而且会顶掉「零第三方依赖」这条）。实现
 * 需要的其实只有 openFile + query 两个方法。
 *
 * 为什么需要它：Android 7（API 24）以后不许把 file:// 交给别的应用
 * （FileUriExposedException），把 APK 交给系统安装器必须用 content:// 并授权。
 *
 * 安全：只暴露 App 自己的外部目录下的 updates/ 一个子目录，文件名在这里
 * 校验（不许带 / 或 ..），不接受调用方传任意路径。
 */
public class SimpleFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.self.water.files";
    private static final String DIR = "updates";

    /** 下载目录；不存在就建。 */
    public static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(null), DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** 给某个文件造 content:// 地址。 */
    public static Uri uriFor(File f) {
        return Uri.parse("content://" + AUTHORITY + "/" + f.getName());
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.length() == 0
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.contains("..") || name.startsWith(".")) {
            throw new FileNotFoundException("bad name");
        }
        File f = new File(dir(getContext()), name);
        if (!f.isFile()) {
            throw new FileNotFoundException(name);
        }
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    /** 安装器要靠 DISPLAY_NAME / SIZE 显示信息，这两个得给。 */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            MatrixCursor c = new MatrixCursor(new String[]{
                    OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
