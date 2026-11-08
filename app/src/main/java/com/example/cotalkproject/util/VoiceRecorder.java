package com.example.cotalkproject.util;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;

/**
 * 语音录制：AAC / MPEG_4 / 单声道 / 44.1kHz / 64kbps。
 */
public class VoiceRecorder {

    public static final long MIN_DURATION_MS = 2000L;
    public static final long MAX_DURATION_MS = 30000L;

    private MediaRecorder recorder;
    private File output;
    private long startedAt;
    private boolean recording;

    public static File createOutputFile(Context context) throws IOException {
        File dir = new File(context.getCacheDir(), "voice_cache");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建语音目录");
        }
        String name = "voice_" + System.currentTimeMillis() + ".m4a";
        return new File(dir, name);
    }

    public boolean start(File file) {
        if (file == null) {
            return false;
        }
        release();
        try {
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setOutputFile(file.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            output = file;
            startedAt = SystemClock.elapsedRealtime();
            recording = true;
            return true;
        } catch (Exception e) {
            release();
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return false;
        }
    }

    /**
     * 停止并返回实际时长；录制失败或过短时返回 0，同时删除临时文件。
     */
    public long stop() {
        if (!recording) {
            release();
            return 0L;
        }
        long duration = elapsedMs();
        boolean ok = true;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            ok = false;
        } finally {
            release();
        }
        if (!ok || duration < MIN_DURATION_MS) {
            deleteOutput();
            return 0L;
        }
        return Math.min(duration, MAX_DURATION_MS);
    }

    public void cancel() {
        if (!recording) {
            release();
            return;
        }
        try {
            recorder.stop();
        } catch (RuntimeException ignored) {
            // 录制时间过短时 stop 会抛异常，直接删除。
        }
        release();
        deleteOutput();
    }

    public boolean isRecording() {
        return recording;
    }

    public long elapsedMs() {
        return recording ? SystemClock.elapsedRealtime() - startedAt : 0L;
    }

    public File getOutput() {
        return output;
    }

    private void release() {
        recording = false;
        startedAt = 0L;
        if (recorder != null) {
            try {
                recorder.reset();
            } catch (RuntimeException ignored) {
                // 忽略释放异常
            }
            try {
                recorder.release();
            } catch (RuntimeException ignored) {
                // 忽略释放异常
            }
            recorder = null;
        }
    }

    private void deleteOutput() {
        if (output != null) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            output = null;
        }
    }
}
