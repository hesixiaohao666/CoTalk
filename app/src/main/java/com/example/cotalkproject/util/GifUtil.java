package com.example.cotalkproject.util;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.ImageDecoder;
import android.graphics.Movie;
import android.graphics.drawable.Animatable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GIF 解码、压缩与单次播放。
 *
 * 压缩使用 Movie 采样帧 + 固定 6×6×6 调色板 + GIF89a LZW 重编码；
 * 原文件已在 256px/60 帧/1MB 内时直接复用，避免无意义重编码。
 */
public final class GifUtil {

    public static final int MAX_DIMENSION = 256;
    public static final int MAX_FRAMES = 60;
    public static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    private static final int TRANSPARENT_INDEX = 255;
    private static final int MAX_DELAY_CS = 100;

    private GifUtil() {
    }

    public static boolean isGifMime(String mime) {
        return mime != null
                && mime.toLowerCase(Locale.ROOT).contains("gif");
    }

    public static boolean isGifFile(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        try (InputStream in = new FileInputStream(file)) {
            return hasGifHeader(in);
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isGifUri(Context context, Uri uri) {
        if (context == null || uri == null) {
            return false;
        }
        try (InputStream in = openStream(context, uri)) {
            return hasGifHeader(in);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasGifHeader(InputStream in) throws IOException {
        if (in == null) {
            return false;
        }
        byte[] header = new byte[6];
        int read = in.read(header);
        if (read < 6) {
            return false;
        }
        String value = new String(header, java.nio.charset.StandardCharsets.US_ASCII);
        return value.equals("GIF87a") || value.equals("GIF89a");
    }

    /**
     * 压缩 GIF 到限制内；无法满足时抛出 IOException。
     */
    public static File compressToFile(Context context,
                                      Uri uri,
                                      File output) throws IOException {
        byte[] source = readBytes(context, uri);
        GifInfo info = readInfo(source);
        if (info == null || info.width <= 0 || info.height <= 0) {
            throw new IOException("无法读取 GIF");
        }
        if (source.length <= MAX_BYTES
                && Math.max(info.width, info.height) <= MAX_DIMENSION
                && info.frames <= MAX_FRAMES) {
            writeFile(output, source);
            return output;
        }
        List<Bitmap> frames = decodeFrames(source);
        if (frames.isEmpty()) {
            throw new IOException("GIF 帧解码失败");
        }
        int delayMs = info.durationMs > 0
                ? Math.max(20, info.durationMs / frames.size())
                : 100;
        int delayCs = Math.max(2, Math.min(MAX_DELAY_CS, delayMs / 10));
        byte[] encoded = encode(frames, delayCs);
        while (encoded.length > MAX_BYTES && frames.size() > 1) {
            List<Bitmap> reduced = new ArrayList<>();
            for (int i = 0; i < frames.size(); i += 2) {
                reduced.add(frames.get(i));
            }
            frames = reduced;
            delayCs = Math.max(2, Math.min(MAX_DELAY_CS,
                    delayCs * 2));
            encoded = encode(frames, delayCs);
        }
        if (encoded.length > MAX_BYTES) {
            throw new IOException("GIF 压缩后仍超过 1MB");
        }
        writeFile(output, encoded);
        return output;
    }

    /**
     * 读取 GIF 时长，供 API 28+ 播放器按时停止。
     */
    public static int durationMs(File file) {
        if (file == null || !file.isFile()) {
            return 0;
        }
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) != -1) {
                out.write(buffer, 0, length);
                if (out.size() > MAX_SOURCE_BYTES) {
                    break;
                }
            }
            GifInfo info = readInfo(out.toByteArray());
            return info == null ? 0 : info.durationMs;
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * 创建单次播放器；播放结束后回调 onEnd。
     */
    public static GifAnimator createAnimator(Context context,
                                             File file,
                                             Runnable onEnd) {
        if (file == null || !file.isFile()) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return createAnimatedImageDrawable(file, onEnd);
            } catch (Exception ignored) {
                // 回退 Movie
            }
        }
        return createMovieAnimator(file, onEnd);
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private static GifAnimator createAnimatedImageDrawable(
            File file, Runnable onEnd) throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(file);
        Drawable drawable = ImageDecoder.decodeDrawable(source,
                (decoder, info, src) ->
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
        if (!(drawable instanceof AnimatedImageDrawable)) {
            throw new IOException("不是动态 GIF");
        }
        AnimatedImageDrawable animated = (AnimatedImageDrawable) drawable;
        return new GifAnimator() {
            private final Handler handler =
                    new Handler(Looper.getMainLooper());
            private final AtomicBoolean finished = new AtomicBoolean();
            private final Runnable stopTask = () -> finish();

            @Override
            public Drawable drawable() {
                return animated;
            }

            @Override
            public void start() {
                finished.set(false);
                animated.setRepeatCount(0);
                animated.start();
                int duration = durationMs(file);
                handler.postDelayed(stopTask,
                        duration > 0 ? duration : 1500);
            }

            @Override
            public void stop() {
                handler.removeCallbacks(stopTask);
                animated.stop();
            }

            @Override
            public void release() {
                stop();
            }

            private void finish() {
                animated.stop();
                if (finished.compareAndSet(false, true) && onEnd != null) {
                    onEnd.run();
                }
            }
        };
    }

    private static GifAnimator createMovieAnimator(File file,
                                                   Runnable onEnd) {
        Movie movie = Movie.decodeFile(file.getAbsolutePath());
        if (movie == null) {
            return null;
        }
        MovieDrawable drawable = new MovieDrawable(movie, onEnd);
        return new GifAnimator() {
            @Override
            public Drawable drawable() {
                return drawable;
            }

            @Override
            public void start() {
                drawable.start();
            }

            @Override
            public void stop() {
                drawable.stop();
            }

            @Override
            public void release() {
                drawable.stop();
            }
        };
    }

    private static List<Bitmap> decodeFrames(byte[] source)
            throws IOException {
        Movie movie = Movie.decodeByteArray(source, 0, source.length);
        if (movie == null || movie.width() <= 0 || movie.height() <= 0) {
            throw new IOException("GIF 帧解码失败");
        }
        int width = movie.width();
        int height = movie.height();
        float scale = Math.min(1f, MAX_DIMENSION
                / (float) Math.max(width, height));
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        int duration = Math.max(0, movie.duration());
        int count = duration <= 0 ? 1
                : Math.min(MAX_FRAMES, Math.max(1, duration / 50));
        List<Bitmap> frames = new ArrayList<>();
        Bitmap previous = null;
        for (int i = 0; i < count; i++) {
            int time = count == 1 ? 0 : (int) ((long) duration * i / count);
            Bitmap frame = Bitmap.createBitmap(targetWidth, targetHeight,
                    Bitmap.Config.ARGB_8888);
            frame.eraseColor(Color.TRANSPARENT);
            Canvas canvas = new Canvas(frame);
            canvas.scale(targetWidth / (float) width,
                    targetHeight / (float) height);
            movie.setTime(time);
            movie.draw(canvas, 0, 0);
            if (previous != null && previous.sameAs(frame)) {
                frame.recycle();
                continue;
            }
            frames.add(frame);
            previous = frame;
        }
        if (frames.isEmpty()) {
            Bitmap frame = Bitmap.createBitmap(targetWidth, targetHeight,
                    Bitmap.Config.ARGB_8888);
            frame.eraseColor(Color.TRANSPARENT);
            frames.add(frame);
        }
        return frames;
    }

    private static byte[] encode(List<Bitmap> frames, int delayCs)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int width = frames.get(0).getWidth();
        int height = frames.get(0).getHeight();
        out.write("GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeShort(out, width);
        writeShort(out, height);
        out.write(0xF7); // global table, 256 colors
        out.write(TRANSPARENT_INDEX);
        out.write(0);
        writePalette(out);
        writeLoopExtension(out);
        for (Bitmap frame : frames) {
            writeGraphicControl(out, delayCs);
            out.write(0x2C);
            writeShort(out, 0);
            writeShort(out, 0);
            writeShort(out, width);
            writeShort(out, height);
            out.write(0);
            out.write(8);
            writeSubBlocks(out, lzw(frame));
        }
        out.write(0x3B);
        return out.toByteArray();
    }

    private static void writePalette(OutputStream out) throws IOException {
        for (int i = 0; i < 256; i++) {
            if (i == TRANSPARENT_INDEX) {
                out.write(0);
                out.write(0);
                out.write(0);
                continue;
            }
            int r = (i / 36) * 43;
            int g = ((i / 6) % 6) * 43;
            int b = (i % 6) * 43;
            out.write(Math.min(255, r));
            out.write(Math.min(255, g));
            out.write(Math.min(255, b));
        }
    }

    private static void writeLoopExtension(OutputStream out) throws IOException {
        out.write(0x21);
        out.write(0xFF);
        out.write(11);
        out.write("NETSCAPE2.0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(3);
        out.write(1);
        writeShort(out, 0);
        out.write(0);
    }

    private static void writeGraphicControl(OutputStream out, int delayCs)
            throws IOException {
        out.write(0x21);
        out.write(0xF9);
        out.write(4);
        out.write(0x09); // disposal=2, transparent
        writeShort(out, delayCs);
        out.write(TRANSPARENT_INDEX);
        out.write(0);
    }

    private static byte[] lzw(Bitmap bitmap) {
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0,
                bitmap.getWidth(), bitmap.getHeight());
        byte[] indices = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            indices[i] = (byte) paletteIndex(pixels[i]);
        }
        return GifLzwEncoder.encode(indices);
    }

    private static int paletteIndex(int color) {
        int alpha = (color >>> 24) & 0xFF;
        if (alpha < 128) {
            return TRANSPARENT_INDEX;
        }
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int ri = Math.min(5, r * 6 / 256);
        int gi = Math.min(5, g * 6 / 256);
        int bi = Math.min(5, b * 6 / 256);
        return ri * 36 + gi * 6 + bi;
    }

    private static void writeSubBlocks(OutputStream out, byte[] data)
            throws IOException {
        int offset = 0;
        while (offset < data.length) {
            int length = Math.min(255, data.length - offset);
            out.write(length);
            out.write(data, offset, length);
            offset += length;
        }
        out.write(0);
    }

    private static void writeShort(OutputStream out, int value)
            throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeFile(File output, byte[] bytes)
            throws IOException {
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建 GIF 目录");
        }
        try (FileOutputStream out = new FileOutputStream(output)) {
            out.write(bytes);
        }
    }

    private static byte[] readBytes(Context context, Uri uri)
            throws IOException {
        try (InputStream in = openStream(context, uri)) {
            if (in == null) {
                throw new IOException("无法读取 GIF");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) != -1) {
                out.write(buffer, 0, length);
                if (out.size() > MAX_SOURCE_BYTES) {
                    throw new IOException("GIF 文件过大");
                }
            }
            return out.toByteArray();
        }
    }

    private static InputStream openStream(Context context, Uri uri)
            throws IOException {
        if (uri == null) {
            throw new IOException("GIF 地址为空");
        }
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null) {
                throw new IOException("GIF 地址无效");
            }
            return new FileInputStream(path);
        }
        ContentResolver resolver = context.getContentResolver();
        return resolver.openInputStream(uri);
    }

    private static GifInfo readInfo(byte[] data) {
        if (data == null || data.length < 13
                || data[0] != 'G' || data[1] != 'I' || data[2] != 'F') {
            return null;
        }
        GifInfo info = new GifInfo();
        info.width = (data[6] & 0xFF) | ((data[7] & 0xFF) << 8);
        info.height = (data[8] & 0xFF) | ((data[9] & 0xFF) << 8);
        int packed = data[10] & 0xFF;
        int pos = 13;
        if ((packed & 0x80) != 0) {
            pos += 3 * (1 << ((packed & 0x07) + 1));
        }
        int delayCs = 0;
        while (pos < data.length) {
            int marker = data[pos++] & 0xFF;
            if (marker == 0x3B) {
                break;
            }
            if (marker == 0x21) {
                if (pos >= data.length) {
                    break;
                }
                int label = data[pos++] & 0xFF;
                if (label == 0xF9 && pos < data.length) {
                    int size = data[pos++] & 0xFF;
                    if (size >= 4 && pos + size <= data.length) {
                        delayCs += (data[pos + 1] & 0xFF)
                                | ((data[pos + 2] & 0xFF) << 8);
                    }
                    pos += size;
                    if (pos < data.length && (data[pos] & 0xFF) == 0) {
                        pos++;
                    }
                } else {
                    pos = skipSubBlocks(data, pos);
                }
                if (pos < 0) {
                    break;
                }
                continue;
            }
            if (marker != 0x2C || pos + 9 > data.length) {
                break;
            }
            int imagePacked = data[pos + 8] & 0xFF;
            pos += 9;
            if ((imagePacked & 0x80) != 0) {
                pos += 3 * (1 << ((imagePacked & 0x07) + 1));
            }
            if (pos >= data.length) {
                break;
            }
            pos++; // LZW min code size
            pos = skipSubBlocks(data, pos);
            if (pos < 0) {
                break;
            }
            info.frames++;
        }
        info.durationMs = delayCs > 0
                ? delayCs * 10 : Math.max(0, info.frames) * 100;
        return info;
    }

    private static int skipSubBlocks(byte[] data, int pos) {
        while (pos >= 0 && pos < data.length) {
            int size = data[pos++] & 0xFF;
            if (size == 0) {
                return pos;
            }
            pos += size;
            if (pos > data.length) {
                return -1;
            }
        }
        return -1;
    }

    public static final class GifInfo {
        public int width;
        public int height;
        public int frames;
        public int durationMs;
    }

    public interface GifAnimator {
        Drawable drawable();

        void start();

        void stop();

        void release();
    }

    /**
     * API 26–27 的 Movie 兜底播放器：播放一轮后回到首帧。
     */
    private static final class MovieDrawable extends Drawable
            implements Animatable, Runnable {
        private final Movie movie;
        private final Runnable onEnd;
        private final Handler handler =
                new Handler(Looper.getMainLooper());
        private boolean running;
        private long startTime;
        private int duration;

        MovieDrawable(Movie movie, Runnable onEnd) {
            this.movie = movie;
            this.onEnd = onEnd;
            this.duration = Math.max(1, movie.duration());
        }

        @Override
        public void start() {
            if (running) {
                return;
            }
            running = true;
            startTime = SystemClock.uptimeMillis();
            handler.removeCallbacks(this);
            handler.post(this);
        }

        @Override
        public void stop() {
            running = false;
            handler.removeCallbacks(this);
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void run() {
            if (!running) {
                return;
            }
            long elapsed = SystemClock.uptimeMillis() - startTime;
            if (elapsed >= duration) {
                movie.setTime(Math.max(0, duration - 1));
                invalidateSelf();
                running = false;
                if (onEnd != null) {
                    onEnd.run();
                }
                return;
            }
            movie.setTime((int) elapsed);
            invalidateSelf();
            handler.postDelayed(this, 16);
        }

        @Override
        public void draw(Canvas canvas) {
            if (movie.width() <= 0 || movie.height() <= 0) {
                return;
            }
            int save = canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / (float) movie.width(),
                    getBounds().height() / (float) movie.height());
            movie.draw(canvas, 0, 0);
            canvas.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return movie.width();
        }

        @Override
        public int getIntrinsicHeight() {
            return movie.height();
        }
    }
}
