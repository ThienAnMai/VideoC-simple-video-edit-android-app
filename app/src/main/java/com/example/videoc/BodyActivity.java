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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.collection.LruCache;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.DatabaseProvider;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ClippingMediaSource;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback;
import com.arthenica.ffmpegkit.ReturnCode;
import com.example.videoc.databinding.ActivityBodyBinding;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@UnstableApi
public class BodyActivity extends AppCompatActivity implements SegmentAdapter.ThumbnailGenerator {
    /**
    *----------------------------------------------------------------------------------------------------
    *-----------------------------------INITIATE-Variables-----------------------------------------------
    *----------------------------------------------------------------------------------------------------
    */
    ExoPlayer exoPlayer;
    Context context;
    ActivityBodyBinding binding;
    private GestureDetector gestureDetector;
    private MyGestureListener myGestureListener;
    private List<VideoSegment> videoSegmentList;
    int currentVolume;
    ConcatenatingMediaSource2 mediaSource;
    private SegmentAdapter segmentAdapter;
    VideoSegment selectedSegment;
    private SimpleCache exoPlayerCache;
    private CacheDataSource.Factory cacheDataSourceFactory;
    private long currentTime = 0;

    private double pixelsPerSecond = 50.0; // Initial zoom level. e.g., 50 pixels represents 1 second.

    // For running background tasks like caching and thumbnail generation
    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(2); // 2 threads for parallel tasks
    // For posting results back to the main UI thread
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    private int timelineHeightPx = 1;
    private final ThreadLocal<MediaMetadataRetriever> retrieverThreadLocal =
            new ThreadLocal<MediaMetadataRetriever>() {
                @Override
                protected MediaMetadataRetriever initialValue() {
                    // This method is called once per thread to create the instance
                    Log.d("ThumbnailGen", "Creating new MediaMetadataRetriever for thread: " + Thread.currentThread().getName());
                    return new MediaMetadataRetriever();
                }
            };

    /**
    *----------------------------------------------------------------------------------------------------
    *-----------------------------------INITIATE-functions-----------------------------------------------
    *----------------------------------------------------------------------------------------------------
    */

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        context = this;
        binding = ActivityBodyBinding.inflate(getLayoutInflater());

        // Show the loading screen before starting any tasks
        binding.loadingContainer.setVisibility(View.VISIBLE);

        setContentView(binding.getRoot());
        hideSystemUI();

        iniSegment();
        setUpPlayer();

        myGestureListener = new MyGestureListener();
        gestureDetector = new GestureDetector(this, myGestureListener);


        updateSeparateLine();
        setUpBtns();

        setUpThumbnail();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d("Cleanup", "onDestroy called.");
        binding.videoLoader.removeCallbacks(null);
        // Release resources that might still be held if cleanupAndFinish wasn't called.
        // This acts as a final safety net.
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdownNow();
        }
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        if (exoPlayerCache != null) {
            exoPlayerCache.release();
            exoPlayerCache = null;
        }
    }

    @Override
    public Bitmap generateThumbnail(Uri uri, long timestampMs, int width, int height) {
        MediaMetadataRetriever retriever = retrieverThreadLocal.get();
        try {
            // The retriever is already initialized.
            // Note: setDataSource is thread-safe as long as you don't use the same retriever
            // instance on multiple threads at the exact same time without synchronization.
            // For this use case with a thread pool of 2, it's generally safe.
            retriever.setDataSource(this, uri);
            // The timestamp needs to be in microseconds for getFrameAtTime
            return retriever.getScaledFrameAtTime(timestampMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, width, height);
        } catch (Exception e) {
            Log.e("ThumbnailGen", "Failed to get frame for " + uri + " at " + timestampMs, e);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                Log.e("ThumbnailGen", "Failed to release retriever", e);
            }
        }
        // We no longer release the retriever here. It will be released in onDestroy.
    }
    /**
     *----------------------------------------------------------------------------------------------------
     *-----------------------------------GESTURE----------------------------------------------------------
     *----------------------------------------------------------------------------------------------------
     */

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        //Check if the user has lifted their finger ---
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            // If so, call our custom onGestureEnd method.
            myGestureListener.onScrollEnd();
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    private class MyGestureListener extends GestureDetector.SimpleOnGestureListener {

        long startTime;
        boolean isScrolling = false;
        boolean isPlaying = false;
        @Override
        public boolean onDown(@NonNull MotionEvent e) {
            isScrolling = false;
            startTime = SystemClock.uptimeMillis();

            if (exoPlayer != null) {
                isPlaying = exoPlayer.isPlaying();
                exoPlayer.pause();
            }
            return true;
        }

        @Override
        public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
            isScrolling = true;
            long elapsedTime = SystemClock.uptimeMillis() - startTime;
            if(elapsedTime == 0) return true; // Skip scroll even if it's not scrolling
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
                binding.videoCurrentTimeTv.setText(formatDuration(currentTime));
                int scrollX = (int) (currentTime / 1000.0 * pixelsPerSecond);
                binding.timelineScrollView.scrollTo(scrollX, 0);
                setUpSelectedSegment();
            }

            return true;
        }

        @Override
        public boolean onFling(@NonNull MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
            onScrollEnd();
            return true;
        }

        public void onScrollEnd() {
            if (isScrolling) {
                // Perform one final, precise seek to the exact end position.
                exoPlayer.seekTo(currentTime);
                if (isPlaying) exoPlayer.play();
                isScrolling = false;
            }
        }
    }



    /**
     *----------------------------------------------------------------------------------------------------
     *-----------------------------------General-functions------------------------------------------------
     *----------------------------------------------------------------------------------------------------
     */
    //-----------------------------------Player-----------------------------------------------------------
    private void setUpPlayer(){


        if(videoSegmentList != null){
            // 1. Create a single cache instance.
            if (exoPlayerCache == null) {
                File cacheDir = new File(getCacheDir(), "exoPlayerCache");
                DatabaseProvider databaseProvider = new StandaloneDatabaseProvider(this);
                exoPlayerCache = new SimpleCache(
                        cacheDir,
                        new LeastRecentlyUsedCacheEvictor((Runtime.getRuntime().maxMemory() / 1024)/2),
                        databaseProvider
                );
            }

            // 2. Create a CacheDataSourceFactory that will be used for both playback and pre-caching.
            if (cacheDataSourceFactory == null) {
                cacheDataSourceFactory = new CacheDataSource.Factory()
                        .setCache(exoPlayerCache)
                        .setUpstreamDataSourceFactory(new DefaultDataSource.Factory(this));
            }

            startPreloadingTasks(); //start preload task for cache
            // --- END CACHING SETUP ---

            // 3. Build ExoPlayer using the caching factory.
            exoPlayer = new ExoPlayer.Builder(BodyActivity.this)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(cacheDataSourceFactory))
                    .build();

            binding.videoLoader.setPlayer(exoPlayer);
            binding.videoLoader.setKeepScreenOn(true);

            setUpPlayerMedia();
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

                        // Calculate the desired scroll position based on time and zoom factor
                        int scrollX = (int) (currentTime / 1000.0 * pixelsPerSecond);
                        binding.timelineScrollView.scrollTo(scrollX, 0);
                        // --- END AUTO-SCROLL LOGIC ---


                        setUpSelectedSegment();

                        binding.videoCurrentTimeTv.setText(formatDuration(currentTime));
                        binding.videoLoader.postDelayed(this::getCurrentPlayerPosition, 100);
                    }
                }
            });
        }


    }

    /**
     * Starts a background thread to pre-cache all video segments.
     * This downloads the video data to disk so it's ready for instant playback.
     */

    private void setUpPlayerMedia(){
        long startTime = System.currentTimeMillis();
        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context);
        MediaSource.Factory factory = new ProgressiveMediaSource.Factory(dataSourceFactory);

        if(videoSegmentList != null) {

            ConcatenatingMediaSource2.Builder concatenatingMediaSourceBuilder = new ConcatenatingMediaSource2.Builder();
            for (VideoSegment videoSegment : videoSegmentList) {
                MediaSource mediaSource1 = new ClippingMediaSource(factory.createMediaSource(MediaItem.fromUri(videoSegment.getUri())),
                        videoSegment.getClippingStart()*1000,
                        videoSegment.getClippingEnd()*1000);
                concatenatingMediaSourceBuilder.add(mediaSource1);

            }

            mediaSource = concatenatingMediaSourceBuilder.build();

            exoPlayer.setMediaSource(mediaSource);

            exoPlayer.prepare();
            exoPlayer.seekTo(currentTime);

        }
    }
    private void playVideo(){
        exoPlayer.play();
    }

    private void pauseVideo(){
        exoPlayer.pause();
    }
    //-----------------------------------Cache------------------------------------------------------------
    private void startPreloadingTasks() {
        // Task 1: Pre-cache videos in the background
        Future<?> videoCachingFuture = backgroundExecutor.submit(() -> {
            try {
                for (int i = 0; i < videoSegmentList.size(); i++) {
                    VideoSegment segment = videoSegmentList.get(i);
                    final String progressText = "Preparing video " + (i + 1) + " of " + videoSegmentList.size() + "...";

                    // Post progress updates to the main thread
                    mainThreadHandler.post(() -> binding.loadingStatusText.setText(progressText));

                    DataSpec dataSpec = new DataSpec.Builder()
                            .setUri(segment.getUri())
                            .setLength(C.LENGTH_UNSET) // Tell the cache to resolve the length automatically
                            .build();

                    CacheWriter cacheWriter = new CacheWriter(
                            cacheDataSourceFactory.createDataSource(),
                            dataSpec,
                            null, // You can pass a byte array here if needed, but null is fine
                            null  // Progress listener
                    );
                    cacheWriter.cache(); // Blocking call
                    Log.d("PreCache", "Finished caching: " + segment.getUri());
                }
            } catch (Exception e) {
                Log.e("PreCache", "Error caching videos", e);
            }
        });

        // Task 2: Wait for both tasks to finish, then update the UI
        backgroundExecutor.submit(() -> {
            try {
                // Block and wait for both futures to complete
                videoCachingFuture.get();

                // Now that both tasks are done, post the final UI update to the main thread
                mainThreadHandler.post(() -> {
                    segmentAdapter.notifyDataSetChanged();
                    binding.loadingContainer.setVisibility(View.GONE);
                    binding.timelineScrollView.setEnabled(true);
                    Log.d("PreCache", "All pre-loading tasks complete. UI is now active.");
                });
            } catch (Exception e) {
                Log.e("PreCache", "Error waiting for pre-loading tasks to complete", e);
            }
        });
    }

    //-----------------------------------Thumbnail--------------------------------------------------------
    private void rebuildTimelineAndPlayer() {
        if (exoPlayer == null) return;

        // 1. Rebuild the player's playlist with the new segment order and timings
        setUpPlayerMedia();

        // 2. Tell the adapter that the data has fundamentally changed
        // This will redraw the thumbnails in the new order.
        segmentAdapter.setSegments(videoSegmentList);

        // 3. Redraw the separator lines at their new positions
        updateSeparateLine();

        // 4. Update the selection highlight to follow the moved segment
        setUpSelectedSegment();
    }
    private long getTotalDuration() {
        long total = 0;
        for (VideoSegment segment : videoSegmentList) {
            total += segment.getDuration();
        }
        return total;
    }
    @SuppressLint("ClickableViewAccessibility")
    private void setUpThumbnail() {
        //0. Disable scrolling on the RecyclerView itself
        binding.segmentRecyclerView.setNestedScrollingEnabled(false);

        // 1. Initialize the main SegmentAdapter, passing 'this' as the generator
        segmentAdapter = new SegmentAdapter(this, pixelsPerSecond, this);
        binding.segmentRecyclerView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        binding.segmentRecyclerView.setAdapter(segmentAdapter);

        segmentAdapter.setSegments(videoSegmentList);

        // Use a ViewTreeObserver to wait until the layout is complete.
        binding.segmentRecyclerView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                // This is called after the layout pass is complete.
                int height = binding.segmentRecyclerView.getHeight();
                if (height > 0) {
                    // We got a valid height, so store it.
                    timelineHeightPx = height;

                    // Pass the height to the adapter.
                    if (segmentAdapter != null) {
                        segmentAdapter.setTimelineHeight(timelineHeightPx);
                    }

                    // Remove the listener so it doesn't fire again.
                    binding.segmentRecyclerView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                }
            }
        });

        // 3. set up padding
        // We need to wait for the layout to be drawn to get the width of the scroll view
        binding.timelineScrollView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                // Remove the listener to prevent it from being called multiple times
                binding.timelineScrollView.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                // Calculate the padding needed to center the start/end of the timeline
                int padding = binding.timelineScrollView.getWidth() / 2;

                // Apply the padding to the RecyclerView inside the ScrollView.
                // This pushes the content away from the edges but keeps the scroll area full-width.
                binding.videoEditContainer.setPadding(padding, 0, padding, 0);
            }
        });


        // 4. Handle scrolling to seek the video
        binding.timelineScrollView.setOnTouchListener((v, event) -> {
            // We only care about when the user is actively dragging their finger.
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                // The scrollX of the view gives us the current pixel position at the left edge.
                int scrollX = v.getScrollX();

                // With padding, the scrollX position directly corresponds to the time at the playhead.
                currentTime = (long) (scrollX / pixelsPerSecond * 1000.0);

                // Clamp time to valid range
                long totalDuration = getTotalDuration();
                if (currentTime < 0) currentTime = 0;
                if (currentTime > totalDuration) currentTime = totalDuration;

                // Seek the player but only if the time has actually changed to avoid spamming ExoPlayer
                if (Math.abs(exoPlayer.getCurrentPosition() - currentTime) > 500) { // 500ms threshold
                    exoPlayer.seekTo(currentTime);
                }

                binding.videoCurrentTimeTv.setText(formatDuration(currentTime));
                setUpSelectedSegment();
            }
            else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                // Perform one final, precise seek to the exact end position.
                binding.videoCurrentTimeTv.setText(formatDuration(currentTime));
                exoPlayer.seekTo(currentTime);

            }
            // Return false so that the touch event is passed on to the HorizontalScrollView
            // to perform the actual scrolling. If we return true, scrolling will stop.
            return false;
        });
    }


    //-----------------------------------Segment----------------------------------------------------------
    private void iniSegment() {
        videoSegmentList = new ArrayList<>();
        Intent resultData = getIntent().getParcelableExtra("result_data");
        if (resultData != null) {
            if (resultData.getClipData() != null) {
                // User selected multiple files
                for (int i = 0; i < resultData.getClipData().getItemCount(); i++) {
                    addVideoSegment(resultData.getClipData().getItemAt(i).getUri());
                }
            } else if (resultData.getData() != null) {
                // User selected a single file
                addVideoSegment(resultData.getData());
            }
        }
    }

    private void addVideoSegment(Uri uri) {
        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(this, uri);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);

            long durationMs = Long.parseLong(durationStr);
            int width = Integer.parseInt(widthStr);
            int height = Integer.parseInt(heightStr);

            // Create a segment that uses the entire original video clip
            videoSegmentList.add(new VideoSegment(uri, 0, durationMs, width, height));
            retriever.release();
        } catch (Exception e) {
            Log.e("URI", "Failed to add video segment for URI: " + uri, e);
        }
    }
    private void setUpSelectedSegment(){

        if (videoSegmentList == null || videoSegmentList.isEmpty()) {
            binding.selectedTimeLineView.setVisibility(View.INVISIBLE);
            return;
        }

        long cumulativeDuration = 0;
        VideoSegment activeSegment = null;

        // Find which segment is active based on currentTime
        for (VideoSegment segment : videoSegmentList) {
            if (currentTime >= cumulativeDuration && currentTime < cumulativeDuration + segment.getDuration()) {
                activeSegment = segment;
                selectedSegment = segment;
                break; // Found it
            }
            cumulativeDuration += segment.getDuration();
        }

        if (activeSegment != null) {
            // We found the active segment, now position the selection view
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) binding.selectedTimeLineView.getLayoutParams();

            // Calculate the width of the segment in pixels
            params.width = (int) (activeSegment.getDuration() / 1000.0 * pixelsPerSecond);

            // Calculate the start position (left margin) of the segment in pixels
            // cumulativeDuration already holds the duration of all segments before this one
            params.setMarginStart((int) (cumulativeDuration / 1000.0 * pixelsPerSecond));

            binding.selectedTimeLineView.setLayoutParams(params);
            binding.selectedTimeLineView.setVisibility(View.VISIBLE);

            // Make sure the selection view is drawn on top of separators but behind the playhead
            binding.selectedTimeLineView.bringToFront();
        } else {
            // If no segment is active (e.g., time is out of bounds), hide the view
            binding.selectedTimeLineView.setVisibility(View.INVISIBLE);
            selectedSegment = null;
        }

    }

    private void updateSeparateLine(){
        // Clear any old separators before drawing new ones
        binding.separatorContainer.removeAllViews();

        // Don't draw a line after the very last segment
        if (videoSegmentList.size() <= 1) {
            return;
        }
        long cumulativeDuration = 0;
        for (int i = 0; i < videoSegmentList.size() - 1; i++) {
            VideoSegment segment = videoSegmentList.get(i);
            cumulativeDuration += segment.getDuration();

            // Calculate the position of the separator line in pixels
            int linePosition = (int) (cumulativeDuration / 1000.0 * pixelsPerSecond);

            // Call the drawing method
            drawSeparateLine(linePosition);
        }
    }

    private void drawSeparateLine(int linePosition) {
        View view = new View(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view.setBackgroundColor(getResources().getColor(R.color.black,null));
        }

        // Create layout params for the line
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                10, // The width of the line
                FrameLayout.LayoutParams.MATCH_PARENT
        );

        // The line's position is its left margin. We offset by half its width to center it.
        params.setMarginStart(linePosition - 5);
        view.setLayoutParams(params);

        // Add the line to the overlay container
        binding.separatorContainer.addView(view);
        binding.selectedTimeLineView.bringToFront();
    }

    private void moveToSegment(int direction){
        if (selectedSegment == null) return;

        int currentIndex = videoSegmentList.indexOf(selectedSegment);
        int newIndex = currentIndex + direction;

        // Check if the new position is valid
        if (newIndex >= 0 && newIndex < videoSegmentList.size()) {
            // Swap the segments in the list
            Collections.swap(videoSegmentList, currentIndex, newIndex);

            rebuildTimelineAndPlayer();
        }
    }


    private void cutSegment(VideoSegment segmentToCut, long cutTimeInSegment) {
        if (segmentToCut == null || cutTimeInSegment <= 0 || cutTimeInSegment >= segmentToCut.getDuration()) {
            // Cannot cut at the very beginning or end
            return;
        }

        int segmentIndex = videoSegmentList.indexOf(segmentToCut);
        if (segmentIndex == -1) return;

        long cutTimeInSource = segmentToCut.getClippingStart() + cutTimeInSegment;
        long originalClippingEnd = segmentToCut.getClippingEnd();

        segmentToCut.setClippingEnd(cutTimeInSource);

        VideoSegment newSegment = new VideoSegment(
                segmentToCut.getUri(),
                cutTimeInSource,         // The new start is the cut time.
                originalClippingEnd,     // The end is the original end.
                segmentToCut.getWidth(),
                segmentToCut.getHeight()
        );
        videoSegmentList.add(segmentIndex + 1, newSegment);
        rebuildTimelineAndPlayer();
        exoPlayer.pause();
    }

    private void deleteSegment(VideoSegment segmentToDelete) {
        if (segmentToDelete == null || videoSegmentList.size() <= 1) {
            // Don't allow deleting the last segment
            Toast.makeText(this, "Cannot delete the last segment", Toast.LENGTH_SHORT).show();
            return;
        }

        videoSegmentList.remove(segmentToDelete);
        // Refresh everything
        rebuildTimelineAndPlayer();
    }

    //-----------------------------------Buttons-----------------------------------------------------------
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


        binding.editCancelBtn.setOnClickListener(view -> cleanupAndFinish());
        binding.editCompleteBtn.setOnClickListener(view -> complete());

        binding.videoFullscreenBtn.setOnClickListener(view -> {
            startVideoPlayerActivity(videoSegmentList);
        });

        binding.editSplitBtn.setOnClickListener(view -> {
            if (selectedSegment != null) {
                long segmentStartTimeInTimeline = 0;
                for (VideoSegment segment : videoSegmentList) {
                    if (segment == selectedSegment) {
                        break; // Found the start of our segment
                    }
                    segmentStartTimeInTimeline += segment.getDuration();
                }

                long cutTimeInSegment = currentTime - segmentStartTimeInTimeline;
                cutSegment(selectedSegment, cutTimeInSegment);
            }
        });

        binding.editMoveleftBtn.setOnClickListener(view -> moveToSegment(-1) );
        binding.editMoverightBtn.setOnClickListener(view -> moveToSegment(1) );

        binding.editDeleteBtn.setOnClickListener(view -> deleteSegment(selectedSegment));

        binding.editZoomInBtn.setOnClickListener(v -> {
            pixelsPerSecond *= 1.5; // Zoom in by 50%
            segmentAdapter.setZoom(pixelsPerSecond);
            updateSeparateLine();
        });

        binding.editZoomOutBtn.setOnClickListener(v -> {
            pixelsPerSecond /= 1.5; // Zoom out
            segmentAdapter.setZoom(pixelsPerSecond);
            updateSeparateLine();
        });

    }

    //-----------------------------------Format-----------------------------------------------------------
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

    //-----------------------------------Clean-Up----------------------------------------------------------
    private void releaseResources() {
        Log.d("Cleanup", "Releasing resources...");


        // 1. Stop all background tasks immediately
        // This prevents new caching or thumbnail generation from continuing.
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            Log.d("Cleanup", "Shutting down background executor.");
            backgroundExecutor.shutdown();
        }
        // 2. Release the ExoPlayer instance
        // This is crucial. It stops playback and frees all associated media resources.
        if (exoPlayer != null) {
            Log.d("Cleanup", "Releasing ExoPlayer.");
            exoPlayer.release();
            exoPlayer = null;
        }
        // 4. Release the SimpleCache (disk cache)
        // This closes the database connection and file handles.
        if (exoPlayerCache != null) {
            Log.d("Cleanup", "Releasing SimpleCache.");
            exoPlayerCache.release();
            exoPlayerCache = null;
        }

    }

    private void cleanupAndFinish() {
        Log.d("Cleanup", "User initiated exit. Cleaning up and finishing.");
        releaseResources(); // Call the resource cleanup
        setResult(Activity.RESULT_CANCELED); // Set the result for the parent
        finish(); // Finish the activity
    }

    private void complete(){
        // 1. Show a loading screen and disable buttons to prevent re-entry
        binding.loadingContainer.setVisibility(View.VISIBLE);
        binding.loadingStatusText.setText("Processing video...");
        setAllButtonsEnabled(false);

        // 2. Define the output file path
        SettingsManager settingsManager = new SettingsManager(this);
        String outputDirectoryPath = settingsManager.getOutputPath();
        File outputDir = new File(outputDirectoryPath);

        if (!outputDir.exists()) {
            outputDir.mkdirs(); // Create the directory if it doesn't exist
        }

        String outputVideoPath = new File(outputDir, "final_video_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
        // 3. Create the callback for when FFmpeg finishes the entire process
        FFmpegSessionCompleteCallback ffmpegCallback = session -> {
            if (ReturnCode.isSuccess(session.getReturnCode())) {
                // SUCCESS
                Log.i("VideoProcUtils", "Smart render completed successfully to:" + outputVideoPath);
                runOnUiThread(() -> {
                    Toast.makeText(BodyActivity.this, "Video saved to: " + outputVideoPath, Toast.LENGTH_LONG).show();

                    // Set the result to OK for MainActivity
                    Intent resultIntent = new Intent();
                    resultIntent.putExtra("video_path", outputVideoPath);
                    setResult(Activity.RESULT_OK, resultIntent);

                    // Clean up resources and finish the activity
                    cleanupAndFinish();
                });
            } else {
                // FAILURE
                Log.e("VideoProcUtils", String.format("Smart render failed with state %s and rc %s.%s", session.getState(), session.getReturnCode(), session.getFailStackTrace()));
                runOnUiThread(() -> {
                    Toast.makeText(BodyActivity.this, "Error processing video.", Toast.LENGTH_LONG).show();
                    // Hide loading screen and re-enable buttons on failure
                    binding.loadingContainer.setVisibility(View.GONE);
                    setAllButtonsEnabled(true);
                });
            }
        };

        // 4. Execute the smart render process
        // Your VideoProcUtils method expects an ArrayList, so we create one from our list.
        ArrayList<VideoSegment> segmentsToProcess = new ArrayList<>(videoSegmentList);
        VideoProcUtils.execute(BodyActivity.this, segmentsToProcess, outputVideoPath, ffmpegCallback);

        //setResult(Activity.RESULT_OK);
        //finish();
    }

    //-----------------------------------Helpers-----------------------------------------------------------
    private void setAllButtonsEnabled(boolean isEnabled) {
        binding.editCompleteBtn.setEnabled(isEnabled);
        binding.editCancelBtn.setEnabled(isEnabled);
        binding.editSplitBtn.setEnabled(isEnabled);
        binding.editDeleteBtn.setEnabled(isEnabled);
        binding.editMoveleftBtn.setEnabled(isEnabled);
        binding.editMoverightBtn.setEnabled(isEnabled);
        binding.editZoomInBtn.setEnabled(isEnabled);
        binding.editZoomOutBtn.setEnabled(isEnabled);
        binding.videoPlayBtn.setEnabled(isEnabled);
        binding.videoPauseBtn.setEnabled(isEnabled);
        binding.videoFullscreenBtn.setEnabled(isEnabled);
        // Add any other buttons or interactive views here
    }

    //-----------------------------------New-Activity-----------------------------------------------------------
    //change to new segmentList+++++++++++++++++++++++++++++++++++++++++++++++
    private void startVideoPlayerActivity(List<VideoSegment> SegmentList) {
        exoPlayer.pause();
        Intent intent = new Intent(this, VideoPlayerActivity.class);
        ArrayList<VideoSegment> segmentsToPass = new ArrayList<>(SegmentList); // cast List to an ArrayList
        intent.putParcelableArrayListExtra("segmentList", segmentsToPass);
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
                        exoPlayer.play();
                    }
                }
            });

    //-----------------------------------System-UI---------------------------------------------------------
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