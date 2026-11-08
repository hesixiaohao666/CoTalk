package com.example.cotalkproject.ui.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * 全屏图片：双指缩放 1x–4x、缩放后拖动、单击退出、长按保存。
 */
public class ZoomImageView extends AppCompatImageView {

    public interface OnLongPressListener {
        void onLongPress();
    }

    private static final float MAX_SCALE = 4f;
    private static final float MIN_SCALE = 1f;

    private final Matrix imageMatrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private OnLongPressListener longPressListener;
    private float baseScale = 1f;

    public ZoomImageView(Context context) {
        this(context, null);
    }

    public ZoomImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZoomImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setScaleType(ScaleType.MATRIX);
        setBackgroundColor(Color.BLACK);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float current = currentScale();
                        if (current <= 0f) {
                            return false;
                        }
                        float target = clamp(
                                current * detector.getScaleFactor(),
                                MIN_SCALE, MAX_SCALE);
                        float factor = target / current;
                        imageMatrix.postScale(factor, factor,
                                detector.getFocusX(), detector.getFocusY());
                        constrain();
                        setImageMatrix(imageMatrix);
                        return true;
                    }
                });

        gestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onScroll(MotionEvent e1,
                                            MotionEvent e2,
                                            float distanceX,
                                            float distanceY) {
                        if (!isZoomed()) {
                            return false;
                        }
                        imageMatrix.postTranslate(-distanceX, -distanceY);
                        constrain();
                        setImageMatrix(imageMatrix);
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        performClick();
                        return true;
                    }

                    @Override
                    public void onLongPress(MotionEvent e) {
                        if (longPressListener != null) {
                            longPressListener.onLongPress();
                        }
                    }
                });
    }

    public void setOnLongPressListener(OnLongPressListener listener) {
        longPressListener = listener;
    }

    public void resetMatrix() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        float viewWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float viewHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        float drawableWidth = drawable.getIntrinsicWidth();
        float drawableHeight = drawable.getIntrinsicHeight();
        if (viewWidth <= 0 || viewHeight <= 0
                || drawableWidth <= 0 || drawableHeight <= 0) {
            return;
        }
        baseScale = Math.min(viewWidth / drawableWidth,
                viewHeight / drawableHeight);
        imageMatrix.reset();
        imageMatrix.postScale(baseScale, baseScale);
        imageMatrix.postTranslate(
                getPaddingLeft() + (viewWidth - drawableWidth * baseScale) / 2f,
                getPaddingTop() + (viewHeight - drawableHeight * baseScale) / 2f);
        setImageMatrix(imageMatrix);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::resetMatrix);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        return true;
    }

    private boolean isZoomed() {
        return currentScale() > 1.01f;
    }

    private float currentScale() {
        if (baseScale <= 0f) {
            return MIN_SCALE;
        }
        float[] values = new float[9];
        imageMatrix.getValues(values);
        return values[Matrix.MSCALE_X] / baseScale;
    }

    private void constrain() {
        Drawable drawable = getDrawable();
        if (drawable == null) {
            return;
        }
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        float drawableWidth = drawable.getIntrinsicWidth();
        float drawableHeight = drawable.getIntrinsicHeight();
        if (viewWidth <= 0 || viewHeight <= 0
                || drawableWidth <= 0 || drawableHeight <= 0) {
            return;
        }

        RectF rect = new RectF(0, 0, drawableWidth, drawableHeight);
        imageMatrix.mapRect(rect);

        float deltaX = 0f;
        if (rect.width() <= viewWidth) {
            deltaX = (viewWidth - rect.width()) / 2f - rect.left;
        } else if (rect.left > 0) {
            deltaX = -rect.left;
        } else if (rect.right < viewWidth) {
            deltaX = viewWidth - rect.right;
        }

        float deltaY = 0f;
        if (rect.height() <= viewHeight) {
            deltaY = (viewHeight - rect.height()) / 2f - rect.top;
        } else if (rect.top > 0) {
            deltaY = -rect.top;
        } else if (rect.bottom < viewHeight) {
            deltaY = viewHeight - rect.bottom;
        }

        imageMatrix.postTranslate(deltaX, deltaY);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
