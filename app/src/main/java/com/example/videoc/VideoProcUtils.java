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
            // If processing a segment fails, processedParts will be empty. We should stop.
            if (processedParts.isEmpty()) {
                Log.e(TAG, "Processing failed for segment " + segmentIndex + ". Aborting concatenation.");
                // Clean up and signal failure
                concatenateParts(new ArrayList<>(), outputVideoPath, tempDir, finalCallback);
                return;
            }
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
        String ffprobeCommand = String.format(Locale.US, "-v quiet -print_format json -show_format -show_streams -show_frames -select_streams v:0 -show_entries frame=key_frame,pts_time,pict_type -read_intervals %.3f%%+%.3f -i %s",
                startTimeSec, (endTimeSec - startTimeSec), safeReadPath);

        FFprobeKit.executeAsync(ffprobeCommand, ffprobeSession -> {
            // --- STEP 3: The FFprobe is complete. Now, inside its callback, we process the result. ---
            String output = ffprobeSession.getOutput();

            if (TextUtils.isEmpty(output) || !ReturnCode.isSuccess(ffprobeSession.getReturnCode())) {
                Log.e(TAG, "FFprobe failed for: " + safeReadPath + ". RC: " + ffprobeSession.getReturnCode());
                Log.e(TAG, "FFprobe output: " + output);
                callback.onComplete(new ArrayList<>());
                return;
            }

            List<FrameInfo> allFrames = parseFrameInfo(output);
            if (allFrames.isEmpty()) {
                Log.e(TAG, "Could not parse any frame information. Re-encoding whole segment.");
                return;
            }

            double copyableStart = -1; // The time we can start a lossless copy
            double copyableEnd = -1;   // The time we can end a lossless copy

            // Find the first I-frame at or after our start time
            for (FrameInfo frame : allFrames) {
                if ("I".equals(frame.pict_type) && frame.pts_time >= startTimeSec) {
                    copyableStart = frame.pts_time;
                    break;
                }
            }

            // Find the last I-frame or P-frame at or before our end time
            for (int i = allFrames.size() - 1; i >= 0; i--) {
                FrameInfo frame = allFrames.get(i);
                if (("I".equals(frame.pict_type) || "P".equals(frame.pict_type)) && frame.pts_time <= endTimeSec) {
                    copyableEnd = frame.pts_time;
                    break;
                }
            }

            // Parse codec information from the FFprobe output.
            String videoCodec = parseCodec(output, "video");
            String audioCodec = parseCodec(output, "audio");

            if (videoCodec == null) {
                Log.e(TAG, "No video stream/codec found in the file.");
                callback.onComplete(new ArrayList<>());
                return;
            }



            // --- STEP 4: Build the list of FFmpeg commands to execute for this segment. ---
            List<String> partsToProcess = new ArrayList<>();
            List<String> generatedParts = Collections.synchronizedList(new ArrayList<>());
            String reEncodeVideoCmd = String.format("-c:v %s -preset normal", videoCodec);
            String reEncodeAudioCmd = (audioCodec != null) ? String.format("-c:a %s", audioCodec) : "-an";


            // A small tolerance for floating point inaccuracies.
            final double tolerance = 0.1;
            boolean isPerfectLossless = (copyableStart != -1 && copyableEnd != -1 &&
                    Math.abs(startTimeSec - copyableStart) < tolerance &&
                    Math.abs(endTimeSec - copyableEnd) < tolerance);

            if (isPerfectLossless) {
                // --- PERFECT CASE: The segment aligns with I-frames, use a single copy command. ---
                Log.d(TAG, "Segment " + segmentIndex + " is a perfect lossless candidate. Using single copy.");
                String singleCopyPartPath = new File(tempDir, String.format("part_%d_single_copy.ts", segmentIndex)).getAbsolutePath();
                String singleCopyPath = FFmpegKitConfig.getSafParameterForRead(context, segment.getUri());
                String cmd = String.format(Locale.US, "-y -ss %.3f -to %.3f -i %s -c copy -bsf:v h264_mp4toannexb -avoid_negative_ts make_zero \"%s\"",
                        startTimeSec, endTimeSec, singleCopyPath, singleCopyPartPath);
                partsToProcess.add(cmd);
                generatedParts.add(singleCopyPartPath);

            } else {
                // --- COMPLEX CASE: Fallback to the head/middle/tail logic. ---
                Log.d(TAG, "Segment " + segmentIndex + " is not a perfect candidate. Using head/middle/tail logic.");

                // Part 1: The "Head" (re-encode if start is not copyable)
                if (copyableStart == -1 || startTimeSec < copyableStart) {
                    double headEndTime = (copyableStart != -1) ? copyableStart : endTimeSec;
                    String headPartPath = new File(tempDir, String.format("part_%d_head.ts", segmentIndex)).getAbsolutePath();
                    String headPath = FFmpegKitConfig.getSafParameterForRead(context, segment.getUri());
                    String cmd = String.format(Locale.US, "-y -i %s -ss %.3f -to %.3f %s %s -bsf:v h264_mp4toannexb -avoid_negative_ts make_zero \"%s\"",
                            headPath, startTimeSec, headEndTime, reEncodeVideoCmd, reEncodeAudioCmd, headPartPath);
                    partsToProcess.add(cmd);
                    generatedParts.add(headPartPath);
                }

                // Part 2: The "Middle" (lossless copy between I-frame and last I/P-frame)
                if (copyableStart != -1 && copyableEnd != -1 && copyableEnd > copyableStart) {
                    String middlePartPath = new File(tempDir, String.format("part_%d_middle.ts", segmentIndex)).getAbsolutePath();
                    String middlePath = FFmpegKitConfig.getSafParameterForRead(context, segment.getUri());
                    String cmd = String.format(Locale.US, "-y -ss %.3f -to %.3f -i %s -c copy -bsf:v h264_mp4toannexb -avoid_negative_ts make_zero \"%s\"",
                            copyableStart, copyableEnd, middlePath, middlePartPath);
                    partsToProcess.add(cmd);
                    generatedParts.add(middlePartPath);
                }

                // Part 3: The "Tail" (re-encode if end is not copyable)
                if (copyableEnd != -1 && endTimeSec > copyableEnd) {
                    String tailPartPath = new File(tempDir, String.format("part_%d_tail.ts", segmentIndex)).getAbsolutePath();
                    String tailPath = FFmpegKitConfig.getSafParameterForRead(context, segment.getUri());
                    String cmd = String.format(Locale.US, "-y -i %s -ss %.3f -to %.3f %s %s -bsf:v h264_mp4toannexb -avoid_negative_ts make_zero \"%s\"",
                            tailPath, copyableEnd, endTimeSec, reEncodeVideoCmd, reEncodeAudioCmd, tailPartPath);
                    partsToProcess.add(cmd);
                    generatedParts.add(tailPartPath);
                }
            }

            // Fallback: If for any reason no parts were generated, re-encode the whole thing.
            if (partsToProcess.isEmpty()) {
                Log.w(TAG, "Logic resulted in no parts for segment " + segmentIndex + ". Re-encoding entire segment as fallback.");
                String singlePartPath = new File(tempDir, String.format("part_%d_single_reencode.ts", segmentIndex)).getAbsolutePath();
                String singlePath = FFmpegKitConfig.getSafParameterForRead(context, segment.getUri());
                String cmd = String.format(Locale.US, "-y -i %s -ss %.3f -to %.3f %s %s -bsf:v h264_mp4toannexb -avoid_negative_ts make_zero \"%s\"",
                        singlePath, startTimeSec, endTimeSec, reEncodeVideoCmd, reEncodeAudioCmd, singlePartPath);
                partsToProcess.add(cmd);
                generatedParts.add(singlePartPath);
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
            cleanupTempFiles(tempDir);
            finalCallback.apply(FFmpegSession.create(new String[]{"-i", "invalid"})); // Create a failed session
            return;
        }

        StringBuilder concatInput = new StringBuilder("concat:");
        for (int i = 0; i < intermediateFiles.size(); i++) {
            // IMPORTANT: The file paths must be escaped for the FFmpeg command line.
            // We are not writing to a file anymore, so we don't need to worry about single quotes in paths here.
            concatInput.append(intermediateFiles.get(i));
            if (i < intermediateFiles.size() - 1) {
                concatInput.append("|");
            }
        }

        // --- NEW: The final command using the concat protocol ---
        // We no longer need the concat_list.txt file or the -f concat -safe 0 flags.
        String finalCommand = String.format("-y -i \"%s\" -c copy -movflags +faststart \"%s\"", concatInput.toString(), outputVideoPath);

        Log.d(TAG, "Executing final concatenation command: " + finalCommand);

        FFmpegKit.executeAsync(finalCommand, session -> {
            // Clean up temp files
            // We also need to delete the temp directory itself.

//            // --- NEW: Create the debug video with frame numbers ---
//            Log.d(TAG, "Main concatenation successful. Now creating debug video with frame numbers.");
//
//            // Construct the path for the debug output file.
//            String debugOutputVideoPath = outputVideoPath.replace(".mp4", "_debug_frames.mp4");
//
//            // The drawtext filter requires a font file. FFmpeg on Android doesn't have a default path.
//            // A common, safe font to use is DroidSansMono, which is usually available.
//            String fontPath = "/system/fonts/DroidSansMono.ttf";
//            String drawtextFilter = String.format("drawtext=fontfile=%s:text='%%{frame_num}':x=10:y=10:fontsize=48:fontcolor=white@0.8:box=1:boxcolor=black@0.5", fontPath);
//
//            String debugCommand = String.format("-y -i \"%s\" -vf \"%s\" -c:v libx264 -preset ultrafast -c:a copy -movflags +faststart \"%s\"",
//                    outputVideoPath, // Input is the successfully concatenated video
//                    drawtextFilter,
//                    debugOutputVideoPath);
//
//
//            Log.d(TAG, "Executing debug frame-number command: " + debugCommand);
//
//            // Execute the debug command synchronously within the callback for simplicity.
//            FFmpegSession debugSession = FFmpegKit.execute(debugCommand);
//            if (!ReturnCode.isSuccess(debugSession.getReturnCode())) {
//                Log.e(TAG, "Failed to create debug video. RC: " + debugSession.getReturnCode());
//                Log.e(TAG, "FFmpeg logs: " + debugSession.getOutput());
//            } else {
//                Log.d(TAG, "Successfully created debug video at: " + debugOutputVideoPath);
//            }
//            //end

            finalCallback.apply(session);

            if (!ReturnCode.isSuccess(session.getReturnCode())) {
                Log.e(TAG, "Final concatenation failed! RC: " + session.getReturnCode());
                Log.e(TAG, "FFmpeg logs: " + session.getOutput());

            }

            cleanupTempFiles(tempDir);

        });
    }



    // --- Helper Methods ---

    private static void cleanupTempFiles(File tempDir) {
        if (tempDir != null && tempDir.exists()) {
            for (File file : tempDir.listFiles()) {
                file.delete();
            }
            tempDir.delete();
        }
    }

    private static class FrameInfo {
        double pts_time;
        String pict_type; // "I", "P", or "B"

        public FrameInfo(double pts_time, String pict_type) {
            this.pts_time = pts_time;
            this.pict_type = pict_type;
        }
    }
    private static List<FrameInfo> parseFrameInfo(String ffprobeJson) {
        List<FrameInfo> frameInfoList = new ArrayList<>();
        try {
            Gson gson = new Gson();
            Map<String, Object> result = gson.fromJson(ffprobeJson, new TypeToken<Map<String, Object>>() {}.getType());

            if (result.containsKey("frames")) {
                List<Map<String, Object>> frames = (List<Map<String, Object>>) result.get("frames");
                if (frames != null) {
                    for (Map<String, Object> frame : frames) {
                        if (frame != null && frame.containsKey("pts_time") && frame.containsKey("pict_type")) {
                            double time = Double.parseDouble(String.valueOf(frame.get("pts_time")));
                            String type = (String) frame.get("pict_type");
                            frameInfoList.add(new FrameInfo(time, type));
                        }
                    }
                }
            }
        } catch (JsonSyntaxException | ClassCastException | NumberFormatException e) {
            Log.e(TAG, "Failed to parse frame info from FFprobe JSON", e);
        }
        return frameInfoList;
    }
}