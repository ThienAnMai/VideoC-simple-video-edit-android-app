package com.example.videoc;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class VideoProcUtils {

    private static final String TAG = "VideoProcUtils";

    /**
     * Executes a "smart render" process. It re-encodes only the necessary small portions
     * around the cut points and losslessly copies the main content.
     *
     * @param context               The application context.
     * @param videoSegmentArrayList The list of segments to process.
     * @param outputVideoPath       The final output video path.
     * @param executeCallback       The final callback when the entire process is complete.
     */
    public static void execute(Context context, ArrayList<VideoSegment> videoSegmentArrayList, String outputVideoPath, FFmpegSessionCompleteCallback executeCallback) {
        if (videoSegmentArrayList == null || videoSegmentArrayList.isEmpty()) {
            return;
        }

        // Create a temporary directory for intermediate video files.
        File tempDir = new File(context.getCacheDir(), "temp_video_parts");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        } else {
            // Clean up old files
            for (File file : tempDir.listFiles()) {
                file.delete();
            }
        }

        List<String> intermediateFiles = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger segmentsProcessed = new AtomicInteger(0);

        for (int i = 0; i < videoSegmentArrayList.size(); i++) {
            VideoSegment segment = videoSegmentArrayList.get(i);
            int segmentIndex = i;

            // Start processing each segment
            processSingleSegment(context, segment, tempDir, segmentIndex, (processedParts) -> {
                intermediateFiles.addAll(processedParts);
                int count = segmentsProcessed.incrementAndGet();

                // If all segments have been processed, start the final concatenation
                if (count == videoSegmentArrayList.size()) {
                    concatenateParts(intermediateFiles, outputVideoPath, tempDir, executeCallback);
                }
            });
        }
    }

    // Interface for callback when a segment is done processing
    private interface SegmentProcessCallback {
        void onComplete(List<String> processedParts);
    }

    /**
     * Processes a single VideoSegment, splitting it into re-encoded and copied parts.
     */
    private static void processSingleSegment(Context context, VideoSegment segment, File tempDir, int segmentIndex, SegmentProcessCallback callback) {
        String realPath = UriUtils.getRealPathFromURI(context, segment.getUri());
        double startTimeSec = segment.getClippingStart() / 1000.0;
        double endTimeSec = segment.getClippingEnd() / 1000.0;

        // 1. Use FFprobe to get keyframe times
        String ffprobeCommand = String.format("-v quiet -print_format json -show_frames -select_streams v:0 -show_entries frame=key_frame,pts_time -i \"%s\"", realPath);

        FFprobeKit.executeAsync(ffprobeCommand, session -> {
            String output = session.getOutput();
            List<Double> keyframeTimes = parseKeyframeTimes(output);

            // 2. Find nearest keyframes
            double startKeyframe = findNearestKeyframe(keyframeTimes, startTimeSec, false);
            double endKeyframe = findNearestKeyframe(keyframeTimes, endTimeSec, true);

            // 3. Generate parts
            List<String> partsToProcess = new ArrayList<>();
            List<String> generatedParts = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger partsCounter = new AtomicInteger(0);

            // Part 1: Head cut (re-encode)
            if (startTimeSec < startKeyframe) {
                String headPartPath = new File(tempDir, String.format("part_%d_head.ts", segmentIndex)).getAbsolutePath();
                String cmd = String.format(Locale.US, "-y -i \"%s\" -ss %.3f -to %.3f -c:v libx264 -preset ultrafast -c:a aac \"%s\"", realPath, startTimeSec, startKeyframe, headPartPath);
                partsToProcess.add(cmd);
                generatedParts.add(headPartPath);
            }

            // Part 2: Middle (lossless copy)
            if (startKeyframe < endKeyframe) {
                String middlePartPath = new File(tempDir, String.format("part_%d_middle.ts", segmentIndex)).getAbsolutePath();
                String cmd = String.format(Locale.US, "-y -ss %.3f -to %.3f -i \"%s\" -c copy -avoid_negative_ts 1 \"%s\"", startKeyframe, endKeyframe, realPath, middlePartPath);
                partsToProcess.add(cmd);
                generatedParts.add(middlePartPath);
            }

            // Part 3: Tail cut (re-encode)
            if (endTimeSec > endKeyframe && endKeyframe > startKeyframe) {
                String tailPartPath = new File(tempDir, String.format("part_%d_tail.ts", segmentIndex)).getAbsolutePath();
                String cmd = String.format(Locale.US, "-y -i \"%s\" -ss %.3f -to %.3f -c:v libx264 -preset ultrafast -c:a aac \"%s\"", realPath, endKeyframe, endTimeSec, tailPartPath);
                partsToProcess.add(cmd);
                generatedParts.add(tailPartPath);
            }

            if (partsToProcess.isEmpty()) {
                callback.onComplete(new ArrayList<>());
                return;
            }

            // Execute all FFmpeg commands for this segment
            for (String command : partsToProcess) {
                FFmpegKit.executeAsync(command, partSession -> {
                    if (partsCounter.incrementAndGet() == partsToProcess.size()) {
                        // All parts for this segment are done
                        callback.onComplete(generatedParts);
                    }
                });
            }
        });
    }

    /**
     * Concatenates all the intermediate .ts files into the final output.
     */
    private static void concatenateParts(List<String> intermediateFiles, String outputVideoPath, File tempDir, FFmpegSessionCompleteCallback finalCallback) {
        // Create a concat file list
        File concatFile = new File(tempDir, "concat_list.txt");
        try (FileWriter writer = new FileWriter(concatFile)) {
            for (String filePath : intermediateFiles) {
                writer.write("file '" + filePath + "'\n");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to create concat file", e);
            // Execute callback with an error
            // and return a session object in the FAILED state.
            FFmpegSession session = FFmpegKit.execute("-i invalid_input");
            // Now, pass this genuinely failed session to the final callback.
            finalCallback.apply(session);
            return;
        }

        // Use the concat demuxer for a lossless final stitch
        String finalCommand = String.format("-y -f concat -safe 0 -i \"%s\" -c copy \"%s\"", concatFile.getAbsolutePath(), outputVideoPath);
        FFmpegKit.executeAsync(finalCommand, session -> {
            // Clean up temp files
            for (File file : tempDir.listFiles()) {
                file.delete();
            }
            tempDir.delete();
            // Fire the final user callback
            finalCallback.apply(session);
        });
    }

    // --- Helper Methods ---

    private static List<Double> parseKeyframeTimes(String ffprobeJsonOutput) {
        List<Double> keyframeTimes = new ArrayList<>();
        try {
            Gson gson = new Gson();
            Type type = new TypeToken<Map<String, List<Map<String, String>>>>() {}.getType();
            Map<String, List<Map<String, String>>> result = gson.fromJson(ffprobeJsonOutput, type);
            List<Map<String, String>> frames = result.get("frames");
            if (frames != null) {
                for (Map<String, String> frame : frames) {
                    if ("1".equals(frame.get("key_frame"))) {
                        keyframeTimes.add(Double.parseDouble(frame.get("pts_time")));
                    }
                }
            }
        } catch (JsonSyntaxException | NumberFormatException e) {
            Log.e(TAG, "Failed to parse ffprobe keyframe output.", e);
        }
        return keyframeTimes;
    }

    private static double findNearestKeyframe(List<Double> keyframeTimes, double targetTime, boolean findPrevious) {
        if (keyframeTimes.isEmpty()) return targetTime;

        double closestKeyframe = -1.0;
        double minDistance = Double.MAX_VALUE;

        for (double kfTime : keyframeTimes) {
            if (findPrevious) { // Find the last keyframe BEFORE or AT the target time
                if (kfTime <= targetTime) {
                    double distance = targetTime - kfTime;
                    if (distance < minDistance) {
                        minDistance = distance;
                        closestKeyframe = kfTime;
                    }
                }
            } else { // Find the first keyframe AFTER or AT the target time
                if (kfTime >= targetTime) {
                    double distance = kfTime - targetTime;
                    if (distance < minDistance) {
                        minDistance = distance;
                        closestKeyframe = kfTime;
                    }
                }
            }
        }
        return closestKeyframe == -1.0 ? targetTime : closestKeyframe;
    }
}