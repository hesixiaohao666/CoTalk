package com.example.cotalkproject.ui.widget;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.cotalkproject.R;

/**
 * 居中公告窗口：标题 + 可滚动正文 + 确认按钮。
 * 服务端公告与版本更新公告共用。
 */
public final class AnnouncementDialog {

    private AnnouncementDialog() {
    }

    public static Dialog show(Context context,
                              String title,
                              String content,
                              Runnable onConfirm) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_announcement);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);

        TextView titleView = dialog.findViewById(R.id.tv_announcement_title);
        TextView bodyView = dialog.findViewById(R.id.tv_announcement_body);
        ScrollView scroll = dialog.findViewById(R.id.sv_announcement_body);

        titleView.setText(title == null ? "" : title);
        bodyView.setText(content == null ? "" : content);

        dialog.findViewById(R.id.btn_announcement_confirm)
                .setOnClickListener(v -> {
                    dialog.dismiss();
                    if (onConfirm != null) {
                        onConfirm.run();
                    }
                });

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.55f;
            window.setAttributes(params);
            int width = (int) (context.getResources()
                    .getDisplayMetrics().widthPixels * 0.9f);
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        dialog.setOnShowListener(ignored -> {
            Window shown = dialog.getWindow();
            if (shown != null) {
                int width = (int) (context.getResources()
                        .getDisplayMetrics().widthPixels * 0.9f);
                shown.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            if (scroll == null) {
                return;
            }
            int maxHeight = (int) (context.getResources()
                    .getDisplayMetrics().heightPixels * 0.55f);
            scroll.post(() -> {
                ViewGroup.LayoutParams params = scroll.getLayoutParams();
                if (params.height > maxHeight
                        || scroll.getHeight() > maxHeight) {
                    params.height = maxHeight;
                    scroll.setLayoutParams(params);
                }
            });
        });
        dialog.show();
        return dialog;
    }
}
