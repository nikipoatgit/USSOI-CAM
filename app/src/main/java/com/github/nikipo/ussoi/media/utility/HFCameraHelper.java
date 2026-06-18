package com.github.nikipo.ussoi.media.utility;

/**
 * *****************************************************************************
 *
 * @author nikipo
 * *****************************************************************************
 * @file HFCameraHelper
 * @date 6/17/26 9:15 AM
 * @attention Copyright (c) 2026
 * All rights reserved.
 * <p>
 * This software is licensed under the terms described in the LICENSE file
 * located in the root directory of this project.
 * If no LICENSE file is present, this software is provided "AS IS",
 * without warranty of any kind, express or implied.
 * <p>
 * *****************************************************************************
 */

import static com.github.nikipo.ussoi.media.utility.CameraHelper.getCameraCharacteristics;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Pair;
import android.util.Range;
import android.util.Size;

import java.util.ArrayList;
import java.util.List;

public class HFCameraHelper {
    private static final String TAG = "HFCameraHelper";

    public static Pair<Integer, Integer> frameRateScaler(
            int cameraFps,
            int targetFps) {

        if (cameraFps <= 0 || targetFps <= 0) {
            throw new IllegalArgumentException();
        }

        targetFps = Math.min(targetFps, cameraFps);

        int skip = Math.max(
                1,
                Math.round((float) cameraFps / targetFps)
        );

        int normalizedFps = cameraFps / skip;

        return new Pair<>(normalizedFps, skip);
    }

    public static Size getClosest360pEvenResolution(int srcWidth, int srcHeight) {
        // 1. Calculate the original aspect ratio
        double aspectRatio = (double) srcWidth / srcHeight;

        int targetHeight = 360;
        int targetWidth;

        // 2. Handle Landscape vs. Portrait orientations
        if (srcWidth >= srcHeight) {
            // Landscape (e.g., 4K 3840x2160) -> Scale height to 360
            targetWidth = (int) Math.round(targetHeight * aspectRatio);
        } else {
            // Portrait (e.g., Phone 2160x3840) -> Scale width to 360
            targetWidth = 360;
            targetHeight = (int) Math.round(targetWidth / aspectRatio);
        }

        // 3. Force both dimensions to be the nearest multiple of 2
        // Math bitwise trick: (val + 1) & ~1 rounds to the nearest even number
        int finalWidth = (targetWidth + 1) & ~1;
        int finalHeight = (targetHeight + 1) & ~1;

        return new Size(finalWidth, finalHeight);
    }

    public static Range<Integer> getOptimalHFpsRange(
            Context context,
            String cameraId,
            int width,
            int height,
            int fps) {

        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return null;

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return null;

            Size targetSize = new Size(width, height);

            // Only consider ranges for the resolution actually being requested
            Range<Integer>[] fpsRanges = map.getHighSpeedVideoFpsRangesFor(targetSize);
            if (fpsRanges == null) return null;

            Range<Integer> best = null;

            for (Range<Integer> range : fpsRanges) {
                // fps must be ACHIEVABLE within this range, not equal to the upper bound
                if (range.getLower() <= fps && range.getUpper() >= fps) {
                    // Prefer a fixed range (lower == upper == fps) if available,
                    // otherwise prefer the tightest/highest-lower-bound match
                    if (best == null || range.getLower() > best.getLower()) {
                        best = range;
                    }
                }
            }

            return best;

        } catch (CameraAccessException | IllegalArgumentException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static boolean checkForExactHFSupportedResolution(
            Context context,
            String cameraId,
            int width,
            int height,
            Range<Integer> fpsRange
    ) {

        if (cameraId == null ||
                width <= 0 ||
                height <= 0 ||
                fpsRange == null ||
                fpsRange.getLower() <= 0 ||
                fpsRange.getUpper() <= 0 ||
                fpsRange.getLower() > fpsRange.getUpper()) {
            return false;
        }

        try {

            CameraCharacteristics chars =
                    getCameraCharacteristics(context, cameraId);

            StreamConfigurationMap map =
                    chars.get(CameraCharacteristics
                            .SCALER_STREAM_CONFIGURATION_MAP);

            if (map == null) {
                return false;
            }

            Size targetSize = new Size(width, height);

            boolean resolutionSupported = false;

            for (Size size : map.getHighSpeedVideoSizes()) {

                if (size.equals(targetSize)) {
                    resolutionSupported = true;
                    break;
                }
            }

            if (!resolutionSupported) {
                return false;
            }

            Range<Integer>[] ranges =
                    map.getHighSpeedVideoFpsRangesFor(targetSize);

            if (ranges == null || ranges.length == 0) {
                return false;
            }

            for (Range<Integer> range : ranges) {

                if (range.getLower() <= fpsRange.getLower() &&
                        range.getUpper() >= fpsRange.getUpper()) {
                    return true;
                }
            }

            return false;

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "checkForExactHFSupportedResolution failed",
                    e
            );
            throw  new IllegalStateException("Check For HF Res failed");
        }
    }
    public static String[] getHFpsCameraId(Context context) {
        List<String> result = new ArrayList<>();

        try {
            CameraManager manager =
                    (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            for (String cameraId : manager.getCameraIdList()) {

                CameraCharacteristics chars =
                        manager.getCameraCharacteristics(cameraId);

                StreamConfigurationMap map =
                        chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                if (map == null) {
                    continue;
                }

                if (map.getHighSpeedVideoSizes().length > 0) {
                    result.add(cameraId);
                }
            }
        } catch (Exception ignored) {
        }

        return result.toArray(new String[0]);
    }

    /**
     * Checks if the camera device supports Constrained High Speed video capture.
     */
    public static boolean isHighSpeedSupported(Context context, String cameraId) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (capabilities != null) {
                for (int cap : capabilities) {
                    if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) {
                        return true;
                    }
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to inspect camera capabilities", e);
        }
        return false;
    }


    public static Range<Integer> findBestHighSpeedRange(Context context, String cameraId, Size size, int targetFps) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (map == null) return new Range<>(60, 60);

            Range<Integer>[] ranges = map.getHighSpeedVideoFpsRangesFor(size);
            if (ranges == null || ranges.length == 0) {
                return new Range<>(60, 60);
            }

            Range<Integer> bestRange = null;
            int bestScore = Integer.MIN_VALUE;

            for (Range<Integer> range : ranges) {
                int score = 0;
                // Favor fixed-rate ranges (e.g. [120, 120]) over variable configurations
                if (range.getLower().equals(range.getUpper())) score += 1000;
                if (range.getUpper() == targetFps)             score += 500;
                else                                            score -= Math.abs(range.getUpper() - targetFps);

                if (score > bestScore) {
                    bestScore = score;
                    bestRange = range;
                }
            }
            return bestRange != null ? bestRange : new Range<>(60, 60);
        } catch (Exception e) {
            Log.e(TAG, "findBestHighSpeedRange failed, using 60/60 fallback", e);
            return new Range<>(60, 60);
        }
    }
}
