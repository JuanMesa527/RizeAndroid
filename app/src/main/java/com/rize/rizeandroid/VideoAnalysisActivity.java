package com.rize.rizeandroid;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VideoAnalysisActivity — two-phase video analysis.
 *
 * Phase 1 (ANALYZING): MediaCodec decodes the video sequentially (no random seeks),
 *   feeds each frame to MediaPipe in VIDEO mode, and stores lightweight FrameData
 *   (landmarks + AlgorithmResult) for every ~10 analyzed frames per second.
 *
 * Phase 2 (PLAYBACK): VideoView plays the original video with hardware acceleration
 *   at normal speed. A 30 fps sync loop picks the nearest FrameData to the current
 *   playback position and asks SkeletonOverlayView to redraw, giving the "live
 *   analysis on video" effect.
 */
public class VideoAnalysisActivity extends AppCompatActivity {

    private static final String TAG            = "VideoAnalysis";
    private static final int    ANALYSIS_FPS   = 10;   // frames/s to analyze
    private static final int    MAX_DIM        = 640;   // scale down for MediaPipe speed

    // ── Per-frame data (lightweight) ─────────────────────────────────────────
    private static class FrameData {
        final long                    timestampMs;
        final List<NormalizedLandmark> landmarks;   // null if no person detected
        final AlgorithmResult          result;

        FrameData(long ts, List<NormalizedLandmark> lm, AlgorithmResult r) {
            timestampMs = ts; landmarks = lm; result = r;
        }
    }

    // ── Views ─────────────────────────────────────────────────────────────────
    private VideoView          videoView;
    private SkeletonOverlayView skeletonOverlay;
    private View               progressOverlay;
    private ProgressBar        progressBar;
    private TextView           progressText;
    private Spinner            exerciseSpinner;
    private TextView           btnSelectVideo;
    private TextView           btnPlayPause;
    private TextView           videoInfo;
    private TextView           metricsText;
    private LinearLayout       reportPanel;
    private TextView           reportContent;

    // ── State ─────────────────────────────────────────────────────────────────
    private Uri              selectedVideoUri;
    private volatile boolean isAnalyzing  = false;
    private boolean          isPlaying    = false;
    private int              videoWidth   = 1920;
    private int              videoHeight  = 1080;

    private final List<FrameData>  frames    = new ArrayList<>();
    private final ExecutorService  executor  = Executors.newSingleThreadExecutor();
    private final Handler          uiHandler = new Handler(Looper.getMainLooper());
    private       Runnable         syncRunnable;

    // ── Video picker ──────────────────────────────────────────────────────────
    private final ActivityResultLauncher<Intent> picker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) onVideoSelected(uri);
                }
            }
    );

    // ═════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_analysis);
        bindViews();
        setupSpinner();
        setupListeners();
    }

    private void bindViews() {
        videoView       = findViewById(R.id.video_view);
        skeletonOverlay = findViewById(R.id.skeleton_overlay);
        progressOverlay = findViewById(R.id.progress_overlay);
        progressBar     = findViewById(R.id.progress_bar);
        progressText    = findViewById(R.id.progress_text);
        exerciseSpinner = findViewById(R.id.exercise_spinner);
        btnSelectVideo  = findViewById(R.id.btn_select_video);
        btnPlayPause    = findViewById(R.id.btn_play_pause);
        videoInfo       = findViewById(R.id.video_info);
        metricsText     = findViewById(R.id.metrics_text);
        reportPanel     = findViewById(R.id.report_panel);
        reportContent   = findViewById(R.id.report_content);
    }

    private void setupSpinner() {
        String[] exercises = {"Bench Press", "Barbell Squat", "Dumbbell Curl"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, exercises);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        exerciseSpinner.setAdapter(adapter);
    }

    private void setupListeners() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        btnSelectVideo.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("video/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            picker.launch(Intent.createChooser(i, "Seleccionar video"));
        });

        btnPlayPause.setOnClickListener(v -> togglePlayback());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isAnalyzing = false;
        stopSync();
        if (videoView != null) videoView.stopPlayback();
        executor.shutdownNow();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Video selection
    // ═════════════════════════════════════════════════════════════════════════

    private void onVideoSelected(Uri uri) {
        selectedVideoUri = uri;
        frames.clear();
        skeletonOverlay.setLandmarks(null, videoWidth, videoHeight);
        metricsText.setVisibility(View.GONE);
        btnPlayPause.setVisibility(View.GONE);
        reportPanel.setVisibility(View.GONE);
        videoInfo.setText("Iniciando análisis...");
        exerciseSpinner.setEnabled(false);
        btnSelectVideo.setAlpha(0.5f);

        String exercise = (String) exerciseSpinner.getSelectedItem();
        isAnalyzing = true;
        progressOverlay.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        progressText.setText("0%");

        executor.execute(() -> runAnalysis(uri, exercise));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Phase 1 — MediaCodec sequential decoding + MediaPipe
    // ═════════════════════════════════════════════════════════════════════════

    private void runAnalysis(Uri uri, String exercise) {

        Algorithms alg = new Algorithms();
        alg.selectAlgorithm(exercise);

        PoseLandmarkerHelper poseHelper = new PoseLandmarkerHelper(
                PoseLandmarkerHelper.DEFAULT_POSE_DETECTION_CONFIDENCE,
                PoseLandmarkerHelper.DEFAULT_POSE_TRACKING_CONFIDENCE,
                PoseLandmarkerHelper.DEFAULT_POSE_PRESENCE_CONFIDENCE,
                PoseLandmarkerHelper.DELEGATE_CPU,
                RunningMode.VIDEO,
                this,
                null
        );

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec     codec     = null;

        try {
            extractor.setDataSource(this, uri, null);

            // ── Find video track ──────────────────────────────────────────────
            MediaFormat fmt      = null;
            int         trackIdx = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f    = extractor.getTrackFormat(i);
                String      mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    fmt = f; trackIdx = i; break;
                }
            }
            if (trackIdx < 0) { postError("No se encontró pista de video."); return; }
            extractor.selectTrack(trackIdx);

            // ── Video dimensions ─────────────────────────────────────────────
            videoWidth  = fmt.getInteger(MediaFormat.KEY_WIDTH);
            videoHeight = fmt.getInteger(MediaFormat.KEY_HEIGHT);

            long   durationUs      = fmt.containsKey(MediaFormat.KEY_DURATION)
                    ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            long   frameIntervalUs = 1_000_000L / ANALYSIS_FPS;
            String mime            = fmt.getString(MediaFormat.KEY_MIME);

            // ── Create decoder (no Surface → can use getOutputImage()) ────────
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info       = new MediaCodec.BufferInfo();
            boolean               inputDone  = false;
            boolean               outputDone = false;
            long                  nextTargetUs = 0;
            long                  frameCount   = 0;
            long totalFrames = durationUs > 0 ? Math.max(1, durationUs / frameIntervalUs) : 100;

            while (!outputDone && isAnalyzing) {

                // ── Feed input ────────────────────────────────────────────────
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        int sz = extractor.readSampleData(inBuf, 0);
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                // ── Consume output ────────────────────────────────────────────
                int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                if (outIdx >= 0) {

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }

                    // Process only frames at target FPS
                    if (info.size > 0 && info.presentationTimeUs >= nextTargetUs) {

                        Image  img = null;
                        try { img = codec.getOutputImage(outIdx); }
                        catch (Exception ignored) {}

                        if (img != null) {
                            Bitmap bmp    = yuvToBitmap(img);
                            img.close();

                            if (bmp != null) {
                                Bitmap scaled = ensureMaxDim(bmp, MAX_DIM);
                                if (scaled != bmp) bmp.recycle();

                                long tsMs = info.presentationTimeUs / 1000;
                                PoseLandmarkerHelper.ResultBundle rb =
                                        poseHelper.detectVideoFrame(scaled, tsMs);
                                scaled.recycle();

                                List<NormalizedLandmark> lmList = null;
                                AlgorithmResult          res     = null;

                                if (rb != null && !rb.getResults().isEmpty()) {
                                    PoseLandmarkerResult pr = rb.getResults().get(0);
                                    if (!pr.landmarks().isEmpty()) {
                                        lmList = pr.landmarks().get(0);
                                        alg.onPoseData(flattenLandmarks(lmList));
                                        res = alg.getCurrentResult();
                                    }
                                }

                                final FrameData fd = new FrameData(tsMs, lmList, res);
                                synchronized (frames) { frames.add(fd); }

                                nextTargetUs = info.presentationTimeUs + frameIntervalUs;
                            }
                        }

                        frameCount++;
                        final int pct = (int) Math.min(99, frameCount * 100 / totalFrames);
                        uiHandler.post(() -> {
                            progressBar.setProgress(pct);
                            progressText.setText(pct + "%  –  " + frames.size() + " frames");
                        });
                    }

                    codec.releaseOutputBuffer(outIdx, false);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error during analysis", e);
            postError("Error: " + e.getMessage());
            return;
        } finally {
            if (codec != null) {
                try { codec.stop(); codec.release(); } catch (Exception ignored) {}
            }
            extractor.release();
            poseHelper.clearPoseLandmarker();
        }

        uiHandler.post(this::onAnalysisComplete);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Phase 2 — VideoView playback + skeleton sync
    // ═════════════════════════════════════════════════════════════════════════

    private void onAnalysisComplete() {
        progressOverlay.setVisibility(View.GONE);
        exerciseSpinner.setEnabled(true);
        btnSelectVideo.setAlpha(1f);

        if (frames.isEmpty()) {
            videoInfo.setText("Sin detección de pose en el video.");
            return;
        }

        int reps = 0;
        for (FrameData f : frames)
            if (f.result != null) reps = Math.max(reps, f.result.getRepCount());

        videoInfo.setText(String.format(Locale.US,
                "%d frames analizados  ·  %d reps detectadas", frames.size(), reps));

        // Pass video dimensions to overlay for aspect-ratio-correct skeleton
        skeletonOverlay.setVideoSize(videoWidth, videoHeight);

        videoView.setVideoURI(selectedVideoUri);
        videoView.setOnPreparedListener(mp -> {
            mp.setLooping(false);
            btnPlayPause.setVisibility(View.VISIBLE);
            metricsText.setVisibility(View.VISIBLE);
            isPlaying = true;
            btnPlayPause.setText("⏸");
            videoView.start();
            startSync();
        });
        videoView.setOnCompletionListener(mp -> {
            isPlaying = false;
            btnPlayPause.setText("▶");
            stopSync();
            skeletonOverlay.setLandmarks(null, videoWidth, videoHeight);
            reportPanel.setVisibility(View.VISIBLE);
            reportContent.setText(buildReport());
        });
        videoView.requestFocus();
    }

    // ── Sync loop: runs at ~30 fps while video plays ──────────────────────────

    private void startSync() {
        syncRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPlaying && !frames.isEmpty()) {
                    long    pos = videoView.getCurrentPosition();
                    FrameData fd = nearestFrame(pos);
                    if (fd != null) {
                        skeletonOverlay.setLandmarks(fd.landmarks, videoWidth, videoHeight);
                        if (fd.result != null) showMetrics(fd.result);
                    }
                }
                uiHandler.postDelayed(this, 33); // ~30 fps
            }
        };
        uiHandler.post(syncRunnable);
    }

    private void stopSync() {
        if (syncRunnable != null) {
            uiHandler.removeCallbacks(syncRunnable);
            syncRunnable = null;
        }
    }

    /** Binary search for the FrameData closest to posMs. */
    private FrameData nearestFrame(long posMs) {
        if (frames.isEmpty()) return null;
        int lo = 0, hi = frames.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (frames.get(mid).timestampMs < posMs) lo = mid + 1;
            else hi = mid;
        }
        if (lo > 0) {
            long dLo   = Math.abs(frames.get(lo).timestampMs     - posMs);
            long dPrev = Math.abs(frames.get(lo - 1).timestampMs - posMs);
            if (dPrev < dLo) return frames.get(lo - 1);
        }
        return frames.get(lo);
    }

    private void showMetrics(AlgorithmResult r) {
        String txt = "Rep " + r.getRepCount()
                + "   " + r.getTechnicalError()
                + (r.getFatigueDetected() ? "  ⚡ Fatiga" : "");
        metricsText.setText(txt);
        metricsText.setTextColor(
                r.getAlert() ? Color.parseColor("#FF5722") : Color.WHITE);
    }

    private void togglePlayback() {
        if (isPlaying) {
            videoView.pause();
            isPlaying = false;
            btnPlayPause.setText("▶");
        } else {
            videoView.start();
            isPlaying = true;
            btnPlayPause.setText("⏸");
            if (syncRunnable == null) startSync();
        }
    }

    private String buildReport() {
        int reps = 0, alertF = 0, fatigueF = 0;
        for (FrameData f : frames) {
            if (f.result == null) continue;
            reps    = Math.max(reps, f.result.getRepCount());
            if (f.result.getAlert())          alertF++;
            if (f.result.getFatigueDetected()) fatigueF++;
        }
        return "Ejercicio: " + exerciseSpinner.getSelectedItem() + "\n"
                + "Repeticiones: " + reps + "\n"
                + "Frames analizados: " + frames.size() + "\n"
                + "Frames con alerta: " + alertF + "\n"
                + "Frames con fatiga: " + fatigueF;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // YUV → Bitmap (MediaCodec getOutputImage output)
    // ═════════════════════════════════════════════════════════════════════════

    private static Bitmap yuvToBitmap(Image image) {
        try {
            int w = image.getWidth(), h = image.getHeight();
            Image.Plane[] planes = image.getPlanes();

            ByteBuffer yBuf = planes[0].getBuffer();
            ByteBuffer uBuf = planes[1].getBuffer();
            ByteBuffer vBuf = planes[2].getBuffer();
            int yStride    = planes[0].getRowStride();
            int uvStride   = planes[1].getRowStride();
            int uvPxStride = planes[1].getPixelStride();

            // Build NV21 byte array (Y plane + interleaved VU)
            byte[] nv21 = new byte[w * h + (w / 2) * (h / 2) * 2];

            for (int row = 0; row < h; row++) {
                yBuf.position(row * yStride);
                int toCopy = Math.min(w, yBuf.remaining());
                yBuf.get(nv21, row * w, toCopy);
            }

            int base = w * h;
            for (int row = 0; row < h / 2; row++) {
                for (int col = 0; col < w / 2; col++) {
                    int idx = row * uvStride + col * uvPxStride;
                    if (idx < vBuf.capacity() && idx < uBuf.capacity()) {
                        nv21[base + row * w + col * 2]     = vBuf.get(idx);
                        nv21[base + row * w + col * 2 + 1] = uBuf.get(idx);
                    }
                }
            }

            YuvImage yuvImg = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            yuvImg.compressToJpeg(new Rect(0, 0, w, h), 85, baos);
            byte[] bytes = baos.toByteArray();
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

        } catch (Exception e) {
            Log.e(TAG, "YUV→Bitmap conversion failed", e);
            return null;
        }
    }

    private static Bitmap ensureMaxDim(Bitmap src, int max) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= max && h <= max) return src;
        float s = Math.min((float) max / w, (float) max / h);
        return Bitmap.createScaledBitmap(src,
                Math.round(w * s), Math.round(h * s), true);
    }

    private static List<Double> flattenLandmarks(List<NormalizedLandmark> lms) {
        List<Double> out = new ArrayList<>(132);
        for (NormalizedLandmark lm : lms) {
            out.add((double) lm.x());
            out.add((double) lm.y());
            out.add((double) lm.z());
            out.add((double) lm.visibility().orElse(0f));
        }
        return out;
    }

    private void postError(String msg) {
        uiHandler.post(() -> {
            isAnalyzing = false;
            progressOverlay.setVisibility(View.GONE);
            exerciseSpinner.setEnabled(true);
            btnSelectVideo.setAlpha(1f);
            videoInfo.setText("Error: " + msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }
}
