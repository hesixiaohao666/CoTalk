package com.example.cotalkproject.util;

import android.media.AudioAttributes;
import android.media.MediaPlayer;

import java.io.File;

/**
 * 单例语音播放器：媒体音量、语音内容类型、播放互斥。
 */
public class VoicePlayer {

    public interface Listener {
        void onStarted();

        void onCompleted();

        void onError(String message);
    }

    private MediaPlayer player;
    private Listener listener;
    private boolean paused;

    public void play(File file, Listener listener) {
        stop();
        if (file == null || !file.isFile()) {
            if (listener != null) {
                listener.onError("语音文件不存在");
            }
            return;
        }
        this.listener = listener;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                if (player != mp) {
                    return;
                }
                try {
                    mp.start();
                    paused = false;
                    if (VoicePlayer.this.listener != null) {
                        VoicePlayer.this.listener.onStarted();
                    }
                } catch (RuntimeException e) {
                    notifyError(e.getMessage());
                }
            });
            player.setOnCompletionListener(mp -> {
                if (player != mp) {
                    return;
                }
                Listener current = VoicePlayer.this.listener;
                stop();
                if (current != null) {
                    current.onCompleted();
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                notifyError("播放失败");
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            notifyError(e.getMessage());
        }
    }

    public void pause() {
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.pause();
                    paused = true;
                }
            } catch (RuntimeException ignored) {
                // 忽略状态异常
            }
        }
    }

    public void resume() {
        if (player != null && paused) {
            try {
                player.start();
                paused = false;
            } catch (RuntimeException e) {
                notifyError(e.getMessage());
            }
        }
    }

    public boolean isPlaying() {
        if (player == null) {
            return false;
        }
        try {
            return player.isPlaying();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public void stop() {
        MediaPlayer current = player;
        player = null;
        paused = false;
        listener = null;
        if (current != null) {
            try {
                current.stop();
            } catch (RuntimeException ignored) {
                // 未 prepare 完成时 stop 可能抛异常
            }
            try {
                current.reset();
            } catch (RuntimeException ignored) {
                // 忽略重置异常
            }
            try {
                current.release();
            } catch (RuntimeException ignored) {
                // 忽略释放异常
            }
        }
    }

    private void notifyError(String message) {
        Listener current = listener;
        stop();
        if (current != null) {
            current.onError(message == null ? "播放失败" : message);
        }
    }
}
