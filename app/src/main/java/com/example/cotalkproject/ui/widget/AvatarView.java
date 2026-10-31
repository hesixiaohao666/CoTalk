package com.example.cotalkproject.ui.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.cotalkproject.R;
import com.example.cotalkproject.util.AvatarUtil;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.imageview.ShapeableImageView;

/**
 * 圆形头像：有图显示图片，无图显示昵称首字彩色圆。
 */
public class AvatarView extends FrameLayout {

    private final ShapeableImageView imageView;
    private final TextView fallbackView;

    public AvatarView(Context context) {
        this(context, null);
    }

    public AvatarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AvatarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        imageView = new ShapeableImageView(context);
        imageView.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setShapeAppearanceModel(ShapeAppearanceModel.builder()
                .setAllCornerSizes(ShapeAppearanceModel.PILL)
                .build());
        imageView.setVisibility(GONE);
        addView(imageView);

        fallbackView = new TextView(context);
        fallbackView.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        fallbackView.setGravity(Gravity.CENTER);
        fallbackView.setTypeface(null, Typeface.BOLD);
        fallbackView.setTextColor(Color.BLACK);
        fallbackView.setBackgroundResource(R.drawable.bg_avatar_circle);
        addView(fallbackView);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int size = Math.min(w, h);
        if (size > 0) {
            fallbackView.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.42f);
        }
    }

    public void setAvatar(String nickname, Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            imageView.setImageBitmap(bitmap);
            imageView.setVisibility(VISIBLE);
            fallbackView.setVisibility(GONE);
        } else {
            imageView.setImageDrawable(null);
            imageView.setVisibility(GONE);
            fallbackView.setVisibility(VISIBLE);
            AvatarUtil.bindInitial(fallbackView, nickname);
        }
        setContentDescription(nickname == null ? "头像" : nickname + " 的头像");
    }
}
