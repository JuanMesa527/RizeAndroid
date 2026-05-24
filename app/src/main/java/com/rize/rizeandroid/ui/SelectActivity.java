package com.rize.rizeandroid.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.rize.rizeandroid.AppPreferences;
import com.rize.rizeandroid.R;
import com.rize.rizeandroid.biomechanics.ExerciseType;

import java.util.ArrayList;
import java.util.List;

/**
 * Actividad para seleccionar ejercicios.
 */
public class SelectActivity extends AppCompatActivity implements ExerciseAdapter.OnExerciseClickListener {

    private ExerciseAdapter adapter;
    private List<ExerciseAdapter.Exercise> allExercises;

    /**
     * Inicializa la actividad, configurando la interfaz y los listeners.
     * 
     * @param savedInstanceState
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_select);

        setupToolbar();
        setupAutoSaveToggle();
        setupCameraSelector();
        setupExerciseList();
        setupPositionGuideButton();
        setupBottomNav();
    }

    /**
     * Configura el interruptor de guardado automático.
     */
    private void setupAutoSaveToggle() {
        SwitchMaterial toggle = findViewById(R.id.auto_save_switch);
        toggle.setChecked(AppPreferences.isAutoSaveEnabled(this));
        toggle.setOnCheckedChangeListener((btn, checked) ->
                AppPreferences.setAutoSaveEnabled(this, checked));
    }

    /**
     * Configura el selector de cámara.
     */
    private void setupCameraSelector() {
        LinearLayout card = findViewById(R.id.camera_selector_card);
        TextView desc     = findViewById(R.id.camera_selector_desc);
        ImageView icon    = findViewById(R.id.camera_selector_icon);

        updateCameraLabel(desc, AppPreferences.isFrontCameraPreferred(this));

        card.setOnClickListener(v -> {
            boolean nowFront = !AppPreferences.isFrontCameraPreferred(this);
            AppPreferences.setFrontCameraPreferred(this, nowFront);
            updateCameraLabel(desc, nowFront);
        });
    }

    /**
     * Actualiza la etiqueta del selector de cámara según la preferencia actual.
     * 
     * @param desc
     * @param front
     */
    private void updateCameraLabel(TextView desc, boolean front) {
        desc.setText(front
                ? getString(R.string.select_camera_front)
                : getString(R.string.select_camera_back));
    }

    /**
     * Configura la barra de herramientas.
     */
    private void setupToolbar() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }

    /**
     * Configura la lista de ejercicios.
     */
    private void setupExerciseList() {
        allExercises = new ArrayList<>();
        allExercises.add(new ExerciseAdapter.Exercise(
                getString(R.string.exercise_bench_press),
                getString(R.string.select_cat_strength),
                getString(R.string.muscles_bench_press),
                0,
                ExerciseType.BENCH_PRESS
        ));
        allExercises.add(new ExerciseAdapter.Exercise(
                getString(R.string.exercise_barbell_squat),
                getString(R.string.select_cat_compound),
                getString(R.string.muscles_barbell_squat),
                0,
                ExerciseType.SQUAT
        ));
        allExercises.add(new ExerciseAdapter.Exercise(
                getString(R.string.exercise_dumbbell_curl),
                getString(R.string.select_cat_isolation),
                getString(R.string.muscles_dumbbell_curl),
                0,
                ExerciseType.CURL
        ));

        adapter = new ExerciseAdapter(new ArrayList<>(allExercises), this);

        RecyclerView recyclerView = findViewById(R.id.exercise_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    /**
     * Configura el botón de guía de posición.
     */
    private void setupPositionGuideButton() {
        findViewById(R.id.btn_position_guide).setOnClickListener(v ->
                startActivity(new Intent(this, PositionGuideActivity.class)));
    }

    /**
     * Configura la navegación inferior.
     */
    private void setupBottomNav() {
        findViewById(R.id.nav_home).setOnClickListener(v -> {
            Intent intent = new Intent(this, HomepageActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        });
        findViewById(R.id.nav_fab).setOnClickListener(v -> { /* Already on select */ });
        findViewById(R.id.nav_stats).setOnClickListener(v -> {
            startActivity(new Intent(this, StatsHistoryActivity.class));
        });
    }

    /**
     * Maneja el clic en un ejercicio, iniciando la actividad de cámara con los detalles del ejercicio seleccionado.
     * @param exercise
     */
    @Override
    public void onStartSets(ExerciseAdapter.Exercise exercise) {
        Intent intent = new Intent(this, CameraActivity.class);
        intent.putExtra("exercise_name", exercise.name);
        intent.putExtra("exercise_type", exercise.exerciseType.name());
        intent.putExtra(CameraActivity.EXTRA_AUTO_SAVE, AppPreferences.isAutoSaveEnabled(this));
        intent.putExtra(CameraActivity.EXTRA_FRONT_CAMERA, AppPreferences.isFrontCameraPreferred(this));
        startActivity(intent);
    }
}
