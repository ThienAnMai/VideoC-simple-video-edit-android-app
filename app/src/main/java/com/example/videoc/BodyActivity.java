package com.example.videoc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ClippingMediaSource;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import com.example.videoc.databinding.ActivityBodyBinding;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@UnstableApi public class BodyActivity extends AppCompatActivity{
    //-------------initiate---------------
    ExoPlayer exoPlayer;
    MediaItem mediaItem;
    Uri uri;
    ArrayList<Uri> uriArrayList;
    Context context;
    ActivityBodyBinding binding;
    private GestureDetector gestureDetector;
    private long startTime;
    int currentVolume;
    List<VideoSegment> videoSegmentList;
    ConcatenatingMediaSource2 mediaSource;
    VideoSegment selectedSegment;
    private long currentTime = 0;
    final int[] currentWidth = {0};
    private Handler handler;
    List<Bitmap> frameList;
    // ----------Body Activity-------------

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        context = this;
        binding = ActivityBodyBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        hideSystemUI();

        iniSegment();
        setUpPlayer();


        handler = new Handler(Looper.getMainLooper());

        gestureDetector = new GestureDetector(this, new MyGestureListener());
        setUpThumbnail();
        setUpBtns();

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding.videoLoader.removeCallbacks(null);
        exoPlayer.release();
        exoPlayer = null;
    }

    @Override
    protected void onStop() {
        super.onStop();
        exoPlayer.setPlayWhenReady(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        exoPlayer.setPlayWhenReady(true);
    }

    //--------------gesture-------------------------
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    private class MyGestureListener extends GestureDetector.SimpleOnGestureListener{
        @Override
        public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
            long elapsedTime = SystemClock.uptimeMillis() - startTime;
                float velocityX = distanceX / elapsedTime;
            if(velocityX > 0) {
                velocityX = 0 - velocityX;
            }
            if(velocityX > -99999) {
                currentTime = exoPlayer.getCurrentPosition();
                currentTime = (long) (exoPlayer.getCurrentPosition() + (distanceX * velocityX * 2));
                exoPlayer.seekTo(currentTime);
                binding.videoCurrentTimeTv.setText(formatDuration(currentTime));

                //binding.videoEditScrollView.scrollTo((int) (currentTime*(binding.videoFrameLayout.getWidth())/ exoPlayer.getDuration()), 0);
                setUpSelectedSegment();
                binding.videoEditScrollView.scrollTo((int) (((currentTime-selectedSegment.getStartTime())*(selectedSegment.getWidth())/ selectedSegment.getDuration())+selectedSegment.getStartPosition()), 0);


                startTime = SystemClock.uptimeMillis();
            }

            return super.onScroll(e1, e2, distanceX, distanceY);
        }

    }


    // ------general functions----------
    //---------Player--------------
    private void setUpPlayer(){

        //uri = getIntent().getParcelableExtra("uri");

        if(videoSegmentList != null){

            //DefaultLoadControl loadControl = new DefaultLoadControl.Builder().setBufferDurationsMs(10000, 60000, 1500, 1500).build();

            exoPlayer = new ExoPlayer.Builder(BodyActivity.this).build();

            //mediaItem = MediaItem.fromUri(uri);
            binding.videoLoader.setPlayer(exoPlayer);
            binding.videoLoader.setKeepScreenOn(true);
            //exoPlayer.setMediaItem(mediaItem);
            //exoPlayer.setMediaSource(mediaSource);
            setUpPlayerMedia();

            //exoPlayer.prepare();
            exoPlayer.setPlayWhenReady(false);


            exoPlayer.addListener(new Player.Listener() {
                @Override
                public void onTimelineChanged(Timeline timeline, int reason) {
                    Player.Listener.super.onTimelineChanged(timeline, reason);
                    binding.videoStartTimeTv.setText("00:00:00.000");
                    binding.videoEndTimeTv.setText(formatDuration(exoPlayer.getDuration()));

                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    Player.Listener.super.onIsPlayingChanged(isPlaying);
                    if (isPlaying) {
                        binding.videoLoader.postDelayed(this::getCurrentPlayerPosition, 100);
                    }

                }
                private void getCurrentPlayerPosition() {
                    if (exoPlayer!=null && exoPlayer.isPlaying()) {
                        currentTime = exoPlayer.getCurrentPosition();

                        //binding.videoEditScrollView.scrollTo((int) (exoPlayer.getCurrentPosition()*(binding.videoFrameLayout.getWidth())/ exoPlayer.getDuration()), 0);
                        setUpSelectedSegment();
                        binding.videoEditScrollView.scrollTo((int) (((currentTime-selectedSegment.getStartTime())*(selectedSegment.getWidth())/ selectedSegment.getDuration())+selectedSegment.getStartPosition()), 0);

                        binding.videoCurrentTimeTv.setText(formatDuration(currentTime));
                        binding.videoLoader.postDelayed(this::getCurrentPlayerPosition, 100);
                    }
                }
            });
        }


    }
    private void setUpPlayerMedia(){
        long startTime = System.currentTimeMillis();
        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context);
        MediaSource.Factory factory = new ProgressiveMediaSource.Factory(dataSourceFactory);

        if(videoSegmentList != null) {

            ConcatenatingMediaSource2.Builder concatenatingMediaSourceBuilder = new ConcatenatingMediaSource2.Builder();
            for (VideoSegment videoSegment : videoSegmentList) {
                MediaSource mediaSource1 = new ClippingMediaSource(factory.createMediaSource(MediaItem.fromUri(videoSegment.getUri())), videoSegment.getClippingStart()*1000,videoSegment.getClippingEnd()*1000);
                concatenatingMediaSourceBuilder.add(mediaSource1, 0);
                //concatenatingMediaSourceBuilder.add(factory.createMediaSource(MediaItem.fromUri(videoSegment.getUri())), 0);

            }

            mediaSource = concatenatingMediaSourceBuilder.build();

            exoPlayer.setMediaSource(mediaSource);

            exoPlayer.prepare();


            exoPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    Player.Listener.super.onPlaybackStateChanged(playbackState);
                    if(playbackState == Player.STATE_READY){
                        exoPlayer.seekTo(currentTime);
                        long endTime = System.currentTimeMillis();
                        long elapsedTime = endTime - startTime;

                        System.out.println("Elapsed time: " + elapsedTime + " milliseconds");
                        exoPlayer.removeListener(this);
                    }
                }
            });

        }
    }
    private void playVideo(){
        binding.videoPlayBtn.setVisibility(View.INVISIBLE);
        binding.videoPauseBtn.setVisibility(View.VISIBLE);
        exoPlayer.play();
    }

    private void pauseVideo(){
        binding.videoPlayBtn.setVisibility(View.VISIBLE);
        binding.videoPauseBtn.setVisibility(View.INVISIBLE);
        exoPlayer.pause();
    }
    //-------thumbnail------------

    @SuppressLint("ClickableViewAccessibility")
    private void setUpThumbnail(){
        binding.videoEditScrollView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                binding.videoEditScrollView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                int padding = binding.videoEditScrollView.getWidth()/2;
                binding.videoEditScrollView.setPadding(padding,0,padding,0);
            }
        });


        binding.videoEditScrollView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (motionEvent.getAction() == MotionEvent.ACTION_MOVE) {
                    //currentTime = exoPlayer.getDuration()*binding.videoEditScrollView.getScrollX()/binding.videoFrameLayout.getWidth();
                    setUpSelectedSegment();
                    currentTime = (selectedSegment.getDuration()*(binding.videoEditScrollView.getScrollX()-selectedSegment.getStartPosition())/selectedSegment.getWidth())+selectedSegment.getStartTime();


                    exoPlayer.seekTo(currentTime);
                    binding.videoCurrentTimeTv.setText(formatDuration(currentTime));

                }

                return false;
            }
        });



        setUpThumbnailImage();

        //exoPlayer.play();

    }

    private void setUpThumbnailImage(){

        binding.videoFrameLayout.removeAllViews();

        //final Semaphore semaphore = new Semaphore(1);

        new Thread(new Runnable() {
            @Override
            public void run() {
                currentWidth[0] = 0;

                for(VideoSegment videoSegment: videoSegmentList) {

                    setUpThumbnailChild(videoSegment, videoSegment.getFrameInterval());

                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                            //scroll to current position after cut segment
                            setUpSelectedSegment();
                            binding.videoEditScrollView.scrollTo((int) (((currentTime-selectedSegment.getStartTime())*(selectedSegment.getWidth())/ selectedSegment.getDuration())+selectedSegment.getStartPosition()), 0);

                    }
                });
            }
        }).start();
    }

    private void setUpThumbnailChild(VideoSegment videoSegment, long Interval){

        final Semaphore semaphore = new Semaphore(1);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(context, videoSegment.uri);


        long duration = videoSegment.getDuration();
        long frameInterval = Interval;

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        LinearLayout layout = new LinearLayout(context);
        layout.setLayoutParams(params);

        handler.post(new Runnable() {
            @Override
            public void run() {
                binding.videoFrameLayout.addView(layout);

                binding.videoFrameLayout.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        try {
                            semaphore.acquire(); // Acquire the permit

                            // Code for the listener inside nested loop
                            videoSegment.setStartPosition(currentWidth[0]);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        } finally {
                            semaphore.release(); // Release the permit in a finally block
                        }
                        // Remove the listener to avoid multiple calls
                        binding.videoFrameLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                });

            }
        });


        for (long i = videoSegment.getStartTime(); i < videoSegment.getEndTime(); i += frameInterval) {
            Bitmap frame = retriever.getFrameAtTime(i * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            frame = Bitmap.createScaledBitmap(frame, frame.getWidth() / 4, frame.getHeight() / 4, false);
            if (frame != null) {
                Bitmap finalFrame = frame;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        View view = LayoutInflater.from(context).inflate(R.layout.item_video_edit, binding.videoFrameLayout, false);
                        ImageView imageView = view.findViewById(R.id.thumbnailImageView);
                        imageView.setImageBitmap(finalFrame);

                        layout.addView(view);


                        binding.videoFrameLayout.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                try {
                                    semaphore.acquire(); // Acquire the permit

                                    // Code for the listener inside nested loop
                                    videoSegment.setWidth(binding.videoFrameLayout.getWidth()-videoSegment.getStartPosition());

                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                } finally {
                                    semaphore.release(); // Release the permit in a finally block
                                }
                                // Remove the listener to avoid multiple calls
                                binding.videoFrameLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            }
                        });


                    }
                });

            }


        }


        handler.post((new Runnable() {
            @Override
            public void run() {
                binding.videoFrameLayout.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {

                        try {
                            semaphore.acquire(); // Acquire the permit

                            // Code for the listener outside nested loop
                            currentWidth[0] = binding.videoFrameLayout.getWidth();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        } finally {
                            semaphore.release(); // Release the permit in a finally block
                        }
                        // Remove the listener to avoid multiple calls
                        binding.videoFrameLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                });


                if(videoSegment.getStartPosition()!=0){
                    setSeparateLine(videoSegment.getStartPosition());
                }

                try {
                    retriever.release();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }));


    }


    //----------segment--------------------
    private void iniSegment(){
        uriArrayList = new ArrayList<>();
        uriArrayList = getIntent().getParcelableArrayListExtra("uriArrayList");

        videoSegmentList = new ArrayList<VideoSegment>();
        //videoSegmentList.clear();
        long startTime = 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        for(Uri uri:uriArrayList){
            retriever.setDataSource(context, uri);
            long duration = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));

            videoSegmentList.add(new VideoSegment(uri, startTime, duration));
            startTime += duration;
        }
        try {
            retriever.release();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    private void setUpSelectedSegment(){
        for(VideoSegment videoSegment : videoSegmentList){
            if(currentTime <= videoSegment.endTime && currentTime >= videoSegment.startTime){
                //int segmentWidth = (int) (videoSegment.duration*binding.videoFrameLayout.getWidth()/exoPlayer.getDuration());
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(videoSegment.getWidth(),binding.videoFrameLayout.getHeight());
                //params.setMarginStart((int) (videoSegment.startTime*binding.videoFrameLayout.getWidth()/exoPlayer.getDuration()));
                params.setMarginStart(videoSegment.getStartPosition());
                binding.selectedTimeLineView.setLayoutParams(params);
                binding.selectedTimeLineView.setVisibility(View.VISIBLE);
                selectedSegment = videoSegment;
            }

        }
    }

    private void setSeparateLine(int linePosition){
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(10, ViewGroup.LayoutParams.MATCH_PARENT);
        params.setMarginStart(linePosition-5);
        View view = new View(this);
        view.setLayoutParams(params);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view.setBackgroundColor(getResources().getColor(R.color.black,null));
        }
        binding.videoEditChildFrameLayout.addView(view);
        binding.selectedTimeLineView.bringToFront();
    }

    private void cutSegment(VideoSegment videoSegment, long cutTime){
        int cutPos = (int) (cutTime*videoSegment.getWidth()/videoSegment.getDuration());

        videoSegmentList.add( videoSegmentList.indexOf(videoSegment)+1,new VideoSegment(videoSegment.getUri(),videoSegment.getStartTime()+cutTime,videoSegment.getDuration()-cutTime, cutTime));
        videoSegment.setDuration(cutTime);
        videoSegment.setClippingEnd(cutTime);

        setUpPlayerMedia();
        setUpThumbnailImage();
        setUpSelectedSegment();
        exoPlayer.pause();
        //binding.videoEditScrollView.scrollTo((int) (((currentTime-selectedSegment.getStartTime())*(selectedSegment.getWidth())/ selectedSegment.getDuration())+selectedSegment.getStartPosition()), 0);
    }

    private void deleteSegment(VideoSegment selectedSegment) {
        videoSegmentList.remove(selectedSegment);
        setUpPlayerMedia();
        setUpThumbnail();
        setUpSelectedSegment();
    }

    //------------Buttons--------------
    private void setUpBtns(){
        exoPlayer.addListener(new Player.Listener(){
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                Player.Listener.super.onIsPlayingChanged(isPlaying);
                if(isPlaying){
                    binding.videoPlayBtn.setVisibility(View.INVISIBLE);
                    binding.videoPauseBtn.setVisibility(View.VISIBLE);
                }else{
                    binding.videoPlayBtn.setVisibility(View.VISIBLE);
                    binding.videoPauseBtn.setVisibility(View.INVISIBLE);
                }
            }
        });

        binding.videoPlayBtn.setOnClickListener(view -> playVideo());
        binding.videoPauseBtn.setOnClickListener(view -> pauseVideo());


        binding.editCancelBtn.setOnClickListener(view -> onBackPressed());
        binding.editCompleteBtn.setOnClickListener(view -> onBackPressed());

        binding.videoFullscreenBtn.setOnClickListener(view -> {
            startVideoPlayerActivity(getIntent().getParcelableExtra("uri"));
        });

        binding.editSplitBtn.setOnClickListener(view -> cutSegment(selectedSegment,currentTime-selectedSegment.getStartTime()));

        binding.editDeleteBtn.setOnClickListener(view -> deleteSegment(selectedSegment));

    }




    //-------------format------------------
    public static String formatDuration(long durationMs) {
        if (durationMs == C.TIME_UNSET) {
            return "N/A"; // Or handle the case when duration is not available
        }

        long hours = TimeUnit.MILLISECONDS.toHours(durationMs);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60;
        long milliseconds = durationMs % 1000;

        // Format the duration as hh:mm:ss.ms
        return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, milliseconds);
    }

    //--------------new activity---------------------
    //change to new segmentList+++++++++++++++++++++++++++++++++++++++++++++++
    private void startVideoPlayerActivity(Uri uri) {
        Intent intent = new Intent(this, VideoPlayerActivity.class);
        intent.putParcelableArrayListExtra("uriArrayList", uriArrayList);
        intent.putExtra("uri", uri);
        intent.putExtra("currentTime", currentTime);
        fullScreenActivityResultLauncher.launch(intent);
    }
    //--------Activity result--------

    ActivityResultLauncher<Intent> fullScreenActivityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        currentTime = result.getData().getLongExtra("currentTime",0);
                        exoPlayer.seekTo(currentTime);

                    }
                }
            });

    //--------system UI--------------
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