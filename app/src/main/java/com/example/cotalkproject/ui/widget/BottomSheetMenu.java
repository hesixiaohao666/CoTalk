package com.example.cotalkproject.ui.widget;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

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

        int spacing = context.getResources().getDimensionPixelSize(
                R.dimen.bottom_sheet_action_spacing);
        int half = spacing / 2;
        content.setPaddingRelative(
                content.getPaddingStart(), half,
                content.getPaddingEnd(), half);

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
            item.setPaddingRelative(item.getPaddingStart(), half,
                    item.getPaddingEnd(), half);
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

        addDivider(context, container, half);

        TextView cancel = (TextView) LayoutInflater.from(context)
                .inflate(R.layout.item_bottom_sheet_action,
                        container, false);
        cancel.setText(R.string.cancel);
        cancel.setPaddingRelative(cancel.getPaddingStart(), half,
                cancel.getPaddingEnd(), half);
        cancel.setTextColor(ContextCompat.getColor(
                context, R.color.text_secondary));
        cancel.setOnClickListener(v -> dialog.dismiss());
        container.addView(cancel);

        // 操作菜单的 A/B/C/D 间距已包含底部留白；不再额外叠加导航栏 inset，
        // 否则 dimens 调到 0 仍会剩下一段无法消除的底部距离。
        show(dialog, content, null, false);
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

    private static void addDivider(Context context,
                                   LinearLayout container,
                                   int spacing) {
        View divider = new View(context);
        int height = Math.max(1, Math.round(
                context.getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.topMargin = spacing;
        params.bottomMargin = spacing;
        divider.setLayoutParams(params);
        divider.setBackgroundColor(ContextCompat.getColor(
                context, R.color.divider));
        container.addView(divider);
    }

    private static void applyNavigationBar(Window window, Context context) {
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.setNavigationBarDividerColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightNavigationBars(!night);
    }

    private static void applyBottomInset(View content) {
        int baseBottom = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            Insets navigation = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars());
            view.setPadding(
                    view.getPaddingLeft(),
                    view.getPaddingTop(),
                    view.getPaddingRight(),
                    baseBottom + navigation.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }

    private static void show(BottomSheetDialog dialog, View content) {
        show(dialog, content, null, true);
    }

    private static void show(BottomSheetDialog dialog,
                             View content,
                             Runnable afterShow) {
        show(dialog, content, afterShow, true);
    }

    private static void show(BottomSheetDialog dialog,
                             View content,
                             Runnable afterShow,
                             boolean includeNavigationInset) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.5f);
            window.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            applyNavigationBar(window, dialog.getContext());
        }
        dialog.setOnShowListener(d -> {
            Window shownWindow = dialog.getWindow();
            if (shownWindow != null) {
                applyNavigationBar(shownWindow, dialog.getContext());
            }
            View sheet = dialog.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.setBackgroundResource(R.drawable.bg_bottom_sheet);
                sheet.setFitsSystemWindows(false);
                ViewGroup.LayoutParams params = sheet.getLayoutParams();
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                sheet.setLayoutParams(params);
                BottomSheetBehavior<View> behavior =
                        BottomSheetBehavior.from(sheet);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
            if (includeNavigationInset) {
                applyBottomInset(content);
            }
            if (afterShow != null) {
                afterShow.run();
            }
        });
        dialog.show();
    }
}
