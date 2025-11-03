package com.example.videoc;

import static com.arthenica.ffmpegkit.FFmpegKitConfig.*;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.StreamInformation;
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
            if (executeCallback != null) {
                // Create a dummy failed session to signal completion with error
                executeCallback.apply(FFmpegSession.create(new String[]{}));
            }
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

        // Start processing the first segment. The callback will handle the next one.
        processSegmentsSequentially(context, videoSegmentArrayList, 0, tempDir, intermediateFiles, outputVideoPath, executeCallback);

    }

    private static void processSegmentsSequentially(
            Context context,
            ArrayList<VideoSegment> segments,
            int segmentIndex,
            File tempDir,
            List<String> intermediateFiles,
            String outputVideoPath,
            FFmpegSessionCompleteCallback finalCallback) {

        // Base case: If we've processed all segments, concatenate them.
        if (segmentIndex >= segments.size()) {
            concatenateParts(intermediateFiles, outputVideoPath, tempDir, finalCallback);
            return;
        }

        // Get the current segment to process.
        VideoSegment currentSegment = segments.get(segmentIndex);

        // Process the single segment.
        processSingleSegment(context, currentSegment, tempDir, segmentIndex, (processedParts) -> {
            // This is the callback for processSingleSegment.
            // It runs after the current segment is fully processed.

            intermediateFiles.addAll(processedParts);

            // Now, trigger the processing for the *next* segment.
            processSegmentsSequentially(context, segments, segmentIndex + 1, tempDir, intermediateFiles, outputVideoPath, finalCallback);
        });
    }

    // Interface for callback when a segment is done processing
    private interface SegmentProcessCallback {
        void onComplete(List<String> processedParts);
    }

    /**
     * Processes a single VideoSegment, splitting it into re-encoded and copied parts.
     */
    private static void processSingleSegment(Context context, VideoSegment segment, File tempDir, int segmentIndex, SegmentProcessCallback callback) {
        // --- STEP 1: Get the SAF-aware path for the URI ---
        // This must be done right before the FFprobe execution.
        String safeReadPath = FFmpegKitConfig.getSafParameterForRead(context, segment.getUri());

        if (safeReadPath == null) {
            Log.e(TAG, "Could not get a readable SAF path for URI: " + segment.getUri());
            callback.onComplete(new ArrayList<>());
            return;
        }

        double startTimeSec = segment.getClippingStart() / 1000.0;
        double endTimeSec = segment.getClippingEnd() / 1000.0;

        // --- STEP 2: Execute FFprobe to get all necessary information. ---
        String ffprobeCommand = String.format("-v quiet -print_format json -show_format -show_streams -show_frames -select_streams v:0 -show_entries frame=key_frame,pts_time -i %s", safeReadPath);

        FFprobeKit.executeAsync(ffprobeCommand, ffprobeSession -> {
            // --- STEP 3: The FFprobe is complete. Now, inside its callback, we process the result. ---
            String output = ffprobeSession.getOutput();

            if (TextUtils.isEmpty(output) || !ReturnCode.isSuccess(ffprobeSession.getReturnCode())) {
                Log.e(TAG, "FFprobe failed for: " + safeReadPath + ". RC: " + ffprobeSession.getReturnCode());
                Log.e(TAG, "FFprobe output: " + output);
                callback.onComplete(new ArrayList<>());
                return;
            }

            String durationStr = parseDuration(output);
            double durationSec = 0.0;
            if (durationStr != null) {
                durationSec = Double.parseDouble(durationStr);
            }

            // Parse codec information from the FFprobe output.
            String videoCodec = parseCodec(output, "video");
            String audioCodec = parseCodec(output, "audio");

            if (videoCodec == null) {
                Log.e(TAG, "No video stream/codec found in the file.");
                callback.onComplete(new ArrayList<>());
                return;
            }

            // Parse keyframe times from the FFprobe output.
            List<Double> keyframeTimes = parseKeyframeTimes(output);
            double startKeyframe = findNearestKeyframe(keyframeTimes, startTimeSec, false, durationSec);
            double endKeyframe = findNearestKeyframe(keyframeTimes, endTimeSec, true, durationSec);

            // --- STEP 4: Build the list of FFmpeg commands to execute for this segment. ---
            List<String> partsToProcess = new ArrayList<>();
            List<String> generatedParts = Collections.synchronizedList(new ArrayList<>());
            String reEncodeVideoCmd = String.format("-c:v %s -preset ultrafast", videoCodec);
            String reEncodeAudioCmd = (audioCodec != null) ? String.format("-c:a %s", audioCodec) : "-an";


            // We use a small tolerance (e.g., 0.1 seconds) to account for slight inaccuracies.
            boolean isLossless = (Math.abs(startTimeSec - startKeyframe) < 0.1 && Math.abs(endTimeSec - endKeyframe) < 0.1);

            if (isLossless) {
                Log.d(TAG, "Segment " + segmentIndex + " is a candidate for a single lossless copy.");
                String middlePartPath = new File(tempDir, String.format("part_%d_middle.ts", segmentIndex)).getAbsolutePath();
                String middlePath = FFmpegKitConfig.getSafParameterForRead(context, segment.getUri());
                //ADD THE h264_mp4toannexb BITSTREAM FILTER ---
                String cmd = String.format(Locale.US, "-y -ss %.3f -to %.3f -i %s -c copy -bsf:v h264_mp4toannexb -avoid_negative_ts 1 \"%s\"",
                        startTimeSec, endTimeSec, middlePath, middlePartPath);
                partsToProcess.add(cmd);
                generatedParts.add(middlePartPath);
            } else {


                // Build head, middle, and tail commands as before...
                if (startTimeSec < startKeyframe) {
                    String headPartPath = new File(tempDir, String.format("part_%d_head.ts", segmentIndex)).getAbsolutePath();
                    // IMPORTANT: We need a NEW SAF path for each command.
                    String headPath = FFmpegKitConfig.getSafParameterForRead(context, segment.getUri());
                    String cmd = String.format(Locale.US, "-y -i %s -ss %.3f -to %.3f %s %s \"%s\"",
                            headPath, startTimeSec, startKeyframe, reEncodeVideoCmd, reEncodeAudioCmd, headPartPath);
                    partsToProcess.add(cmd);
                    generatedParts.add(headPartPath);
                }
                if (startKeyframe < endKeyframe) {
                    String middlePartPath = new File(tempDir, String.format("part_%d_middle.ts", segmentIndex)).getAbsolutePath();
                    String middlePath = FFmpegKitConfig.getSafParameterForRead(context, segment.getUri());
                    //ADD THE h264_mp4toannexb BITSTREAM FILTER ---
                    String cmd = String.format(Locale.US, "-y -ss %.3f -to %.3f -i %s -c copy -bsf:v h264_mp4toannexb -avoid_negative_ts 1 \"%s\"",
                            startKeyframe, endKeyframe, middlePath, middlePartPath);
                    partsToProcess.add(cmd);
                    generatedParts.add(middlePartPath);
                }
                if (endTimeSec > endKeyframe && endKeyframe > startKeyframe) {
                    String tailPartPath = new File(tempDir, String.format("part_%d_tail.ts", segmentIndex)).getAbsolutePath();
                    String tailPath = FFmpegKitConfig.getSafParameterForRead(context, segment.getUri());
                    String cmd = String.format(Locale.US, "-y -i %s -ss %.3f -to %.3f %s %s \"%s\"",
                            tailPath, endKeyframe, endTimeSec, reEncodeVideoCmd, reEncodeAudioCmd, tailPartPath);
                    partsToProcess.add(cmd);
                    generatedParts.add(tailPartPath);
                }
                if (partsToProcess.isEmpty()) {
                    String singlePartPath = new File(tempDir, String.format("part_%d_single.ts", segmentIndex)).getAbsolutePath();
                    String singlePath = FFmpegKitConfig.getSafParameterForRead(context, segment.getUri());
                    String cmd = String.format(Locale.US, "-y -i %s -ss %.3f -to %.3f %s %s \"%s\"",
                            singlePath, startTimeSec, endTimeSec, reEncodeVideoCmd, reEncodeAudioCmd, singlePartPath);
                    partsToProcess.add(cmd);
                    generatedParts.add(singlePartPath);
                }
            }

            // --- STEP 5: Execute the FFmpeg commands for the parts sequentially. ---
            executePartsSequentially(partsToProcess, 0, generatedParts, callback);
        });
    }

    private static void executePartsSequentially(List<String> commands, int commandIndex, List<String> generatedParts, SegmentProcessCallback finalCallback) {
        // Base case: If we've executed all commands, we're done with this segment.
        if (commandIndex >= commands.size()) {
            finalCallback.onComplete(generatedParts);
            return;
        }

        String command = commands.get(commandIndex);
        FFmpegKit.executeAsync(command, session -> {
            if (!ReturnCode.isSuccess(session.getReturnCode())) {
                Log.e(TAG, String.format("Failed to process part! RC: %s. Command: %s", session.getReturnCode(), command));
                Log.e(TAG, "FFmpeg logs: " + session.getOutput());
                // Optional: Decide if you want to stop or continue on failure.
                // For now, we'll stop by not calling the next one.
                finalCallback.onComplete(new ArrayList<>()); // Signal failure with empty list
                return;
            }
            // Success, execute the next command in the chain.
            executePartsSequentially(commands, commandIndex + 1, generatedParts, finalCallback);
        });
    }

    private static String parseDuration(String ffprobeJson) {
        try {
            Gson gson = new Gson();
            Map<String, Object> result = gson.fromJson(ffprobeJson, new TypeToken<Map<String, Object>>() {}.getType());
            if (result.containsKey("format")) {
                Map<String, Object> format = (Map<String, Object>) result.get("format");
                if (format != null && format.containsKey("duration")) {
                    return (String) format.get("duration");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse duration from ffprobe JSON.", e);
        }
        return null;
    }


    // Helper to parse codec from JSON, add this method to your class
    private static String parseCodec(String ffprobeJson, String codecType) {
        try {
            Gson gson = new Gson();
            Map<String, Object> result = gson.fromJson(ffprobeJson, new TypeToken<Map<String, Object>>() {}.getType());
            if (result.containsKey("streams") && result.get("streams") instanceof List) {
                List<Map<String, Object>> streams = (List<Map<String, Object>>) result.get("streams");
                for (Map<String, Object> stream : streams) {
                    if (codecType.equals(stream.get("codec_type"))) {
                        return (String) stream.get("codec_name");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse codec '" + codecType + "' from ffprobe JSON.", e);
        }
        return null;
    }

    /**
     * Concatenates all the intermediate .ts files into the final output.
     */
    private static void concatenateParts(List<String> intermediateFiles, String outputVideoPath, File tempDir, FFmpegSessionCompleteCallback finalCallback) {
        if (intermediateFiles.isEmpty()) {
            Log.e(TAG, "No intermediate files to concatenate.");
            finalCallback.apply(FFmpegSession.create(new String[]{"-i", "invalid"})); // Create a failed session
            return;
        }
        File concatFile = new File(tempDir, "concat_list.txt");
        try (FileWriter writer = new FileWriter(concatFile)) {
            for (String filePath : intermediateFiles) {
                writer.write("file '" + filePath.replace("'", "'\\''") + "'\n");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to create concat file", e);
            finalCallback.apply(FFmpegSession.create(new String[]{"-i", "invalid"}));
            return;
        }

        String finalCommand = String.format("-y -f concat -safe 0 -i \"%s\" -c copy \"%s\"", concatFile.getAbsolutePath(), outputVideoPath);
        FFmpegKit.executeAsync(finalCommand, session -> {
            // Clean up temp files
            for (File file : tempDir.listFiles()) {
                file.delete();
            }
            tempDir.delete();
            finalCallback.apply(session);
        });
    }

    // --- Helper Methods ---

    private static List<Double> parseKeyframeTimes(String ffprobeJsonOutput) {
        List<Double> keyframeTimes = new ArrayList<>();
        if (TextUtils.isEmpty(ffprobeJsonOutput)) return keyframeTimes;

        try {
            Gson gson = new Gson();
            // Use a generic Map to handle the complex JSON structure
            Map<String, Object> result = gson.fromJson(ffprobeJsonOutput, new TypeToken<Map<String, Object>>() {}.getType());

            // Check if the 'frames' key exists and is a List
            if (result.containsKey("frames") && result.get("frames") instanceof List) {
                List<Map<String, Object>> frames = (List<Map<String, Object>>) result.get("frames");

                for (Map<String, Object> frame : frames) {
                    // Check for key_frame and pts_time, and handle different number types
                    if (frame.containsKey("key_frame") && "1".equals(String.valueOf(frame.get("key_frame"))) && frame.containsKey("pts_time")) {
                        Object ptsTimeObj = frame.get("pts_time");
                        if (ptsTimeObj instanceof String) {
                            keyframeTimes.add(Double.parseDouble((String) ptsTimeObj));
                        } else if (ptsTimeObj instanceof Double) {
                            keyframeTimes.add((Double) ptsTimeObj);
                        }
                    }
                }
            }
        } catch (JsonSyntaxException | NumberFormatException e) {
            Log.e(TAG, "Failed to parse ffprobe keyframe output.", e);
        }
        return keyframeTimes;
    }

    private static double findNearestKeyframe(List<Double> keyframeTimes, double targetTime, boolean findPrevious, double duration) {
        if (keyframeTimes.isEmpty()) {
            // If no keyframes, return 0 for previous, and the clip's duration for next.
            return findPrevious ? 0 : duration;
        }

        double closestKeyframe = -1.0;

        if (findPrevious) { // Find the last keyframe BEFORE or AT the target time
            for (double kfTime : keyframeTimes) {
                if (kfTime <= targetTime) {
                    closestKeyframe = Math.max(closestKeyframe, kfTime);
                }
            }
            // If no keyframe is found before the target, it must be 0.
            return closestKeyframe == -1.0 ? 0 : closestKeyframe;
        } else { // Find the first keyframe AFTER or AT the target time
            closestKeyframe = Double.MAX_VALUE;
            for (double kfTime : keyframeTimes) {
                if (kfTime >= targetTime) {
                    closestKeyframe = Math.min(closestKeyframe, kfTime);
                }
            }
            // --- THIS IS THE FIX ---
            // If no keyframe is found after the target, it means we are at the end.
            // Return the video's total duration instead of MAX_VALUE.
            return closestKeyframe == Double.MAX_VALUE ? duration : closestKeyframe;
        }
    }
}