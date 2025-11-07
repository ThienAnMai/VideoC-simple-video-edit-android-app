package com.example.videoc;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public class VideoSegment implements Parcelable {
    long clippingStart;
    long clippingEnd;
    Uri uri;
    int width;
    int height;

    public VideoSegment(Uri uri, long clippingStart, long clippingEnd, int width, int height) {
        this.uri = uri;
        // Ensure clipping start is never less than 0
        this.clippingStart = Math.max(0, clippingStart);
        this.clippingEnd = clippingEnd;
        this.width = width;
        this.height = height;
    }

    protected VideoSegment(Parcel in) {
        clippingStart = in.readLong();
        clippingEnd = in.readLong();
        uri = in.readParcelable(Uri.class.getClassLoader());
        width = in.readInt();
        height = in.readInt();
    }

    public static final Creator<VideoSegment> CREATOR = new Creator<VideoSegment>() {
        @Override
        public VideoSegment createFromParcel(Parcel in) {
            return new VideoSegment(in);
        }

        @Override
        public VideoSegment[] newArray(int size) {
            return new VideoSegment[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int i) {
        parcel.writeLong(clippingStart);
        parcel.writeLong(clippingEnd);
        parcel.writeParcelable(uri, i);
        parcel.writeInt(width);
        parcel.writeInt(height);
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
