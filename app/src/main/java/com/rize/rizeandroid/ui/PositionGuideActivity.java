package com.rize.rizeandroid.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.rize.rizeandroid.R;

/**
 * Actividad para mostrar la guía de posición.
 */
public class PositionGuideActivity extends AppCompatActivity {

    /**
     * Método llamado al crear la actividad. Configura la vista y el botón de retroceso.
     * @param savedInstanceState
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_position_guide);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }
}
