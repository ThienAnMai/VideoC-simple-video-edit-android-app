package com.example.videoc;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Looper;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.collection.LruCache;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SegmentAdapter extends RecyclerView.Adapter<SegmentAdapter.SegmentViewHolder> {

    private final List<VideoSegment> segments = new ArrayList<>();
    private final Context context;
    private double pixelsPerSecond; // This is our zoom factor
    private final ThumbnailGenerator thumbnailGenerator;

    private final LruCache<String, Bitmap> thumbnailCache;
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private int timelineHeightPx;


    public interface ThumbnailGenerator {
        Bitmap generateThumbnail(Uri uri, long timestampMs, int width, int height);
    }

    public SegmentAdapter(Context context, double initialPixelsPerSecond, ThumbnailGenerator generator) {
        this.context = context;
        this.pixelsPerSecond = initialPixelsPerSecond;
        this.thumbnailGenerator = generator;
        this.timelineHeightPx = (int) (100 * context.getResources().getDisplayMetrics().density);

        // Calculate a reasonable cache size, e.g., 1/8th of the app's available memory.
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8; // The size is in kilobytes.

        this.thumbnailCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(@NonNull String key, @NonNull Bitmap bitmap) {
                // Return the size of the bitmap in kilobytes.
                return bitmap.getByteCount() / 1024;
            }
        };
    }

    @NonNull
    @Override
    public SegmentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Each segment is a RecyclerView that will hold the thumbnails
        View view = LayoutInflater.from(context).inflate(R.layout.segment_item, parent, false);
        return new SegmentViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SegmentViewHolder holder, int position) {
        VideoSegment segment = segments.get(position);

        // Get the video's dimensions.
        int videoWidth = segment.getWidth();
        int videoHeight = segment.getHeight();
        if (videoWidth <= 0 || videoHeight <= 0) {
            videoWidth = 16; videoHeight = 9; // Default to a 16:9 aspect ratio to avoid errors.
        }

        // Calculate the width for one thumbnail to maintain the aspect ratio.
        double aspectRatio = (double) videoWidth / videoHeight;
        int singleThumbnailWidth = (int) (this.timelineHeightPx * aspectRatio);
        if (singleThumbnailWidth <= 0) {
            singleThumbnailWidth = this.timelineHeightPx; // Fallback to a square if calculation fails.
        }


        // Calculate the VISIBLE width of the segment container ---
        int segmentVisibleWidth = (int) (segment.getDuration() / 1000.0 * pixelsPerSecond);
        ViewGroup.LayoutParams segmentParams = holder.segmentContainer.getLayoutParams();
        segmentParams.width = segmentVisibleWidth;
        holder.segmentContainer.setLayoutParams(segmentParams);


        // --- STEP 3: Calculate how many thumbnails are needed and the TOTAL width for the inner RecyclerView ---

        // Determine how many thumbnails we need to draw to completely fill the visible area.
        // We use Math.ceil to ensure we draw the partially visible last thumbnail.
        int numThumbnails = (int) Math.ceil((double) segmentVisibleWidth / singleThumbnailWidth);
        if (numThumbnails <= 0) {
            numThumbnails = 1; // Always draw at least one thumbnail.
        }

        // The total width of the inner RecyclerView is simply the number of thumbnails times their individual width.
        int totalThumbnailsWidth = numThumbnails * singleThumbnailWidth;

        // Apply this total width to the inner RecyclerView. This is the critical fix.
        ViewGroup.LayoutParams innerParams = holder.thumbnailContainer.getLayoutParams();
        innerParams.width = totalThumbnailsWidth;
        holder.thumbnailContainer.setLayoutParams(innerParams);


        // --- STEP 4: Set up the inner adapter ---

        // The inner adapter now needs to know the timestamp interval between each thumbnail.
        double timePerThumbnail = (double) (singleThumbnailWidth / pixelsPerSecond)*1000; // Convert to milliseconds.

        ThumbnailAdapter thumbnailAdapter = new ThumbnailAdapter(
                segment.getUri(),
                numThumbnails,
                singleThumbnailWidth, // Pass the calculated width for one thumbnail.
                timelineHeightPx,
                // Pass the time interval.
                timePerThumbnail,
                thumbnailGenerator,
                thumbnailCache,
                thumbnailExecutor,
                segment.getClippingStart());
        holder.thumbnailContainer.setAdapter(thumbnailAdapter);
    }


    @Override
    public int getItemCount() {
        return segments.size();
    }

    // Method to update the data
    @SuppressLint("NotifyDataSetChanged")
    public void setSegments(List<VideoSegment> newSegments) {
        this.segments.clear();
        this.segments.addAll(newSegments);
        notifyDataSetChanged();
    }

    // Method to update the zoom level
    @SuppressLint("NotifyDataSetChanged")
    public void setZoom(double newPixelsPerSecond) {
        this.pixelsPerSecond = newPixelsPerSecond;
        notifyDataSetChanged(); // This re-binds all views, recalculating widths and thumbnails
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setTimelineHeight(int height) {
        if (this.timelineHeightPx != height) {
            this.timelineHeightPx = height;
            // Redraw the timeline now that we have the correct height.
            notifyDataSetChanged();
        }
    }

    // ViewHolder for the outer RecyclerView (each item is a segment)
    static class SegmentViewHolder extends RecyclerView.ViewHolder {
        FrameLayout segmentContainer;
        RecyclerView thumbnailContainer; // This is the inner RecyclerView

        public SegmentViewHolder(@NonNull View itemView) {
            super(itemView);
            segmentContainer = itemView.findViewById(R.id.segment_container);    // The inner RecyclerView is now found inside the FrameLayout
            thumbnailContainer = itemView.findViewById(R.id.thumbnail_recycler_view);
            thumbnailContainer.setLayoutManager(new LinearLayoutManager(itemView.getContext(), LinearLayoutManager.HORIZONTAL, false));
        }
    }


    // Inner adapter for displaying the actual thumbnails
    private static class ThumbnailAdapter extends RecyclerView.Adapter<ThumbnailAdapter.ThumbnailViewHolder> {
        private final Uri videoUri;
        private final int numThumbnails;
        private final int thumbnailWidth;
        private final int thumbnailHeight;
        private final SegmentAdapter.ThumbnailGenerator generator;
        private final LruCache<String, Bitmap> cache;
        private final ExecutorService executor;
        private final double timePerThumbnail;
        private final long clipStart;

        public ThumbnailAdapter(Uri uri,
                                int numThumbnails,
                                int thumbnailWidth, int thumbnailHeight,
                                double timePerThumbnail,
                                SegmentAdapter.ThumbnailGenerator generator,
                                LruCache<String, Bitmap> cache,
                                ExecutorService executor, long clipStart) {
            this.videoUri = uri;
            this.numThumbnails = numThumbnails;
            this.thumbnailWidth = thumbnailWidth;
            this.thumbnailHeight = thumbnailHeight;
            this.timePerThumbnail = timePerThumbnail;
            this.generator = generator;
            this.cache = cache;
            this.executor = executor;
            this.clipStart = clipStart;
        }

        @NonNull
        @Override
        public ThumbnailViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView imageView = new ImageView(parent.getContext());
            imageView.setLayoutParams(new ViewGroup.LayoutParams(thumbnailWidth, ViewGroup.LayoutParams.MATCH_PARENT));
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            return new ThumbnailViewHolder(imageView);
        }

        @Override
        public void onBindViewHolder(@NonNull ThumbnailViewHolder holder, int position) {
            final long timestampMs = clipStart + (long) (position * timePerThumbnail);
            final String cacheKey = videoUri.toString()+  "_" + timestampMs;

            holder.imageView.setTag(cacheKey);

            // Set a placeholder
            holder.imageView.setBackgroundColor(Color.DKGRAY);
            holder.imageView.setImageBitmap(null);

            final Bitmap cachedBitmap = cache.get(cacheKey);
            if (cachedBitmap != null) {
                holder.imageView.setImageBitmap(cachedBitmap);
            } else {
                executor.submit(() -> {
                    try {
                        Bitmap thumbnail = generator.generateThumbnail(videoUri, timestampMs, thumbnailWidth, thumbnailHeight);
                        if (thumbnail != null) {
                            cache.put(cacheKey, thumbnail);
                            new Handler(Looper.getMainLooper()).post(() -> {
                                if (cacheKey.equals(holder.imageView.getTag())) {
                                    holder.imageView.setImageBitmap(thumbnail);
                                }
                            });
                        }
                    } catch (Exception e) {
                        Log.e("ThumbnailGen", "Error generating single thumbnail", e);
                    }
                });
            }
        }

        @Override
        public int getItemCount() {
            return numThumbnails;
        }

        static class ThumbnailViewHolder extends RecyclerView.ViewHolder {
            ImageView imageView;

            public ThumbnailViewHolder(@NonNull View itemView) {
                super(itemView);
                imageView = (ImageView) itemView;
            }
        }
    }
}
