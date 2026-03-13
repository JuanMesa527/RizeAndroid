package com.rize.rizeandroid;

import android.content.Context;
import android.widget.FrameLayout;

import androidx.lifecycle.LifecycleOwner;

public class CameraViewManager {

    private final CameraView cameraView;
    private final FrameLayout container;

    public CameraViewManager(Context context, LifecycleOwner lifecycleOwner, FrameLayout container) {
        this.container = container;
        this.cameraView = new CameraView(context, lifecycleOwner);
    }

    public void start() {
        container.removeAllViews();
        container.addView(cameraView.getView());
        cameraView.startCamera();
    }

    public void switchCamera() {
        cameraView.switchCamera();
    }

    public void release() {
        cameraView.release();
    }
}
