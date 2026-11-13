package com.example.cotalkproject.util;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.Window;
import android.widget.ScrollView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 统一处理 #66CCFF 顶栏、浅色状态栏图标、edge-to-edge 边距和输入法避让。
 */
public final class UiUtil {

    private UiUtil() {
    }

    public static void applyLightSystemBars(Activity activity) {
        Window window = activity.getWindow();
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        int mode = activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        boolean night = mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        controller.setAppearanceLightStatusBars(!night);
        controller.setAppearanceLightNavigationBars(!night);
    }

    /**
     * 左右交给根布局，顶部交给标题栏，让蓝色标题栏覆盖状态栏区域。
     * 底部不额外 padding，交给系统手势区域。
     */
    public static void applyEdgeToEdgeInsets(View root, View titleBar) {
        applyInsets(root, titleBar, null, false);
    }

    /**
     * 无标题栏页面（登录页）的左右/顶部边距。
     */
    public static void applyPageInsets(View root) {
        applyInsets(root, null, null, false);
    }

    /**
     * 无标题栏 + 输入法避让（登录页）。
     * 键盘弹出时给根布局补 IME 高度，并把焦点输入框滚到可见区域。
     */
    public static void applyPageInsetsWithIme(View root) {
        ScrollView scrollView = root instanceof ScrollView ? (ScrollView) root : null;
        applyInsets(root, null, scrollView, true);
    }

    /**
     * 有标题栏 + 输入法避让（注册页、聊天页等）。
     */
    public static void applyEdgeToEdgeInsetsWithIme(View root, View titleBar, ScrollView scrollView) {
        applyInsets(root, titleBar, scrollView, true);
    }

    /**
     * 有标题栏 + 输入法避让，但没有可滚动容器（聊天页输入栏）。
     */
    public static void applyEdgeToEdgeInsetsWithIme(View root, View titleBar) {
        applyInsets(root, titleBar, null, true);
    }

    /**
     * 聊天页：键盘弹出时给根布局补 IME 高度；键盘收起时给输入栏补系统手势栏高度，
     * 避免输入框被小白条遮挡。
     */
    public static void applyChatInsets(View root, View titleBar, View inputBar) {
        final int baseBottom = inputBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());

            view.setPadding(bars.left, 0, bars.right, ime.bottom);
            if (titleBar != null) {
                titleBar.setPadding(0, bars.top, 0, 0);
            }
            int extraBottom = ime.bottom > 0 ? 0 : bars.bottom;
            inputBar.setPaddingRelative(
                    inputBar.getPaddingStart(),
                    inputBar.getPaddingTop(),
                    inputBar.getPaddingEnd(),
                    baseBottom + extraBottom
            );
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private static void applyInsets(View root, View titleBar, ScrollView scrollView, boolean imeAware) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int top = titleBar == null ? bars.top : 0;
            int bottom = 0;
            if (imeAware) {
                bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            }

            view.setPadding(bars.left, top, bars.right, bottom);
            if (titleBar != null) {
                titleBar.setPadding(0, bars.top, 0, 0);
            }
            if (imeAware && scrollView != null && bottom > 0) {
                scrollFocusedIntoView(scrollView);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private static void scrollFocusedIntoView(ScrollView scrollView) {
        // 等键盘动画/布局稳定后再滚动，避免滚到一半又被键盘顶回去。
        scrollView.postDelayed(() -> {
            View focus = scrollView.findFocus();
            if (focus == null) {
                return;
            }
            Rect rect = new Rect();
            focus.getDrawingRect(rect);
            scrollView.offsetDescendantRectToMyCoords(focus, rect);

            int visibleBottom = scrollView.getHeight() - scrollView.getPaddingBottom();
            int margin = (int) (16 * scrollView.getResources().getDisplayMetrics().density);
            int delta = rect.bottom - visibleBottom + margin;
            if (delta > 0) {
                scrollView.smoothScrollTo(0, scrollView.getScrollY() + delta);
            }
        }, 120L);
    }
}
