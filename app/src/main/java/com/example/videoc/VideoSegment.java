package com.example.videoc;

import android.content.Intent;
import android.net.Uri;

public class VideoSegment {
    long startTime;
    long endTime;
    long duration;
    long clippingStart;
    long clippingEnd;
    Uri uri;
    int width = 1;
    int startPosition = 0;
    long frameInterval = 10000;

    public VideoSegment() {
    }

    public VideoSegment(Uri uri, long startTime, long duration) {
        this.startTime = startTime;
        this.duration = duration;
        this.endTime = startTime + duration;
        this.uri = uri;
        this.clippingStart = 0;
        this.clippingEnd = this.clippingStart + this.duration;
        setUpIniInterval(duration);
    }

    public VideoSegment(Uri uri, long startTime, long duration, long clippingStart) {
        this.startTime = startTime;
        this.duration = duration;
        this.endTime = startTime + duration;
        this.uri = uri;
        this.clippingStart = clippingStart;
        this.clippingEnd = this.clippingStart + this.duration;
        setUpIniInterval(duration);
    }
    public VideoSegment(Uri uri, long startTime, long duration, long clippingStart, long clippingEnd) {
        this.startTime = startTime;
        this.duration = duration;
        this.endTime = startTime + duration;
        this.uri = uri;
        this.clippingStart = clippingStart;
        this.clippingEnd = clippingEnd;
        setUpIniInterval(duration);
    }


    private void setUpIniInterval(long duration){
        if(duration >= 3600000){
            this.frameInterval = 600000;
        } else if (duration >= 1800000) {
            this.frameInterval = 300000;
        } else if (duration >= 600000) {
            this.frameInterval = 60000;            
        } else if (duration >= 120000) {
            this.frameInterval = 20000;
        } else if (duration >= 30000) {
            this.frameInterval = 5000;
        } else this.frameInterval = duration;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
        this.duration = this.endTime-startTime;
        setUpIniInterval(this.duration);
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
        this.duration = endTime - this.startTime;
        setUpIniInterval(this.duration);
    }

    public long getFrameInterval() {
        return frameInterval;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
        this.endTime = this.startTime + duration;
        setUpIniInterval(duration);
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

    public int getStartPosition() {
        return startPosition;
    }

    public void setStartPosition(int startPosition) {
        this.startPosition = startPosition;
    }
}
