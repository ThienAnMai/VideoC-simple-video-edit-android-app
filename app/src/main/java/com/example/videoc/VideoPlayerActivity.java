package com.example.videoc;

import android.content.Context;
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
import androidx.media3.exoplayer.source.ClippingMediaSource;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import com.example.videoc.databinding.ActivityVideoPlayerBinding;
import android.widget.Toast;
import java.util.ArrayList;

@UnstableApi public class VideoPlayerActivity extends AppCompatActivity {

    Context context;
    ExoPlayer exoPlayer;
    ActivityVideoPlayerBinding binding;
    long currentTime;
    private GestureDetector gestureDetector;
    private MyGestureListener myGestureListener;
    ArrayList<VideoSegment> segmentList;
    Uri uri;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        context = this;
        binding = ActivityVideoPlayerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        hideSystemUI();

        myGestureListener = new MyGestureListener();
        gestureDetector = new GestureDetector(this, myGestureListener);
        segmentList = getIntent().getParcelableArrayListExtra("segmentList");
        currentTime = getIntent().getLongExtra("currentTime", 0);

        if (segmentList == null || segmentList.isEmpty()) {
            // Handle the error case where no segments were passed
            Toast.makeText(this, "Error: No video data found.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

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

        //Check if the user has lifted their finger ---
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            // If so, call our custom onGestureEnd method.
            myGestureListener.onScrollEnd();
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    private void setUpPlayer() {
        exoPlayer = new ExoPlayer.Builder(this).build();
        binding.videoLoader.setPlayer(exoPlayer);
        binding.videoLoaderController.setPlayer(exoPlayer);
        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context);
        MediaSource.Factory factory = new ProgressiveMediaSource.Factory(dataSourceFactory);

        if(segmentList != null) {

            ConcatenatingMediaSource2.Builder concatenatingMediaSourceBuilder = new ConcatenatingMediaSource2.Builder();
            for (VideoSegment videoSegment : segmentList) {
                MediaSource mediaSource1 = new ClippingMediaSource(factory.createMediaSource(MediaItem.fromUri(videoSegment.getUri())),
                        videoSegment.getClippingStart()*1000,
                        videoSegment.getClippingEnd()*1000);
                concatenatingMediaSourceBuilder.add(mediaSource1);

            }
            exoPlayer.setMediaSource(concatenatingMediaSourceBuilder.build());
            exoPlayer.prepare();
            exoPlayer.seekTo(currentTime); // Start at the correct time
            exoPlayer.play();
        }

//        binding.videoLoader.setOnTouchListener(new View.OnTouchListener() {
//            @Override
//            public boolean onTouch(View view, MotionEvent motionEvent) {
//                toggleLayoutVisibility();
//                return false;
//            }
//        });

        binding.editCancelBtn.setOnClickListener(view -> {
//            onBackPressed();
            long currentTime = exoPlayer.getCurrentPosition();
            Intent resultIntent = new Intent();
            resultIntent.putExtra("currentTime", currentTime);
            setResult(RESULT_OK, resultIntent);
            finish();
        });
    }

    private void toggleLayoutVisibility() {
        if (binding.videoLoaderController.isShown()) {
            binding.videoLoaderController.hide();
        } else {
                binding.videoLoaderController.show();
        }
    }
    private class MyGestureListener extends GestureDetector.SimpleOnGestureListener{

        long startTime;
        boolean isScrolling = false;
        @Override
        public boolean onDown(@NonNull MotionEvent e) {
            isScrolling = false;
            startTime = SystemClock.uptimeMillis();
            exoPlayer.pause();
            return true;
        }

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
                currentTime = (long) (exoPlayer.getCurrentPosition() + (distanceX * velocityX * 2));
                if (Math.abs(exoPlayer.getCurrentPosition() - currentTime) > 10) { // 10ms threshold
                    exoPlayer.seekTo(currentTime);
                }
                startTime = SystemClock.uptimeMillis();
            }

            return true;
        }

        public void onScrollEnd() {
            if (isScrolling) {
                // --- 3. FINAL SEEK ON LIFT ---
                // Perform one final, precise seek to the exact end position.
                exoPlayer.seekTo(currentTime);
                exoPlayer.play();
                // Hide the loader and reset the flag
                binding.videoLoaderController.hide();
                isScrolling = false;
            }
        }


        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            // Handle showing/hiding controls on a simple tap
            if(!this.isScrolling) {
                toggleLayoutVisibility();
            }
            return true;
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