package com.example.videoc;

import android.net.Uri;

public class VideoSegment {
    long startTime;
    long endTime;
    long duration;
    long clippingStart;
    long clippingEnd;
    Uri uri;
    int width;
    int height;


    public VideoSegment() {
    }

    public VideoSegment(Uri uri, long startTime, long duration, int width, int height) {
        this.startTime = startTime;
        this.duration = duration;
        this.endTime = startTime + duration;
        this.uri = uri;
        this.width = width;
        this.height = height;
        this.clippingStart = 0;
        this.clippingEnd = this.clippingStart + this.duration;
    }

    public VideoSegment(Uri uri, long startTime, long duration, long clippingStart, int width, int height) {
        this.startTime = startTime;
        this.duration = duration;
        this.endTime = startTime + duration;
        this.uri = uri;
        this.width = width;
        this.height = height;
        this.clippingStart = clippingStart;
        this.clippingEnd = this.clippingStart + this.duration;
    }
    public VideoSegment(Uri uri, long startTime, long duration, long clippingStart, long clippingEnd, int width, int height) {
        this.startTime = startTime;
        this.duration = duration;
        this.endTime = startTime + duration;
        this.uri = uri;
        this.width = width;
        this.height = height;
        this.clippingStart = clippingStart;
        this.clippingEnd = clippingEnd;
    }


    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
        this.duration = this.endTime-startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
        this.duration = endTime - this.startTime;
    }


    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
        this.endTime = this.startTime + duration;
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
