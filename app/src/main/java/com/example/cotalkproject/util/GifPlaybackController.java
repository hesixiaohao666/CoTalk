package com.example.cotalkproject.util;

/**
 * 聊天页 GIF 单实例播放：任意时刻最多一个 GIF 在播放。
 */
public class GifPlaybackController {

    private String playingKey = "";
    private GifUtil.GifAnimator animator;

    public boolean isPlaying(String key) {
        return key != null && !key.isEmpty() && key.equals(playingKey);
    }

    public String playingKey() {
        return playingKey;
    }

    /**
     * 开始新的 GIF；已有 GIF 会先停止。
     */
    public void start(String key, GifUtil.GifAnimator next) {
        stop();
        if (key == null || key.isEmpty() || next == null) {
            return;
        }
        playingKey = key;
        animator = next;
        next.start();
    }

    public void stop() {
        if (animator != null) {
            animator.stop();
            animator.release();
            animator = null;
        }
        playingKey = "";
    }

    public void release() {
        stop();
    }
}
