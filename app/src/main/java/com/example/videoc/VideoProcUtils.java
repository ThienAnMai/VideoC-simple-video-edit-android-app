package com.example.videoc;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.AsyncFFmpegExecuteTask;
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.Level;
import com.arthenica.ffmpegkit.LogCallback;
import com.arthenica.ffmpegkit.Statistics;

import java.util.ArrayList;
import java.util.Arrays;

public class VideoProcUtils {

    public static void execute(ArrayList<VideoSegment> videoSegmentArrayList, FFmpegSessionCompleteCallback executeCallback) {
        //String[] ffmpegCommand = generateMergeAndTrimCommand(inputVideoPaths, outputVideoPath, startTimeMs, endTimeMs);
        for(VideoSegment videoSegment : videoSegmentArrayList){

        }

        String[] ffmpegCommand = new String[0];
        FFmpegKit.executeAsync(Arrays.toString(ffmpegCommand), executeCallback);
    }
    private static String[] generateMergeCommand(String[] inputVideoPaths) {
        String inputList = "concat:" + String.join("|", inputVideoPaths);
        return new String[]{"-i", inputList, "-filter_complex", "concat=n=" + inputVideoPaths.length + ":v=1:a=1", "-c:a", "aac", "-strict", "experimental"};
    }

    private static String[] generateTrimCommand(String outputVideoPath, long startTimeMs, long endTimeMs) {
        String startTime = String.valueOf(startTimeMs / 1000.0); // Convert to seconds
        String endTime = String.valueOf(endTimeMs / 1000.0); // Convert to seconds

        return new String[]{"-ss", startTime, "-to", endTime, "-c", "copy", outputVideoPath};
    }

}
