package com.example.videoc;

import android.net.Uri;

public class VideoSegment {
    long clippingStart;
    long clippingEnd;
    Uri uri;
    int width;
    int height;


    public VideoSegment() {
    }

    public VideoSegment(Uri uri, long clippingStart, long clippingEnd, int width, int height) {
        this.uri = uri;
        // Ensure clipping start is never less than 0
        this.clippingStart = Math.max(0, clippingStart);
        this.clippingEnd = clippingEnd;
        this.width = width;
        this.height = height;
    }

    public long getDuration() {
        // Duration cannot be negative
        return Math.max(0, clippingEnd - clippingStart);
    }
    public long getClippingStart() {
        return clippingStart;
    }

    public void setClippingStart(long clippingStart) {
        this.clippingStart = clippingStart;
    }

    public long getClippingEnd() {
        return clippingEnd;
    }

    public void setClippingEnd(long clippingEnd) {
        this.clippingEnd = clippingEnd;
    }

    public Uri getUri() {
        return uri;
    }

    public void setUri(Uri uri) {
        this.uri = uri;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }
}
