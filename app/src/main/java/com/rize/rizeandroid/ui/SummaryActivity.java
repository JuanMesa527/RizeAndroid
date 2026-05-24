package com.rize.rizeandroid.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.rize.rizeandroid.R;
import com.rize.rizeandroid.RizeApplication;
import com.rize.rizeandroid.data.PendingSessionData;
import com.rize.rizeandroid.data.PendingSessionHolder;

/**
 * Actividad para mostrar el resumen de una sesión pendiente.
 */
public class SummaryActivity extends AppCompatActivity {

    private boolean alreadySaved = false;

    /**
     * Inicializa la actividad, configurando la UI según si la sesión ya fue guardada o no.
     * 
     * @param savedInstanceState
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_summary);

        alreadySaved = getIntent().getBooleanExtra(CameraActivity.EXTRA_ALREADY_SAVED, false);

        setupToolbar();
        setupBadgeAndButtons();
        setupBottomNav();
        setupBackNavigation();
        populateData();
    }

    /**
     * Configura la barra de herramientas de la actividad.
     */
    private void setupToolbar() {
        findViewById(R.id.btn_back).setOnClickListener(v -> onUserExit());
    }

    /**
     * Configura el badge y los botones de la actividad.
     */
    private void setupBadgeAndButtons() {
        TextView savedBadge = findViewById(R.id.summary_saved_badge);
        TextView btnLeft = findViewById(R.id.btn_go_home);
        TextView btnRight = findViewById(R.id.btn_save_workout);

        if (alreadySaved) {
            savedBadge.setVisibility(View.VISIBLE);
            btnLeft.setText(R.string.summary_done_btn);
            btnLeft.setOnClickListener(v -> navigateHome());
            // Cuando ya esta guardada no hay accion adicional util — colapsamos
            // el segundo boton para no inducir al usuario a re-guardar.
            btnRight.setVisibility(View.GONE);
        } else {
            savedBadge.setVisibility(View.GONE);
            btnLeft.setText(R.string.summary_discard_btn);
            btnLeft.setOnClickListener(v -> confirmDiscard(this::navigateHome));
            btnRight.setText(R.string.summary_save_btn);
            btnRight.setOnClickListener(v -> doSave());
        }
    }

    /**
     * Rellena los datos de la actividad con la información de la sesión pendiente.
     */
    private void populateData() {
        PendingSessionData data = PendingSessionHolder.INSTANCE.peek();
        if (data == null) return;
        SummaryUiBinder.bindSummaryContent(this, data);
    }

    /**
     * Guarda la sesión pendiente.
     */
    private void doSave() {
        PendingSessionData data = PendingSessionHolder.INSTANCE.peek();
        if (data == null) {
            Toast.makeText(this, R.string.session_save_failed_toast, Toast.LENGTH_SHORT).show();
            navigateHome();
            return;
        }
        RizeApplication.get().getSessionRepository().saveSessionAsync(data, id -> {
            runOnUiThread(() -> {
                if (id >= 0) {
                    PendingSessionHolder.INSTANCE.consume();
                    Toast.makeText(this, R.string.session_saved_toast, Toast.LENGTH_SHORT).show();
                    navigateHome();
                } else {
                    Toast.makeText(this, R.string.session_save_failed_toast, Toast.LENGTH_SHORT).show();
                }
            });
            return kotlin.Unit.INSTANCE;
        });
    }

    /**
     * Muestra un diálogo de confirmación antes de descartar la sesión pendiente.
     *
     * @param afterDiscard Acción a ejecutar después de descartar la sesión.
     */
    private void confirmDiscard(Runnable afterDiscard) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.summary_discard_dialog_title)
                .setMessage(R.string.summary_discard_dialog_msg)
                .setNegativeButton(R.string.summary_discard_dialog_cancel, null)
                .setPositiveButton(R.string.summary_discard_dialog_confirm, (d, which) -> {
                    PendingSessionHolder.INSTANCE.clear();
                    afterDiscard.run();
                })
                .show();
    }

    /**
     * Salida sin commit (back del toolbar / sistema). Si la sesion estaba
     * pendiente de decisión, equivale a descartar — pero mostramos el dialogo
     * para que el usuario no pierda la sesion por accidente.
     */
    private void onUserExit() {
        if (alreadySaved) {
            navigateHome();
        } else {
            confirmDiscard(this::navigateHome);
        }
    }

    /**
     * Configura la navegación hacia atrás.
     */
    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onUserExit();
            }
        });
    }

    /**
     * Configura la navegación inferior.
     */
    private void setupBottomNav() {
        findViewById(R.id.nav_home).setOnClickListener(v -> onUserExit());
        findViewById(R.id.nav_fab).setOnClickListener(v -> {
            if (alreadySaved) {
                navigateSelect();
            } else {
                confirmDiscard(this::navigateSelect);
            }
        });
        findViewById(R.id.nav_stats).setOnClickListener(v -> {
            if (alreadySaved) {
                navigateStats();
            } else {
                confirmDiscard(this::navigateStats);
            }
        });
    }

    /**
     * Navega a la actividad principal.
     */
    private void navigateHome() {
        Intent intent = new Intent(this, HomepageActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    /**
     * Navega a la actividad de selección.
     */
    private void navigateSelect() {
        startActivity(new Intent(this, SelectActivity.class));
        finish();
    }

    /**
     * Navega a la actividad de historial de estadísticas.
     */
    private void navigateStats() {
        startActivity(new Intent(this, StatsHistoryActivity.class));
        finish();
    }

    /**
     * Limpia los recursos cuando la actividad es destruida.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            PendingSessionHolder.INSTANCE.clear();
        }
    }
}
