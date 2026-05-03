package com.rize.rizeandroid;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.rize.rizeandroid.data.PendingSessionData;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Muestra el resumen de una sesión ya guardada en la base de datos, misma UI que
 * {@link SummaryActivity} en modo “guardado”.
 */
public class SessionHistoryDetailActivity extends AppCompatActivity {

    public static final String EXTRA_SESSION_ID = "com.rize.rizeandroid.EXTRA_SESSION_ID";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_summary);

        long sessionId = getIntent().getLongExtra(EXTRA_SESSION_ID, -1L);
        if (sessionId <= 0) {
            Toast.makeText(this, R.string.session_save_failed_toast, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupToolbar();
        setupSavedButtons();
        setupBottomNav();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        ioExecutor.execute(() -> {
            PendingSessionData data = RizeApplication.get().getSessionRepository()
                    .loadPendingSessionDataBlocking(sessionId);
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                if (data == null) {
                    Toast.makeText(this, R.string.session_save_failed_toast, Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                SummaryUiBinder.bindSummaryContent(this, data);
            });
        });
    }

    private void setupToolbar() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }

    private void setupSavedButtons() {
        TextView savedBadge = findViewById(R.id.summary_saved_badge);
        TextView btnLeft = findViewById(R.id.btn_go_home);
        TextView btnRight = findViewById(R.id.btn_save_workout);
        savedBadge.setVisibility(android.view.View.VISIBLE);
        btnLeft.setText(R.string.summary_done_btn);
        btnLeft.setOnClickListener(v -> finish());
        btnRight.setVisibility(android.view.View.GONE);
    }

    private void setupBottomNav() {
        findViewById(R.id.nav_home).setOnClickListener(v -> {
            Intent intent = new Intent(this, HomepageActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });
        findViewById(R.id.nav_fab).setOnClickListener(v -> {
            startActivity(new Intent(this, SelectActivity.class));
            finish();
        });
        findViewById(R.id.nav_stats).setOnClickListener(v -> {
            startActivity(new Intent(this, StatsHistoryActivity.class));
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdownNow();
    }
}
