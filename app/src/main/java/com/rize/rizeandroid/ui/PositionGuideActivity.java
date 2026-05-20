package com.rize.rizeandroid.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.rize.rizeandroid.R;

public class PositionGuideActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_position_guide);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }
}
