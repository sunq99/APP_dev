package com.example.hello;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ExecutorService executorService;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_main);

        executorService = Executors.newSingleThreadExecutor();
        progressBar = findViewById(R.id.progress_bar);
        progressBar.setVisibility(View.GONE);

        Button startButton = findViewById(R.id.button);
        startButton.setOnClickListener(v -> {
            progressBar.setVisibility(View.VISIBLE);
            executorService.execute(() -> {
                boolean success = loadData();
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    if (success) {
                        Intent intent = new Intent(MainActivity.this, MapActivity.class);
                        startActivity(intent);
                    } else {
                        Toast.makeText(MainActivity.this, "데이터 로드에 실패했습니다. 다시 시도하세요.", Toast.LENGTH_SHORT).show();
                    }
                });
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    // 데이터를 로드하는 메서드 (백그라운드 작업)
    private Boolean loadData() {
        try {
            // 예시: 데이터를 읽고 가공합니다.
            // InputStream inputStream = getResources().openRawResource(R.raw.locations);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
