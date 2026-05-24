package com.rize.rizeandroid.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.rize.rizeandroid.R;

/**
 * Actividad que muestra información sobre la aplicación y permite al usuario iniciar la selección de ejercicios.
 */
public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        TextView btnStart = findViewById(R.id.btn_start);
        btnStart.setOnClickListener(v -> {
            startActivity(new Intent(this, SelectActivity.class));
        });
    }
}
