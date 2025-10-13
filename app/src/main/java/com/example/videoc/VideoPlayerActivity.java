package com.example.videoc;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import com.example.videoc.databinding.ActivityVideoPlayerBinding;

import java.util.ArrayList;

@UnstableApi public class VideoPlayerActivity extends AppCompatActivity {

    ExoPlayer exoPlayer;
    MediaItem mediaItem;
    ActivityVideoPlayerBinding binding;
    private long startTime;
    long currentTime;
    private GestureDetector gestureDetector;
    boolean isScrolling = false;
    Uri uri;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityVideoPlayerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        hideSystemUI();
        gestureDetector = new GestureDetector(this, new VideoPlayerActivity.MyGestureListener());
        currentTime = getIntent().getLongExtra("currentTime",0);
        setUpPlayer();

    }


    @Override
    protected void onDestroy() {
        exoPlayer.stop();
        exoPlayer.setPlayWhenReady(false);
        exoPlayer.release();
        exoPlayer = null;
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        exoPlayer.setPlayWhenReady(false);
        exoPlayer.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        exoPlayer.setPlayWhenReady(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    private void setUpPlayer() {
        ArrayList<Uri> uriArrayList = new ArrayList<Uri>();
        uriArrayList = getIntent().getParcelableArrayListExtra("uriArrayList");

        //uri = getIntent().getParcelableExtra("uri");
        if (uriArrayList != null) {
            DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this);
            MediaSource.Factory factory = new ProgressiveMediaSource.Factory(dataSourceFactory);

            ConcatenatingMediaSource2.Builder concatenatingMediaSourceBuilder = new ConcatenatingMediaSource2.Builder();

            for(Uri uri : uriArrayList){
                concatenatingMediaSourceBuilder.add(factory.createMediaSource(MediaItem.fromUri(uri)),0);
            }

            exoPlayer = new ExoPlayer.Builder(VideoPlayerActivity.this).build();
            //mediaItem = MediaItem.fromUri(uri);
            binding.videoLoaderController.setPlayer(exoPlayer);
            binding.videoLoader.setPlayer(exoPlayer);
            binding.videoLoader.setKeepScreenOn(true);
            //exoPlayer.setMediaItem(mediaItem);
            exoPlayer.setMediaSource(concatenatingMediaSourceBuilder.build());
            exoPlayer.prepare();
            exoPlayer.setPlayWhenReady(true);
            exoPlayer.addListener(new Player.Listener() {
                @Override
                public void onTimelineChanged(Timeline timeline, int reason) {
                    Player.Listener.super.onTimelineChanged(timeline, reason);
                    exoPlayer.seekTo(currentTime);
                }
            });
        }
        binding.videoLoader.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                toggleLayoutVisibility();
                return false;
            }
        });

        binding.editCancelBtn.setOnClickListener(view -> {
            onBackPressed();
            long currentTime = exoPlayer.getCurrentPosition();
            Intent resultIntent = new Intent();
            resultIntent.putExtra("currentTime", currentTime);
            setResult(RESULT_OK, resultIntent);
            finish();
        });
    }

    private void toggleLayoutVisibility() {
        if(!isScrolling){
            if (binding.videoLoaderController.isShown()) {
                binding.videoLoaderController.hide();
            } else {
                binding.videoLoaderController.show();
            }
        }
    }
    private class MyGestureListener extends GestureDetector.SimpleOnGestureListener{
        @Override
        public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
            isScrolling = true;
            long elapsedTime = SystemClock.uptimeMillis() - startTime;
            binding.videoLoaderController.show();
            float velocityX = distanceX / elapsedTime;
            if(velocityX > 0) {
                velocityX = 0 - velocityX;
            }
            if(velocityX > -99999) {
                currentTime = exoPlayer.getCurrentPosition();
                currentTime = (long) (exoPlayer.getCurrentPosition() + (distanceX * velocityX * 2));
                exoPlayer.seekTo(currentTime);
                startTime = SystemClock.uptimeMillis();
            }

            return super.onScroll(e1, e2, distanceX, distanceY);
        }

        @Override
        public boolean onDown(@NonNull MotionEvent e) {
            isScrolling = false;
            return super.onDown(e);
        }
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

}