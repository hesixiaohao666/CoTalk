package com.example.cotalkproject.util;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * 相册头像：采样、居中裁剪、256×256 JPEG、Base64。
 */
public final class ImageUtil {

    private static final int TARGET_SIZE = 256;
    private static final int JPEG_QUALITY = 85;
    private static final int DECODE_MAX = 512;

    public static final int MAX_CHAT_BYTES = 1024 * 1024;
    private static final int CHAT_MAX_DIMENSION = 1920;
    private static final int EMOJI_MAX_DIMENSION = 512;

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

    /**
     * 聊天图片压缩：最长边不超过 1920，逐步降低 JPEG 质量/尺寸直到不超过 1MB。
     */
    public static File compressForChat(Context context, Uri uri)
            throws IOException {
        File dir = new File(context.getFilesDir(), "chat_cache");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建聊天图片目录");
        }
        File output = new File(dir, "img_"
                + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 6) + ".jpg");
        try {
            return compressToFile(context, uri, output, CHAT_MAX_DIMENSION);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            throw e;
        }
    }

    public static File compressEmojiToFile(Context context,
                                           Uri uri,
                                           File output) throws IOException {
        return compressToFile(context, uri, output, EMOJI_MAX_DIMENSION);
    }

    public static Bitmap decodeFileSampled(File file, int maxDimension) {
        if (file == null || !file.isFile()) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        int max = Math.max(bounds.outWidth, bounds.outHeight);
        while (max > 0 && max / sample > maxDimension) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
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

    public static void deleteRemoteAvatarCache(Context context) {
        File root = new File(context.getFilesDir(), "avatars");
        File[] files = root.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                deleteRecursively(file);
            }
        }
    }

    public static void deleteAllAvatarCache(Context context) {
        deleteRecursively(new File(context.getFilesDir(), "avatars"));
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static File compressToFile(Context context,
                                       Uri uri,
                                       File output,
                                       int maxDimension) throws IOException {
        Bitmap decoded = decodeSampled(context, uri, maxDimension);
        if (decoded == null) {
            throw new IOException("无法读取图片");
        }
        Bitmap current = scaleDown(decoded, maxDimension);
        if (current != decoded) {
            decoded.recycle();
        }

        try {
            int quality = 90;
            byte[] data;
            while (true) {
                data = compressJpeg(current, quality);
                if (data.length <= MAX_CHAT_BYTES) {
                    break;
                }
                if (quality > 50) {
                    quality -= 10;
                    continue;
                }
                int nextWidth = Math.max(64, (int) (current.getWidth() * 0.8f));
                int nextHeight = Math.max(64, (int) (current.getHeight() * 0.8f));
                if (nextWidth >= current.getWidth()
                        && nextHeight >= current.getHeight()) {
                    throw new IOException("图片压缩后仍超过 1MB");
                }
                Bitmap smaller = Bitmap.createScaledBitmap(
                        current, nextWidth, nextHeight, true);
                if (smaller != current) {
                    current.recycle();
                }
                current = smaller;
                quality = 85;
            }

            File parent = output.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("无法创建图片目录");
            }
            try (FileOutputStream out = new FileOutputStream(output)) {
                out.write(data);
            }
            return output;
        } finally {
            if (!current.isRecycled()) {
                current.recycle();
            }
        }
    }

    private static byte[] compressJpeg(Bitmap bitmap, int quality)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
            throw new IOException("图片压缩失败");
        }
        return out.toByteArray();
    }

    private static Bitmap scaleDown(Bitmap source, int maxDimension) {
        int width = source.getWidth();
        int height = source.getHeight();
        int max = Math.max(width, height);
        if (max <= maxDimension) {
            return source;
        }
        float scale = maxDimension / (float) max;
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
    }

    private static Bitmap decodeSampled(Context context, Uri uri)
            throws IOException {
        return decodeSampled(context, uri, DECODE_MAX);
    }

    private static Bitmap decodeSampled(Context context,
                                        Uri uri,
                                        int maxDimension) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = openStream(context, uri)) {
            if (in == null) {
                return null;
            }
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample
                > maxDimension) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream in = openStream(context, uri)) {
            if (in == null) {
                return null;
            }
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null) {
            return null;
        }
        return applyExifRotation(bitmap, readOrientation(context, uri));
    }

    private static InputStream openStream(Context context, Uri uri)
            throws IOException {
        if (uri == null) {
            throw new IOException("图片地址为空");
        }
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null) {
                throw new IOException("图片地址无效");
            }
            return new FileInputStream(path);
        }
        ContentResolver resolver = context.getContentResolver();
        return resolver.openInputStream(uri);
    }

    private static int readOrientation(Context context, Uri uri) {
        try (InputStream in = openStream(context, uri)) {
            if (in == null) {
                return ExifInterface.ORIENTATION_NORMAL;
            }
            ExifInterface exif = new ExifInterface(in);
            return exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap applyExifRotation(Bitmap bitmap, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.postRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.postRotate(270);
                matrix.postScale(-1, 1);
                break;
            default:
                return bitmap;
        }
        Bitmap rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) {
            bitmap.recycle();
        }
        return rotated;
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
