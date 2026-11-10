package com.example.cotalkproject.ui.widget;

import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.example.cotalkproject.R;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

/**
 * 统一底部弹层：操作菜单、确认框、输入框。
 */
public final class BottomSheetMenu {

    public static final class Action {
        public final String label;
        public final boolean danger;
        public final Runnable onClick;

        public Action(String label, Runnable onClick) {
            this(label, false, onClick);
        }

        public Action(String label, boolean danger, Runnable onClick) {
            this.label = label;
            this.danger = danger;
            this.onClick = onClick;
        }
    }

    public interface InputCallback {
        boolean onInput(String text);

        default void onCancel() {
        }
    }

    private BottomSheetMenu() {
    }

    public static BottomSheetDialog showActions(Context context,
                                                String title,
                                                List<Action> actions) {
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        View content = inflate(context);
        dialog.setContentView(content);

        TextView titleView = content.findViewById(R.id.tv_sheet_title);
        if (title == null || title.trim().isEmpty()) {
            titleView.setVisibility(View.GONE);
        } else {
            titleView.setText(title);
        }

        LinearLayout container = content.findViewById(R.id.ll_sheet_actions);
        for (Action action : actions) {
            TextView item = (TextView) LayoutInflater.from(context)
                    .inflate(R.layout.item_bottom_sheet_action,
                            container, false);
            item.setText(action.label);
            item.setTextColor(ContextCompat.getColor(context,
                    action.danger ? R.color.danger : R.color.text_primary));
            item.setOnClickListener(v -> {
                dialog.dismiss();
                if (action.onClick != null) {
                    action.onClick.run();
                }
            });
            container.addView(item);
        }

        TextView cancel = (TextView) LayoutInflater.from(context)
                .inflate(R.layout.item_bottom_sheet_action,
                        container, false);
        cancel.setText(R.string.cancel);
        cancel.setTextColor(ContextCompat.getColor(
                context, R.color.text_secondary));
        cancel.setOnClickListener(v -> dialog.dismiss());
        container.addView(cancel);

        show(dialog, content);
        return dialog;
    }

    public static BottomSheetDialog showConfirm(Context context,
                                                String title,
                                                String message,
                                                String positive,
                                                Runnable onPositive) {
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        View content = inflate(context);
        dialog.setContentView(content);

        TextView titleView = content.findViewById(R.id.tv_sheet_title);
        titleView.setText(title);
        TextView messageView = content.findViewById(R.id.tv_sheet_message);
        messageView.setText(message);
        messageView.setVisibility(View.VISIBLE);

        View buttons = content.findViewById(R.id.ll_sheet_buttons);
        buttons.setVisibility(View.VISIBLE);
        TextView secondary = content.findViewById(R.id.btn_sheet_secondary);
        secondary.setOnClickListener(v -> dialog.dismiss());
        TextView primary = content.findViewById(R.id.btn_sheet_primary);
        primary.setText(positive);
        primary.setOnClickListener(v -> {
            dialog.dismiss();
            if (onPositive != null) {
                onPositive.run();
            }
        });

        show(dialog, content);
        return dialog;
    }

    public static BottomSheetDialog showInput(Context context,
                                              String title,
                                              String message,
                                              String hint,
                                              boolean password,
                                              InputCallback callback) {
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        View content = inflate(context);
        dialog.setContentView(content);

        TextView titleView = content.findViewById(R.id.tv_sheet_title);
        titleView.setText(title);
        TextView messageView = content.findViewById(R.id.tv_sheet_message);
        messageView.setText(message);
        messageView.setVisibility(View.VISIBLE);

        EditText input = content.findViewById(R.id.et_sheet_input);
        input.setHint(hint);
        input.setVisibility(View.VISIBLE);
        if (password) {
            input.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }

        View buttons = content.findViewById(R.id.ll_sheet_buttons);
        buttons.setVisibility(View.VISIBLE);
        TextView secondary = content.findViewById(R.id.btn_sheet_secondary);
        TextView primary = content.findViewById(R.id.btn_sheet_primary);

        final boolean[] handled = {false};
        secondary.setOnClickListener(v -> {
            handled[0] = true;
            dialog.dismiss();
            if (callback != null) {
                callback.onCancel();
            }
        });
        primary.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (callback == null || callback.onInput(text)) {
                handled[0] = true;
                dialog.dismiss();
            }
        });
        dialog.setOnDismissListener(d -> {
            if (!handled[0] && callback != null) {
                callback.onCancel();
            }
        });

        show(dialog, content, () -> {
            input.requestFocus();
            input.postDelayed(() -> {
                InputMethodManager manager = (InputMethodManager)
                        context.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (manager != null) {
                    manager.showSoftInput(input,
                            InputMethodManager.SHOW_IMPLICIT);
                }
            }, 150L);
        });
        return dialog;
    }

    private static View inflate(Context context) {
        return LayoutInflater.from(context).inflate(
                R.layout.sheet_bottom_menu, null, false);
    }

    private static void show(BottomSheetDialog dialog, View content) {
        show(dialog, content, null);
    }

    private static void show(BottomSheetDialog dialog,
                             View content,
                             Runnable afterShow) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.5f);
            window.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.setOnShowListener(d -> {
            View sheet = dialog.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT);
                ViewGroup.LayoutParams params = sheet.getLayoutParams();
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                sheet.setLayoutParams(params);
                BottomSheetBehavior<View> behavior =
                        BottomSheetBehavior.from(sheet);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
            if (afterShow != null) {
                afterShow.run();
            }
        });
        dialog.show();
    }
}
