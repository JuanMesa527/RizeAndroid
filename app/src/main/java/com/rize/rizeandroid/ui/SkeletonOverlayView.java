package com.rize.rizeandroid.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;

import java.util.List;

/**
 * Transparent overlay view that draws the MediaPipe skeleton on top of a VideoView.
 *
 * Landmarks are stored as NormalizedLandmark (x,y in [0,1] relative to the video frame).
 * The view replicates the fitCenter scaling used by VideoView so the skeleton aligns
 * precisely with the video content even when the video has a different aspect ratio
 * than the container.
 */
public class SkeletonOverlayView extends View {

    private List<NormalizedLandmark> landmarks;
    private int videoWidth  = 1920;
    private int videoHeight = 1080;

    // Content bounds within this view (letterbox / pillarbox safe area)
    private float drawLeft, drawTop, drawRight, drawBottom;

    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SkeletonOverlayView(Context ctx) {
        super(ctx); init();
    }
    public SkeletonOverlayView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs); init();
    }
    public SkeletonOverlayView(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle); init();
    }

    private void init() {
        // Orange points – same palette as OverlayView.kt
        pointPaint.setColor(Color.parseColor("#dc965a"));
        pointPaint.setStyle(Paint.Style.FILL);

        // Green lines
        linePaint.setColor(Color.parseColor("#4CAF50"));
        linePaint.setStyle(Paint.Style.STROKE);

        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
    }

    /** Call after the video is decoded to get correct aspect-ratio alignment. */
    public void setVideoSize(int w, int h) {
        videoWidth  = w;
        videoHeight = h;
        computeBounds();
        invalidate();
    }

    /**
     * Update the landmarks shown on this frame and (optionally) the video dimensions.
     * Pass null to clear the overlay.
     */
    public void setLandmarks(List<NormalizedLandmark> lms, int vw, int vh) {
        landmarks = lms;
        if (vw != videoWidth || vh != videoHeight) {
            videoWidth  = vw;
            videoHeight = vh;
            computeBounds();
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeBounds();
    }

    /**
     * Compute the actual content rectangle using fitCenter scaling,
     * which is what VideoView uses by default.
     */
    private void computeBounds() {
        float vw = getWidth(), vh = getHeight();
        if (vw == 0 || vh == 0 || videoWidth == 0 || videoHeight == 0) {
            drawLeft = 0; drawTop = 0; drawRight = vw; drawBottom = vh;
            return;
        }
        float scale = Math.min(vw / videoWidth, vh / videoHeight);
        float cw = videoWidth  * scale;
        float ch = videoHeight * scale;
        drawLeft   = (vw - cw) / 2f;
        drawTop    = (vh - ch) / 2f;
        drawRight  = drawLeft + cw;
        drawBottom = drawTop  + ch;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (landmarks == null || landmarks.isEmpty()) return;

        float cw = drawRight  - drawLeft;
        float ch = drawBottom - drawTop;

        // Scale point radius and line width proportionally to the content area
        float ptRadius = Math.max(5f, cw / 90f);
        linePaint.setStrokeWidth(Math.max(2f, cw / 180f));

        // ── Connections ───────────────────────────────────────────────────────
        for (var conn : PoseLandmarker.POSE_LANDMARKS) {
            int s = conn.start(), e = conn.end();
            if (s >= landmarks.size() || e >= landmarks.size()) continue;
            NormalizedLandmark ls = landmarks.get(s);
            NormalizedLandmark le = landmarks.get(e);
            canvas.drawLine(
                    drawLeft + ls.x() * cw, drawTop + ls.y() * ch,
                    drawLeft + le.x() * cw, drawTop + le.y() * ch,
                    linePaint
            );
        }

        // ── Points ────────────────────────────────────────────────────────────
        for (NormalizedLandmark lm : landmarks) {
            canvas.drawCircle(
                    drawLeft + lm.x() * cw,
                    drawTop  + lm.y() * ch,
                    ptRadius, pointPaint
            );
        }
    }
}
