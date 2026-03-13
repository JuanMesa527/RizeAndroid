package com.rize.rizeandroid;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
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

        cameraContainer = findViewById(R.id.camera_container);
        cameraTitle = findViewById(R.id.camera_title);
        cameraTimer = findViewById(R.id.camera_timer);

        String exerciseName = getIntent().getStringExtra("exercise_name");
        if (exerciseName != null) {
            cameraTitle.setText(exerciseName.toUpperCase(Locale.US) + " ANALYSIS");
        }

        setupToolbar();
        setupBottomNav();
        checkCameraPermission();
    }

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
        if (cameraViewManager != null) {
            cameraViewManager.release();
        }
    }
}
