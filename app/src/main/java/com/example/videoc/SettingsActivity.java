package com.example.videoc;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;

public class SettingsActivity extends AppCompatActivity {

    private TextView currentPathTextView;
    private Button browseButton;
    private SettingsManager settingsManager;

    // Launcher for the directory picker intent
    private final ActivityResultLauncher<Intent> directoryPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        // Persist read/write permissions for this directory
                        getContentResolver().takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                        // For simplicity, we'll convert the URI to a file path.
                        // Note: This is a simplified conversion. A more robust solution would use DocumentFile.
                        File selectedDir = new File(UriUtils.getRealPathFromURI(this, uri));
                        settingsManager.setOutputPath(selectedDir.getAbsolutePath());
                        updatePathDisplay();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        settingsManager = new SettingsManager(this);
        currentPathTextView = findViewById(R.id.currentPathTextView);
        browseButton = findViewById(R.id.browseButton);

        updatePathDisplay();

        browseButton.setOnClickListener(v -> openDirectoryPicker());
    }

    private void updatePathDisplay() {
        currentPathTextView.setText(settingsManager.getOutputPath());
    }

    private void openDirectoryPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        directoryPickerLauncher.launch(intent);
    }
}