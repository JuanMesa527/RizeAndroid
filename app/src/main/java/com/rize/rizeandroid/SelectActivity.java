package com.rize.rizeandroid;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class SelectActivity extends AppCompatActivity implements ExerciseAdapter.OnExerciseClickListener {

    private ExerciseAdapter adapter;
    private List<ExerciseAdapter.Exercise> allExercises;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_select);

        setupToolbar();
        setupExerciseList();
        setupSearch();
        setupBottomNav();
    }

    private void setupToolbar() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }

    private void setupExerciseList() {
        allExercises = new ArrayList<>();
        allExercises.add(new ExerciseAdapter.Exercise(
                "Bench Press",
                getString(R.string.select_cat_strength),
                "Chest, Shoulders, Triceps",
                0
        ));
        allExercises.add(new ExerciseAdapter.Exercise(
                "Barbell Squat",
                getString(R.string.select_cat_compound),
                "Quads, Glutes, Lower Back",
                0
        ));
        allExercises.add(new ExerciseAdapter.Exercise(
                "Dumbbell Curl",
                getString(R.string.select_cat_isolation),
                "Biceps Brachii, Forearms",
                0
        ));

        adapter = new ExerciseAdapter(new ArrayList<>(allExercises), this);

        RecyclerView recyclerView = findViewById(R.id.exercise_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    private void setupSearch() {
        EditText searchInput = findViewById(R.id.search_input);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterExercises(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void filterExercises(String query) {
        if (query.isEmpty()) {
            adapter.updateList(new ArrayList<>(allExercises));
            return;
        }

        String lowerQuery = query.toLowerCase();
        List<ExerciseAdapter.Exercise> filtered = new ArrayList<>();
        for (ExerciseAdapter.Exercise ex : allExercises) {
            if (ex.name.toLowerCase().contains(lowerQuery)
                    || ex.muscles.toLowerCase().contains(lowerQuery)
                    || ex.category.toLowerCase().contains(lowerQuery)) {
                filtered.add(ex);
            }
        }
        adapter.updateList(filtered);
    }

    private void setupBottomNav() {
        findViewById(R.id.nav_home).setOnClickListener(v -> {
            Intent intent = new Intent(this, HomepageActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        });
        findViewById(R.id.nav_fab).setOnClickListener(v -> { /* Already on select */ });
        findViewById(R.id.nav_stats).setOnClickListener(v -> { /* Stats placeholder */ });
    }

    @Override
    public void onStartSets(ExerciseAdapter.Exercise exercise) {
        Intent intent = new Intent(this, CameraActivity.class);
        intent.putExtra("exercise_name", exercise.name);
        startActivity(intent);
    }
}
