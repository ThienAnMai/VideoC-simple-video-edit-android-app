package com.example.videoc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private static final int STORAGE_PERMISSION_CODE = 1;
    TextView text;
    Button addButton;
    private Context mContext;

    public String path;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        hideSystemUI();

        text = findViewById(R.id.text);
        addButton = findViewById(R.id.addButton);
        addButton.setOnClickListener(view -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if(!Environment.isExternalStorageManager()) {
                    try {
                        Intent intent = new Intent();
                        intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        Uri uri = Uri.fromParts("package", this.getPackageName(), null);
                        intent.setData(uri);
                        storageActivityResultLauncher.launch(intent);
                    } catch (Exception e) {
                        Intent intent = new Intent();
                        intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        storageActivityResultLauncher.launch(intent);
                    }
                }else{
                    showFileChooser();
                }
            }
        });

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void hideSystemUI() {
        View decorView = this.getWindow().getDecorView();
        int uiOptions = decorView.getSystemUiVisibility();
        int newUiOptions = uiOptions;
        newUiOptions |= View.SYSTEM_UI_FLAG_LOW_PROFILE;
        newUiOptions |= View.SYSTEM_UI_FLAG_FULLSCREEN;
        newUiOptions |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        newUiOptions |= View.SYSTEM_UI_FLAG_IMMERSIVE;
        newUiOptions |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decorView.setSystemUiVisibility(newUiOptions);
    }

    private void showSystemUI() {
        View decorView = this.getWindow().getDecorView();
        int uiOptions = decorView.getSystemUiVisibility();
        int newUiOptions = uiOptions;
        newUiOptions &= ~View.SYSTEM_UI_FLAG_LOW_PROFILE;
        newUiOptions &= ~View.SYSTEM_UI_FLAG_FULLSCREEN;
        newUiOptions &= ~View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        newUiOptions &= ~View.SYSTEM_UI_FLAG_IMMERSIVE;
        newUiOptions &= ~View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decorView.setSystemUiVisibility(newUiOptions);
    }
    private void showFileChooser(){
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.setType("video/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            activityResultLauncher.launch(Intent.createChooser(intent, "Select a file"));
        } catch (Exception e){
            Toast.makeText(this, "File manager not exist", Toast.LENGTH_SHORT).show();
        }
    }
    private void startBodyActivity(ArrayList<Uri> uriArrayList) {
        Intent intent = new Intent(this, BodyActivity.class);
        intent.putParcelableArrayListExtra("uriArrayList", uriArrayList);
        //path = UriUtils.getRealPathFromURI(this, uri);
        //intent.putExtra("path", path);
        startActivity(intent);
    }
    ActivityResultLauncher<Intent> activityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    // There are no request codes
                    ArrayList<Uri> uriArrayList = new ArrayList<>();
                    if (result.getData() != null) {
                        if (result.getData().getClipData() != null) {
                            // User selected multiple files
                            for (int i = 0; i < result.getData().getClipData().getItemCount(); i++) {
                                Uri uri = result.getData().getClipData().getItemAt(i).getUri();
                                uriArrayList.add(uri);
                            }
                        } else if (result.getData().getData() != null) {
                            // User selected a single file
                            Uri uri = result.getData().getData();
                            uriArrayList.add(uri);
                        }
                    }
                    startBodyActivity(uriArrayList);

                }
            });

    ActivityResultLauncher<Intent> storageActivityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        if(Environment.isExternalStorageManager()){
                            //Manage External Storage Permissions Granted
                            Toast.makeText(MainActivity.this, "Storage Permissions Granted", Toast.LENGTH_SHORT).show();
                        }else{
                            Toast.makeText(MainActivity.this, "Storage Permissions Denied", Toast.LENGTH_SHORT).show();
                        }
                    }

                }
            });


}