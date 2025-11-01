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
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Context;

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
import androidx.recyclerview.widget.RecyclerView;

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

@UnstableApi public class BodyActivity extends AppCompatActivity implements SegmentAdapter.ThumbnailGenerator {
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
    private SegmentAdapter segmentAdapter;
    VideoSegment selectedSegment;
    private SimpleCache exoPlayerCache;
    private CacheDataSource.Factory cacheDataSourceFactory;
    private long currentTime = 0;
    final int[] currentWidth = {0};
    private Handler handler;
    List<Bitmap> frameList;
    private double pixelsPerSecond = 50.0; // Initial zoom level. e.g., 50 pixels represents 1 second.
    // A cache to store generated thumbnails. Key: "uri_timestamp", Value: Bitmap
    private static LruCache<String, Bitmap> thumbnailCache;
    // For running background tasks like caching and thumbnail generation
    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(2); // 2 threads for parallel tasks
    // For posting results back to the main UI thread
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());


    // ----------Body Activity-------------

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        context = this;
        binding = ActivityBodyBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        hideSystemUI();

        // --- INITIALIZE THE THUMBNAIL CACHE ---
        // Get max available VM memory, exceeding this amount will throw an
        // OutOfMemory exception. Stored in kilobytes.
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);

        // Use 1/8th of the available memory for this memory cache.
        final int cacheSize = maxMemory / 8;

        thumbnailCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                // The cache size will be measured in kilobytes rather than number of items.
                // This is crucial for managing memory with images of different sizes.
                return bitmap.getByteCount() / 1024;
            }
        };
        // --- END INITIALIZATION ---

        iniSegment();
        setUpPlayer();


        handler = new Handler(Looper.getMainLooper());

        gestureDetector = new GestureDetector(this, new MyGestureListener());

        updateSeparateLine();
        setUpBtns();

        setUpThumbnail();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d("Cleanup", "onDestroy called.");
        binding.videoLoader.removeCallbacks(null);
        cleanupAndFinish();

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
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
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

                setUpSelectedSegment();


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
            // 1. Create a single cache instance.
            if (exoPlayerCache == null) {
                File cacheDir = new File(getCacheDir(), "exoPlayerCache");
                DatabaseProvider databaseProvider = new StandaloneDatabaseProvider(this);
                exoPlayerCache = new SimpleCache(
                        cacheDir,
                        new LeastRecentlyUsedCacheEvictor(5 * 1024 * 1024 * 1024), // 5 GB cache size
                        databaseProvider
                );
            }

            // 2. Create a CacheDataSourceFactory that will be used for both playback and pre-caching.
            if (cacheDataSourceFactory == null) {
                cacheDataSourceFactory = new CacheDataSource.Factory()
                        .setCache(exoPlayerCache)
                        .setUpstreamDataSourceFactory(new DefaultDataSource.Factory(this));
            }
            // --- END CACHING SETUP ---

            // 3. Build ExoPlayer using the caching factory.
            exoPlayer = new ExoPlayer.Builder(BodyActivity.this)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(cacheDataSourceFactory))
                    .build();

            binding.videoLoader.setPlayer(exoPlayer);
            binding.videoLoader.setKeepScreenOn(true);

            setUpPlayerMedia();
            exoPlayer.setPlayWhenReady(false);

            startPreloadingTasks(); //start preload task for cache

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
        binding.videoPlayBtn.setVisibility(View.INVISIBLE);
        binding.videoPauseBtn.setVisibility(View.VISIBLE);
        exoPlayer.play();
    }

    private void pauseVideo(){
        binding.videoPlayBtn.setVisibility(View.VISIBLE);
        binding.videoPauseBtn.setVisibility(View.INVISIBLE);
        exoPlayer.pause();
    }
    //-------cache----------------
    private void startPreloadingTasks() {
        // Show the loading screen before starting any tasks
        binding.loadingContainer.setVisibility(View.VISIBLE);
        binding.timelineScrollView.setEnabled(false);

        // Task 1: Pre-cache videos in the background
        Future<?> videoCachingFuture = backgroundExecutor.submit(() -> {
            try {
                for (int i = 0; i < videoSegmentList.size(); i++) {
                    VideoSegment segment = videoSegmentList.get(i);
                    final String progressText = "Preparing video " + (i + 1) + " of " + videoSegmentList.size() + "...";

                    // Post progress updates to the main thread
                    mainThreadHandler.post(() -> binding.loadingStatusText.setText(progressText));

                    DataSpec dataSpec = new DataSpec(segment.getUri());
                    CacheWriter cacheWriter = new CacheWriter(
                            cacheDataSourceFactory.createDataSource(),
                            dataSpec,
                            null,
                            null // Progress listener
                    );
                    cacheWriter.cache(); // Blocking call
                    Log.d("PreCache", "Finished caching: " + segment.getUri());
                }
            } catch (Exception e) {
                Log.e("PreCache", "Error caching videos", e);
            }
        });

        // Task 2: Generate thumbnails in the background
        Future<?> thumbnailGenerationFuture = backgroundExecutor.submit(() -> {
            try {
                for (VideoSegment segment : videoSegmentList) {
                    generateThumbnailsForSegment(segment, pixelsPerSecond);
                }
                Log.d("ThumbnailGen", "Background thumbnail generation complete.");
            } catch (Exception e) {
                Log.e("ThumbnailGen", "Error generating thumbnails in background", e);
            }
        });

        // Task 3: Wait for both tasks to finish, then update the UI
        backgroundExecutor.submit(() -> {
            try {
                // Block and wait for both futures to complete
                videoCachingFuture.get();
                thumbnailGenerationFuture.get();

                // Now that both tasks are done, post the final UI update to the main thread
                mainThreadHandler.post(() -> {
                    binding.loadingContainer.setVisibility(View.GONE);
                    binding.timelineScrollView.setEnabled(true);
                    segmentAdapter.notifyDataSetChanged(); // Refresh adapter to show thumbnails
                    Log.d("PreCache", "All pre-loading tasks complete. UI is now active.");
                });
            } catch (Exception e) {
                Log.e("PreCache", "Error waiting for pre-loading tasks to complete", e);
            }
        });
    }


    //-------thumbnail------------

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

        // 2. Set the initial data
        segmentAdapter.setSegments(videoSegmentList);

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
                if (Math.abs(exoPlayer.getCurrentPosition() - currentTime) > 100) { // 100ms threshold
                    exoPlayer.seekTo(currentTime);
                }

                binding.videoCurrentTimeTv.setText(formatDuration(currentTime));
                setUpSelectedSegment();
            }
            // Return false so that the touch event is passed on to the HorizontalScrollView
            // to perform the actual scrolling. If we return true, scrolling will stop.
            return false;
        });
    }


    /**
     * This method is called by the SegmentAdapter to get thumbnails for a segment.
     * It calculates how many thumbnails are needed based on the segment's width.
     */
    @Override
    public List<Bitmap> generateThumbnailsForSegment(VideoSegment segment, double currentPixelsPerSecond) {
        List<Bitmap> thumbnails = new ArrayList<>();

        // --- DYNAMIC THUMBNAIL SIZING (from Layout) ---
        // Get the actual measured height of the RecyclerView. This is the most robust way.
        int thumbnailHeightPx = binding.segmentRecyclerView.getHeight();
        if (thumbnailHeightPx <= 0) {
            // Fallback using a hardcoded density-scaled value if the view hasn't been measured yet.
            thumbnailHeightPx = (int) (100 * getResources().getDisplayMetrics().density);
        }

        // Calculate the thumbnail width based on the video's aspect ratio and our dynamic height.
        int videoWidth = segment.getWidth();
        int videoHeight = segment.getHeight();
        if (videoWidth <= 0 || videoHeight <= 0) {
            videoWidth = 1; videoHeight = 1; // Avoid division by zero
        }
        double aspectRatio = (double) videoWidth / videoHeight;
        int thumbnailWidth = (int) (thumbnailHeightPx * aspectRatio);
        if (thumbnailWidth <= 0) thumbnailWidth = thumbnailHeightPx;

        int segmentWidthInPixels = (int) (segment.getDuration() / 1000.0 * currentPixelsPerSecond);
        int numThumbnails = (int) Math.ceil((double) segmentWidthInPixels / thumbnailWidth);

        if (numThumbnails <= 0) return thumbnails;

        // Use floating-point division to prevent timeInterval from becoming zero on high zoom
        double timeInterval = (double) segment.getDuration() / numThumbnails;
        if (timeInterval <= 0) return thumbnails;

        for (int i = 0; i < numThumbnails; i++) {
            long timeMs = (long) (segment.getClippingStart() + (i * timeInterval));
            String cacheKey = segment.getUri().toString() + "_" + timeMs;

            Bitmap cachedBitmap = thumbnailCache.get(cacheKey);
            if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
                thumbnails.add(cachedBitmap);
                continue; // Already cached and valid, move to the next one
            }

            // If not in cache or cached version is bad, generate it.
            // This block is now a self-contained, thread-safe operation for ONE thumbnail.
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(context, segment.getUri());
                Bitmap frame = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST);

                if (frame != null) {
                    int scaledWidth = (int)(thumbnailHeightPx * ((double)frame.getWidth() / frame.getHeight()));
                    Bitmap scaledBitmap = Bitmap.createScaledBitmap(frame, scaledWidth, thumbnailHeightPx, false);

                    if (frame != scaledBitmap) {
                        frame.recycle();
                    }

                    thumbnails.add(scaledBitmap);
                    thumbnailCache.put(cacheKey, scaledBitmap);
                }
            } catch (Exception e) {
                Log.e("ThumbnailGen", "Error generating thumbnail at " + timeMs, e);
            } finally {
                // Release the retriever immediately after this single use.
                // Its release can no longer affect other bitmaps.
                try {
                    retriever.release();
                } catch (IOException e) {
                    Log.e("ThumbnailGen", "Error releasing retriever inside loop", e);
                }
            }
        }
        return thumbnails;
    }

    //----------segment--------------------
    private void iniSegment(){
        uriArrayList = new ArrayList<>();
        uriArrayList = getIntent().getParcelableArrayListExtra("uriArrayList");

        videoSegmentList = new ArrayList<VideoSegment>();
        //videoSegmentList.clear();
        long startTime = 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        // --- Establish Project Dimensions ---
        int projectWidth = -1;
        int projectHeight = -1;

        for(Uri uri:uriArrayList){
            retriever.setDataSource(context, uri);
            long duration = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            int videoWidth = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int videoHeight = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));

            videoSegmentList.add(new VideoSegment(uri, startTime, duration, videoWidth, videoHeight));
            startTime += duration;
        }
        try {
            retriever.release();
        } catch (IOException e) {
            throw new RuntimeException(e);
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

            // Recalculate the start times for all segments
            long cumulativeTime = 0;
            for (VideoSegment segment : videoSegmentList) {
                segment.setStartTime(cumulativeTime);
                cumulativeTime += segment.getDuration();
            }

            // Refresh the UI completely
            setUpPlayerMedia();
            segmentAdapter.setSegments(videoSegmentList); // Use the dedicated method to update adapter data
            updateSeparateLine();
            setUpSelectedSegment();
        }
    }


    private void cutSegment(VideoSegment segmentToCut, long cutTimeInSegment) {
        if (segmentToCut == null || cutTimeInSegment <= 0 || cutTimeInSegment >= segmentToCut.getDuration()) {
            // Cannot cut at the very beginning or end
            return;
        }

        int segmentIndex = videoSegmentList.indexOf(segmentToCut);
        if (segmentIndex == -1) return;

        // Create the new segment that represents the second half
        VideoSegment newSegment = new VideoSegment(
                segmentToCut.getUri(),
                segmentToCut.getStartTime() + cutTimeInSegment,
                segmentToCut.getDuration() - cutTimeInSegment,
                segmentToCut.getClippingStart() + cutTimeInSegment, // Adjust clipping start
                segmentToCut.getWidth(),
                segmentToCut.getHeight()
        );
        newSegment.setClippingEnd(segmentToCut.getClippingEnd()); // Clipping end remains the same relative to original video

        // Update the original segment to be the first half
        segmentToCut.setDuration(cutTimeInSegment);
        segmentToCut.setClippingEnd(segmentToCut.getClippingStart() + cutTimeInSegment);

        // Add the new segment to the list right after the original one
        videoSegmentList.add(segmentIndex + 1, newSegment);

        // Refresh everything
        setUpPlayerMedia();
        segmentAdapter.setSegments(videoSegmentList);
        updateSeparateLine();
        setUpSelectedSegment();
        exoPlayer.pause();
    }

    private void deleteSegment(VideoSegment segmentToDelete) {
        if (segmentToDelete == null || videoSegmentList.size() <= 1) {
            // Don't allow deleting the last segment
            Toast.makeText(this, "Cannot delete the last segment", Toast.LENGTH_SHORT).show();
            return;
        }

        videoSegmentList.remove(segmentToDelete);

        // Recalculate start times
        long cumulativeTime = 0;
        for (VideoSegment segment : videoSegmentList) {
            segment.setStartTime(cumulativeTime);
            cumulativeTime += segment.getDuration();
        }

        // Refresh everything
        setUpPlayerMedia();
        segmentAdapter.setSegments(videoSegmentList);
        updateSeparateLine();
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


        binding.editCancelBtn.setOnClickListener(view -> cleanupAndFinish());
        binding.editCompleteBtn.setOnClickListener(view -> onBackPressed());

        binding.videoFullscreenBtn.setOnClickListener(view -> {
            startVideoPlayerActivity(getIntent().getParcelableExtra("uri"));
        });

        binding.editSplitBtn.setOnClickListener(view -> {
            if (selectedSegment != null) {
                // Calculate the cut time relative to the start of the selected segment
                long cutTimeInSegment = currentTime - selectedSegment.getStartTime();
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
    //--------------stop------------------------
    private void cleanupAndFinish() {
        Log.d("Cleanup", "Starting cleanup process...");


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
        // 3. Clear the thumbnail cache to free up memory
        if (thumbnailCache != null) {
            Log.d("Cleanup", "Evicting all thumbnails from memory cache.");
            thumbnailCache.evictAll();
        }
        // 4. Release the SimpleCache (disk cache)
        // This closes the database connection and file handles.
        if (exoPlayerCache != null) {
            Log.d("Cleanup", "Releasing SimpleCache.");
            exoPlayerCache.release();
            exoPlayerCache = null;
        }
        // 5. Finally, finish the activity
        finish();

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