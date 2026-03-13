package com.rize.rizeandroid;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class SummaryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_summary);

        setupToolbar();
        setupButtons();
        setupBottomNav();
    }

    private void setupToolbar() {
        findViewById(R.id.btn_back).setOnClickListener(v -> navigateHome());
    }

    private void setupButtons() {
        findViewById(R.id.btn_go_home).setOnClickListener(v -> navigateHome());
        findViewById(R.id.btn_save_workout).setOnClickListener(v -> navigateHome());
    }

    private void setupBottomNav() {
        findViewById(R.id.nav_home).setOnClickListener(v -> navigateHome());
        findViewById(R.id.nav_fab).setOnClickListener(v -> {
            Intent intent = new Intent(this, SelectActivity.class);
            startActivity(intent);
            finish();
        });
        findViewById(R.id.nav_stats).setOnClickListener(v -> { /* Stats placeholder */ });
    }

    private void navigateHome() {
        Intent intent = new Intent(this, HomepageActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}
