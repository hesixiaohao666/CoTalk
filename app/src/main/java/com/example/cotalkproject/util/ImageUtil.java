package com.example.cotalkproject.util;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 相册头像：采样、居中裁剪、256×256 JPEG、Base64。
 */
public final class ImageUtil {

    private static final int TARGET_SIZE = 256;
    private static final int JPEG_QUALITY = 85;
    private static final int DECODE_MAX = 512;

    private ImageUtil() {
    }

    public static String saveAvatar(Context context, Uri uri, long userId)
            throws IOException {
        Bitmap bitmap = decodeSampled(context, uri);
        if (bitmap == null) {
            throw new IOException("无法读取图片");
        }

        Bitmap cropped = centerCrop(bitmap);
        Bitmap scaled = Bitmap.createScaledBitmap(
                cropped, TARGET_SIZE, TARGET_SIZE, true);

        File dir = new File(context.getFilesDir(), "avatars");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建头像目录");
        }
        File output = new File(dir, "local_" + userId + ".jpg");
        try (FileOutputStream out = new FileOutputStream(output)) {
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                throw new IOException("头像压缩失败");
            }
        } finally {
            if (scaled != cropped) {
                scaled.recycle();
            }
            if (cropped != bitmap) {
                cropped.recycle();
            }
            bitmap.recycle();
        }
        return output.getAbsolutePath();
    }

    public static Bitmap loadBitmap(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        File file = new File(path);
        if (!file.exists()) {
            return null;
        }
        return BitmapFactory.decodeFile(path);
    }

    public static String fileToDataUri(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        File file = new File(path);
        if (!file.exists()) {
            return "";
        }
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) != -1) {
                out.write(buffer, 0, length);
            }
            String base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            return "data:image/jpeg;base64," + base64;
        } catch (IOException e) {
            return "";
        }
    }

    public static Bitmap decodeDataUri(String dataUri) {
        if (dataUri == null || dataUri.isEmpty()) {
            return null;
        }
        String payload = dataUri;
        int comma = dataUri.indexOf(',');
        if (dataUri.startsWith("data:") && comma > 0) {
            payload = dataUri.substring(comma + 1);
        }
        try {
            byte[] bytes = Base64.decode(payload, Base64.NO_WRAP);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    public static void deleteLocalAvatar(Context context, long userId) {
        File file = new File(new File(context.getFilesDir(), "avatars"),
                "local_" + userId + ".jpg");
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static Bitmap decodeSampled(Context context, Uri uri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            BitmapFactory.decodeStream(in, null, bounds);
        }

        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > DECODE_MAX) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            return BitmapFactory.decodeStream(in, null, options);
        }
    }

    private static Bitmap centerCrop(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int size = Math.min(width, height);
        int x = (width - size) / 2;
        int y = (height - size) / 2;
        return Bitmap.createBitmap(source, x, y, size, size);
    }
}
