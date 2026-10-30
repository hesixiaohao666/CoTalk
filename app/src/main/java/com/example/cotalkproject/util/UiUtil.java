package com.example.cotalkproject.util;

import android.app.Activity;
import android.view.View;
import android.view.Window;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 统一处理 #66CCFF 顶栏、浅色状态栏图标和 edge-to-edge 边距。
 */
public final class UiUtil {

    private UiUtil() {
    }

    public static void applyLightSystemBars(Activity activity) {
        Window window = activity.getWindow();
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(true);
        controller.setAppearanceLightNavigationBars(true);
    }

    /**
     * 左右/底部交给根布局，顶部交给标题栏，让蓝色标题栏覆盖状态栏区域。
     */
    public static void applyEdgeToEdgeInsets(View root, View titleBar) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, 0, bars.right, 0);
            if (titleBar != null) {
                titleBar.setPadding(0, bars.top, 0, 0);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
