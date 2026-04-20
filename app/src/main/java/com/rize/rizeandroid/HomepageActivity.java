package com.rize.rizeandroid;

import android.content.Intent;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class HomepageActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_homepage);

        setupNavigation();
        setupBottomNav();
    }

    private void setupNavigation() {
        TextView btnGetStarted = findViewById(R.id.btn_get_started);
        TextView btnViewAll = findViewById(R.id.btn_view_all);

        btnGetStarted.setOnClickListener(v -> navigateToSelect());
        btnViewAll.setOnClickListener(v -> navigateToSelect());
    }

    private void setupBottomNav() {
        ImageView homeIcon = findViewById(R.id.nav_home_icon);
        TextView homeLabel = findViewById(R.id.nav_home_label);
        homeIcon.setColorFilter(ContextCompat.getColor(this, R.color.toasted_almond), PorterDuff.Mode.SRC_IN);
        homeLabel.setTextColor(ContextCompat.getColor(this, R.color.toasted_almond));

        findViewById(R.id.nav_home).setOnClickListener(v -> { /* Already on home */ });
        findViewById(R.id.nav_fab).setOnClickListener(v -> navigateToSelect());
        findViewById(R.id.nav_stats).setOnClickListener(v -> {
            startActivity(new Intent(this, VideoAnalysisActivity.class));
        });
    }

    private void navigateToSelect() {
        startActivity(new Intent(this, SelectActivity.class));
    }
}
