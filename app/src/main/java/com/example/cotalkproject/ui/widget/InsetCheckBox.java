package com.example.cotalkproject.ui.widget;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatCheckBox;

import com.example.cotalkproject.R;

/**
 * 自定义勾选框：禁止主题对 button drawable 进行 tint，保留外框与内部填充间隙。
 */
public class InsetCheckBox extends AppCompatCheckBox {

    private static final int TEXT_GAP_DP = 8;

    public InsetCheckBox(Context context) {
        super(context);
        init();
    }

    public InsetCheckBox(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public InsetCheckBox(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setSupportButtonTintList(null);
        setButtonDrawable(R.drawable.cb_inset);
    }

    @Override
    public int getCompoundPaddingLeft() {
        return super.getCompoundPaddingLeft() + Math.round(
                TEXT_GAP_DP * getResources().getDisplayMetrics().density);
    }
}
