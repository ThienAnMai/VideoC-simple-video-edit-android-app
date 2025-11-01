package com.example.videoc;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class SegmentAdapter extends RecyclerView.Adapter<SegmentAdapter.SegmentViewHolder> {

    private final List<VideoSegment> segments = new ArrayList<>();
    private final Context context;
    private double pixelsPerSecond; // This is our zoom factor
    private final ThumbnailGenerator thumbnailGenerator;

    public interface ThumbnailGenerator {
        List<Bitmap> generateThumbnailsForSegment(VideoSegment segment, double pixelsPerSecond);
    }

    public SegmentAdapter(Context context, double initialPixelsPerSecond, ThumbnailGenerator generator) {
        this.context = context;
        this.pixelsPerSecond = initialPixelsPerSecond;
        this.thumbnailGenerator = generator;
    }

    @NonNull
    @Override
    public SegmentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Each segment is a RecyclerView that will hold the thumbnails
        RecyclerView innerRecyclerView = new RecyclerView(context);
        innerRecyclerView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        innerRecyclerView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        return new SegmentViewHolder(innerRecyclerView);
    }

    @Override
    public void onBindViewHolder(@NonNull SegmentViewHolder holder, int position) {
        VideoSegment segment = segments.get(position);

        // 1. Calculate the width of the segment based on its duration and the zoom factor
        int segmentWidth = (int) (segment.getDuration() / 1000.0 * pixelsPerSecond);
        ViewGroup.LayoutParams params = holder.itemView.getLayoutParams();
        params.width = segmentWidth;
        holder.itemView.setLayoutParams(params);
        // If the inner RecyclerView already has an adapter, clean up its old bitmaps first.
        if (holder.innerRecyclerView.getAdapter() != null) {
            ((ThumbnailAdapter) holder.innerRecyclerView.getAdapter()).cleanup();
        }

        // 2. Generate thumbnails for this segment based on the current zoom
        List<Bitmap> thumbnails = thumbnailGenerator.generateThumbnailsForSegment(segment, pixelsPerSecond);

        // 3. Calculate the width for each individual thumbnail
        int singleThumbnailWidth = 0;
        if (!thumbnails.isEmpty()) {
            // Divide the total segment width by the number of thumbnails to get the width for each
            singleThumbnailWidth = segmentWidth / thumbnails.size();
        }

        // 4. Pass the calculated width to the inner adapter's constructor
        ThumbnailAdapter innerAdapter = new ThumbnailAdapter(thumbnails, singleThumbnailWidth);


        holder.innerRecyclerView.setAdapter(innerAdapter);
    }

    // Clean up when a segment scrolls off-screen
    @Override
    public void onViewRecycled(@NonNull SegmentViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder.innerRecyclerView.getAdapter() != null) {
            ((ThumbnailAdapter) holder.innerRecyclerView.getAdapter()).cleanup();
        }
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

    // ViewHolder for the outer RecyclerView (each item is a segment)
    static class SegmentViewHolder extends RecyclerView.ViewHolder {
        RecyclerView innerRecyclerView;

        public SegmentViewHolder(@NonNull View itemView) {
            super(itemView);
            innerRecyclerView = (RecyclerView) itemView;
        }
    }

    // Inner adapter for displaying the actual thumbnails
    private static class ThumbnailAdapter extends RecyclerView.Adapter<ThumbnailAdapter.ThumbnailViewHolder> {
        private final List<Bitmap> thumbnails;
        private final int thumbnailWidth;

        public ThumbnailAdapter(List<Bitmap> thumbnails, int thumbnailWidth) {
            this.thumbnails = thumbnails;
            this.thumbnailWidth = thumbnailWidth;
        }
        public void cleanup() {
            if (thumbnails != null) {
                for (Bitmap bitmap : thumbnails) {
                    if (bitmap != null && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                }
                thumbnails.clear();
            }
        }

        @NonNull
        @Override
        public ThumbnailViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video_edit, parent, false);
            // Set the fixed width for each thumbnail
            ViewGroup.LayoutParams params = view.getLayoutParams();
            params.width = this.thumbnailWidth;
            view.setLayoutParams(params);
            return new ThumbnailViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ThumbnailViewHolder holder, int position) {
            holder.imageView.setImageBitmap(thumbnails.get(position));
        }

        @Override
        public int getItemCount() {
            return thumbnails.size();
        }

        static class ThumbnailViewHolder extends RecyclerView.ViewHolder {
            ImageView imageView;
            public ThumbnailViewHolder(@NonNull View itemView) {
                super(itemView);
                imageView = itemView.findViewById(R.id.thumbnailImageView);
            }
        }
    }
}
