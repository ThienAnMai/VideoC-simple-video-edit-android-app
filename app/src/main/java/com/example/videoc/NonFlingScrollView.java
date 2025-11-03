package com.example.videoc;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.HorizontalScrollView;

public class NonFlingScrollView extends HorizontalScrollView {

    public NonFlingScrollView(Context context) {
        super(context);
    }

    public NonFlingScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NonFlingScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public NonFlingScrollView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void fling(int velocityY) {
        // By leaving this method empty, we disable the fling behavior.
        // The 'super.fling(velocityY)' call is intentionally omitted.
    }

}
