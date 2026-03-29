package com.rize.rizeandroid;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;

public class CameraActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST = 100;

    private FrameLayout cameraContainer;
    private TextView cameraTitle;
    private TextView cameraTimer;
    private CameraViewManager cameraViewManager;
    private Algorithms algorithms;

    // ── Views de métricas ─────────────────────────────────────────────────────
    private TextView metricPeakAngle;
    private TextView metricStability;
    private ProgressBar progressConsistency;

    // ── Estado de métricas ────────────────────────────────────────────────────

    private double currentAngle = 0.0;
    private double peakAngle    = 0.0;

    // EMA (Exponential Moving Average) — reacciona rápido a cambios reales.
    // Alpha alto (~0.1) = muy reactivo, Alpha bajo (~0.02) = muy suave.
    // Estabilidad: alpha bajo porque queremos tendencia, no saltos frame a frame
    private static final double STABILITY_ALPHA    = 0.05;
    private double emaStability    = 100.0; // arranca en 100%

    // Consistencia: alpha más alto porque queremos ver el cambio entre reps
    private static final double CONSISTENCY_ALPHA  = 0.08;
    private double emaConsistency  = -1.0;  // -1 = sin datos aún (calibrando)

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private int elapsedSeconds = 0;
    private boolean timerRunning = false;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (timerRunning) {
                elapsedSeconds++;
                int minutes = elapsedSeconds / 60;
                int seconds = elapsedSeconds % 60;
                cameraTimer.setText(String.format(Locale.US, "%02d:%02d", minutes, seconds));
                timerHandler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        cameraContainer     = findViewById(R.id.camera_container);
        cameraTitle         = findViewById(R.id.camera_title);
        cameraTimer         = findViewById(R.id.camera_timer);
        metricPeakAngle     = findViewById(R.id.metric_peak_angle);
        metricStability     = findViewById(R.id.metric_stability);
        progressConsistency = findViewById(R.id.progress_consistency);

        // EMA arranca en 100% para estabilidad (todo OK al inicio)
        // Consistencia arranca en -1 (sin datos hasta 3 reps)

        String exerciseName = getIntent().getStringExtra("exercise_name");
        if (exerciseName != null) {
            cameraTitle.setText(exerciseName.toUpperCase(Locale.US) + " ANALYSIS");
        }

        setupToolbar();
        setupBottomNav();
        setupAlgorithms(exerciseName);
        checkCameraPermission();
    }

    // ── Algoritmos ────────────────────────────────────────────────────────────

    private boolean isCurlExercise = false;

    private void setupAlgorithms(String exerciseName) {
        // El análisis biomecánico solo aplica para Dumbbell Curl
        isCurlExercise = exerciseName != null
                && exerciseName.toLowerCase().contains("curl");

        if (!isCurlExercise) {
            // Ocultar las métricas para ejercicios sin análisis
            findViewById(R.id.metric_peak_angle).setVisibility(android.view.View.GONE);
            findViewById(R.id.metric_stability).setVisibility(android.view.View.GONE);
            findViewById(R.id.progress_consistency).setVisibility(android.view.View.GONE);
            return;
        }

        algorithms = new Algorithms();
        algorithms.selectAlgorithm(exerciseName);

        PoseDataManager.INSTANCE.setPoseDataListener(landmarkData -> {
            algorithms.onPoseData(landmarkData);
            return kotlin.Unit.INSTANCE;
        });
        algorithms.setResultCallback(result -> {
            onAlgorithmResult(result);
            return kotlin.Unit.INSTANCE;
        });
    }

    private void onAlgorithmResult(AlgorithmResult result) {

        // ── Ángulo articular → metric_peak_angle ──────────────────────────────
        // FIX: mostramos el ángulo ACTUAL en tiempo real, no solo el pico.
        // El pico lo guardamos internamente pero no bloqueamos la actualización.
        if (result.getAngleDeg() != null) {
            currentAngle = result.getAngleDeg();
            if (currentAngle > peakAngle) {
                peakAngle = currentAngle;
            }
            // Muestra el ángulo en tiempo real (no el pico fijo)
            metricPeakAngle.setText(
                    String.format(Locale.US, "%.0f°", currentAngle)
            );
        }

        // ── Estabilidad → metric_stability ────────────────────────────────────
        // Target: 100 si no hay fatiga ni error, baja proporcionalmente.
        // Usamos EMA para que suba y baje con rango completo 0-100.
        double targetStability = 100.0;
        if (result.getFatigueDetected())       targetStability -= 50.0;
        switch (result.getTechnicalError()) {
            case MILD:     targetStability -= 20.0; break;
            case MODERATE: targetStability -= 50.0; break;
            case SEVERE:   targetStability -= 80.0; break;
            default: break;
        }
        targetStability = Math.max(0.0, targetStability);

        // EMA: emaStability = emaStability + alpha * (target - emaStability)
        emaStability = emaStability + STABILITY_ALPHA * (targetStability - emaStability);
        int displayStability = (int) Math.round(emaStability);

        metricStability.setText(String.format(Locale.US, "%d%%", displayStability));

        int stabilityColor;
        if (displayStability > 70) {
            stabilityColor = ContextCompat.getColor(this, R.color.improvement_green);
        } else if (displayStability > 40) {
            stabilityColor = ContextCompat.getColor(this, R.color.toasted_almond);
        } else {
            stabilityColor = ContextCompat.getColor(this, R.color.risk_red);
        }
        metricStability.setTextColor(stabilityColor);

        // ── Consistencia → progress_consistency ───────────────────────────────
        // Solo actualizamos tras 3 reps (cuando θ_ref ya está calibrado).
        // EMA con alpha más alto para reflejar cambios entre reps claramente.
        if (result.getErrorMagnitude() != null) {
            double error = result.getErrorMagnitude();
            // Mapea error [0°, 45°] → target [100, 0]
            // Rango del curl en MediaPipe ~130° (paper Fig.5: 0°-130° relativo)
            // Error máximo realista = 65° (mitad del rango total)
            double targetConsistency = Math.max(0.0, 100.0 - (error / 65.0) * 100.0);

            if (emaConsistency < 0) {
                // Primera vez que llega un dato real: inicializar en el valor actual
                emaConsistency = targetConsistency;
            } else {
                emaConsistency = emaConsistency + CONSISTENCY_ALPHA * (targetConsistency - emaConsistency);
            }
            progressConsistency.setProgress((int) Math.round(emaConsistency));
        }
        // Si errorMagnitude == null → calibrando, barra en 0 sin tocar
    }

    // ── Resto sin cambios ─────────────────────────────────────────────────────

    private void setupToolbar() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finishSession());
    }

    private void setupBottomNav() {
        findViewById(R.id.nav_home).setOnClickListener(v -> {
            Intent intent = new Intent(this, HomepageActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        });
        findViewById(R.id.nav_fab).setOnClickListener(v -> {
            Intent intent = new Intent(this, SelectActivity.class);
            startActivity(intent);
        });
        findViewById(R.id.nav_stats).setOnClickListener(v -> { /* Stats placeholder */ });
    }

    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void startCamera() {
        cameraViewManager = new CameraViewManager(this, this, cameraContainer);
        cameraViewManager.start();
        startTimer();
    }

    private void startTimer() {
        timerRunning = true;
        timerHandler.postDelayed(timerRunnable, 1000);
    }

    private void stopTimer() {
        timerRunning = false;
        timerHandler.removeCallbacks(timerRunnable);
    }

    private void finishSession() {
        stopTimer();
        Intent intent = new Intent(this, SummaryActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopTimer();
        if (isCurlExercise) {
            PoseDataManager.INSTANCE.setPoseDataListener(null);
        }
        if (algorithms != null) {
            algorithms.reset();
        }
        if (cameraViewManager != null) {
            cameraViewManager.release();
        }
    }
}