package com.example.cotalkproject.util;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.widget.TextView;

import androidx.core.view.ViewCompat;

/**
 * 无头像时的昵称首字彩色圆。
 */
public final class AvatarUtil {

    private static final int[] COLORS = {
            0xFF66CCFF,
            0xFF4FC3F7,
            0xFF81D4FA,
            0xFF80CBC4,
            0xFFFFCC80,
            0xFFCE93D8,
            0xFFF48FB1,
            0xFFA5D6A7
    };

    private AvatarUtil() {
    }

    public static void bindInitial(TextView avatarView, String nickname) {
        String safe = nickname == null ? "" : nickname.trim();
        String initial = "?";
        if (!safe.isEmpty()) {
            int codePoint = safe.codePointAt(0);
            initial = new String(Character.toChars(codePoint));
        }
        avatarView.setText(initial);

        int index = (safe.hashCode() & 0x7FFFFFFF) % COLORS.length;
        ViewCompat.setBackgroundTintList(
                avatarView,
                ColorStateList.valueOf(COLORS[index])
        );
        avatarView.setTextColor(Color.BLACK);
        avatarView.setContentDescription(safe.isEmpty() ? "默认头像" : safe + " 的头像");
    }
}
