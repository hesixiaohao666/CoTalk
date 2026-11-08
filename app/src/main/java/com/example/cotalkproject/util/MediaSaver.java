package com.example.cotalkproject.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 图片保存到相册：Android 10+ 使用 MediaStore，Android 8/9 写入公共 Pictures 目录。
 */
public final class MediaSaver {

    private static final String ALBUM = "CoTalk";

    private MediaSaver() {
    }

    public static void saveImageToGallery(Context context, File source)
            throws IOException {
        if (source == null || !source.isFile()) {
            throw new IOException("图片文件不存在");
        }
        String mime = mimeFor(source.getName());
        String displayName = buildDisplayName(source.getName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, source, displayName, mime);
        } else {
            saveLegacy(context, source, displayName, mime);
        }
    }

    private static void saveWithMediaStore(Context context,
                                           File source,
                                           String displayName,
                                           String mime) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + ALBUM);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("无法创建相册记录");
        }
        try (InputStream in = new FileInputStream(source);
             OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) {
                throw new IOException("无法打开相册输出流");
            }
            copy(in, out);
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            throw e;
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
    }

    @SuppressWarnings("deprecation")
    private static void saveLegacy(Context context,
                                   File source,
                                   String displayName,
                                   String mime) throws IOException {
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES),
                ALBUM);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建相册目录");
        }
        File target = uniqueFile(dir, displayName);
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
        MediaScannerConnection.scanFile(
                context,
                new String[]{target.getAbsolutePath()},
                new String[]{mime},
                null);
    }

    private static File uniqueFile(File dir, String displayName) {
        File target = new File(dir, displayName);
        if (!target.exists()) {
            return target;
        }
        int dot = displayName.lastIndexOf('.');
        String base = dot > 0 ? displayName.substring(0, dot) : displayName;
        String ext = dot > 0 ? displayName.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            target = new File(dir, base + "_" + i + ext);
            if (!target.exists()) {
                return target;
            }
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    private static String buildDisplayName(String fileName) {
        String ext = ".jpg";
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            ext = fileName.substring(dot).toLowerCase(Locale.ROOT);
        }
        String timestamp = new SimpleDateFormat(
                "yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        return "CoTalk_" + timestamp + ext;
    }

    private static String mimeFor(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private static void copy(InputStream in, OutputStream out)
            throws IOException {
        byte[] buffer = new byte[8192];
        int length;
        while ((length = in.read(buffer)) != -1) {
            out.write(buffer, 0, length);
        }
    }
}
