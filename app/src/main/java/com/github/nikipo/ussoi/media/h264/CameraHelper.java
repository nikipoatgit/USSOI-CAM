package com.github.nikipo.ussoi.media.h264;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file CameraHelper
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


/**
 * Utility class for Camera2 device queries.
 *
 * Handles:
 *  - Camera ID cycling
 *  - Optimal FPS range selection
 *  - Best hardware-supported resolution selection
 *
 * All expensive system service calls are cached per camera ID.
 * Call {@link #invalidateCameraCache()} when a camera is released or switched.
 */
public class CameraHelper {

    private static final String TAG = "CameraHelper";

    /**
     * Aspect ratios within this tolerance are treated as equivalent.
     * Pixel count is used as a tiebreaker within the band.
     */
    private static final double ASPECT_EPSILON = 0.02;

    private final Context context;

    // -------------------------------------------------------------------------
    // Caches
    // -------------------------------------------------------------------------

    private String[] cachedCameraIdList = null;
    private final Map<String, CameraCharacteristics> characteristicsCache = new HashMap<>();
    private final Map<String, Size[]>                outputSizesCache      = new HashMap<>();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * @param context Application or Activity context used to access {@link CameraManager}.
     *                A reference is held for the lifetime of this object — prefer
     *                {@code context.getApplicationContext()} to avoid Activity leaks.
     */
    public CameraHelper(Context context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        this.context = context.getApplicationContext();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the next available camera ID, cycling through all cameras on the device.
     * Initialises to the first camera if no camera has been selected yet.
     *
     * @return The next camera ID, or {@code null} if no cameras are available.
     */
    public synchronized String cycleCameraId(String currentCameraId) {
        try {
            String[] idList = getCameraIdList();

            if (idList.length == 0) {
                Log.e(TAG, "No cameras found on device.");
                return null;
            }

            if (currentCameraId == null) {
                currentCameraId = idList[0];
                return currentCameraId;
            }

            int currentIndex = -1;
            for (int i = 0; i < idList.length; i++) {
                if (idList[i].equals(currentCameraId)) {
                    currentIndex = i;
                    break;
                }
            }

            if (currentIndex == -1) {
                Log.w(TAG, "Current camera ID not found in list, resetting to first.");
                currentCameraId = idList[0];
                return currentCameraId;
            }

            int nextIndex = (currentIndex + 1) % idList.length;
            Log.d(TAG, "Cycled camera: " + currentCameraId + " -> " + idList[nextIndex]);
            currentCameraId = idList[nextIndex];
            return currentCameraId;

        } catch (Exception e) {
            Log.e(TAG, "Failed to cycle camera ID", e);
            return currentCameraId;
        }
    }

    /**
     * Selects the optimal AE target FPS range for the given camera, resolution, and target FPS.
     *
     * <p>Prioritises:
     * <ol>
     *   <li>Fixed ranges (min == max) — avoids exposure flicker</li>
     *   <li>Upper bound matching the effective target exactly</li>
     *   <li>Upper bound as close as possible to the effective target</li>
     * </ol>
     *
     * @param cameraId  Camera ID to query.
     * @param width     Output width in pixels.
     * @param height    Output height in pixels.
     * @param targetFps Desired frame rate.
     * @return Best matching {@link Range}, or {@code [30, 30]} as a safe fallback.
     */
    public Range<Integer> getOptimalFpsRange(String cameraId, int width, int height, int targetFps) {
        final Range<Integer> fallback = new Range<>(30, 30);
        try {
            CameraCharacteristics chars = getCameraCharacteristics(cameraId);

            StreamConfigurationMap map =
                    chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Range<Integer>[] availableRanges =
                    chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

            if (map == null || availableRanges == null || availableRanges.length == 0) {
                Log.e(TAG, "FPS capabilities not available for camera: " + cameraId);
                return fallback;
            }

            // Determine the hardware FPS ceiling for this specific resolution
            long minDurationNs =
                    map.getOutputMinFrameDuration(SurfaceTexture.class, new Size(width, height));
            int maxHwFps = (minDurationNs > 0)
                    ? (int) (1_000_000_000.0 / minDurationNs)
                    : targetFps;

            int effectiveTarget = Math.min(targetFps, maxHwFps);

            Range<Integer> bestRange = null;
            int bestScore = Integer.MIN_VALUE;

            for (Range<Integer> r : availableRanges) {
                if (r.getUpper() > maxHwFps) continue; // physically unsupported

                int score = scoreRange(r.getLower(), r.getUpper(), effectiveTarget);
                if (score > bestScore) {
                    bestScore = score;
                    bestRange = r;
                }
            }

            // Last-resort fallback: range whose upper bound is closest to 30 fps
            if (bestRange == null) {
                bestRange = Arrays.stream(availableRanges)
                        .min(Comparator.comparingInt(r -> Math.abs(r.getUpper() - 30)))
                        .orElse(fallback);
            }

            Log.d(TAG, String.format(
                    "FPS [%dx%d @%d]: hwMax=%d, effective=%d, selected=%s",
                    width, height, targetFps, maxHwFps, effectiveTarget, bestRange));

            return bestRange;

        } catch (Exception e) {
            Log.e(TAG, "Error selecting optimal FPS range", e);
            return fallback;
        }
    }

    /**
     * Returns the best output size the hardware supports for the given camera and
     * requested dimensions.
     *
     * <p>Selection criteria (in priority order):
     * <ol>
     *   <li>Closest aspect ratio (within {@link #ASPECT_EPSILON})</li>
     *   <li>Closest total pixel count as a tiebreaker</li>
     * </ol>
     *
     * @param cameraId  Camera ID to query.
     * @param reqWidth  Desired width in pixels.
     * @param reqHeight Desired height in pixels.
     * @return Best matching {@link Size}, or {@code null} on error.
     */
    public Size getHardwareSupportedResolution(String cameraId, int reqWidth, int reqHeight) {
        if (cameraId == null) {
            Log.e(TAG, "cameraId is null");
            return null;
        }
        if (reqWidth <= 0 || reqHeight <= 0) {
            Log.e(TAG, "Invalid requested dimensions: " + reqWidth + "x" + reqHeight);
            return null;
        }

        try {
            Size[] sizes = getOutputSizes(cameraId);

            if (sizes == null || sizes.length == 0) {
                Log.e(TAG, "No output sizes available for camera: " + cameraId);
                return null;
            }

            final double reqAspect = (double) reqWidth / reqHeight;
            final long reqPixels   = (long)   reqWidth * reqHeight;

            Size   bestSize       = null;
            double bestAspectDiff = Double.MAX_VALUE;
            long   bestPixelDiff  = Long.MAX_VALUE;

            for (Size s : sizes) {
                double aspectDiff =
                        Math.abs((double) s.getWidth() / s.getHeight() - reqAspect);
                long pixelDiff =
                        Math.abs((long) s.getWidth() * s.getHeight() - reqPixels);

                boolean betterAspect = aspectDiff < bestAspectDiff - ASPECT_EPSILON;
                boolean sameAspect   = Math.abs(aspectDiff - bestAspectDiff) <= ASPECT_EPSILON;
                boolean betterPixels = pixelDiff < bestPixelDiff;

                if (betterAspect || (sameAspect && betterPixels)) {
                    bestAspectDiff = aspectDiff;
                    bestPixelDiff  = pixelDiff;
                    bestSize       = s;
                }
            }

            Log.d(TAG, String.format(
                    "Resolution [req=%dx%d (%.3f)] -> selected=%dx%d (%.3f)",
                    reqWidth, reqHeight, reqAspect,
                    bestSize != null ? bestSize.getWidth()  : 0,
                    bestSize != null ? bestSize.getHeight() : 0,
                    bestSize != null ? (double) bestSize.getWidth() / bestSize.getHeight() : 0.0));

            return bestSize;

        } catch (Exception e) {
            Log.e(TAG, "Exception in getHardwareSupportedResolution", e);
            return null;
        }
    }

    /**
     * Clears all cached camera data.
     * Must be called when a camera is released or the active camera changes,
     * to ensure stale characteristics are not reused.
     */
    public synchronized void invalidateCameraCache() {
        characteristicsCache.clear();
        outputSizesCache.clear();
        cachedCameraIdList = null;
        Log.d(TAG, "Camera cache invalidated.");
    }

    // -------------------------------------------------------------------------
    // Private helpers — caching layer
    // -------------------------------------------------------------------------

    private String[] getCameraIdList() throws CameraAccessException {
        if (cachedCameraIdList == null) {
            CameraManager manager =
                    (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            cachedCameraIdList = manager.getCameraIdList();
        }
        return cachedCameraIdList;
    }

    private CameraCharacteristics getCameraCharacteristics(String cameraId)
            throws CameraAccessException {
        if (!characteristicsCache.containsKey(cameraId)) {
            CameraManager manager =
                    (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            characteristicsCache.put(cameraId, manager.getCameraCharacteristics(cameraId));
        }
        return characteristicsCache.get(cameraId);
    }

    private Size[] getOutputSizes(String cameraId) throws CameraAccessException {
        if (!outputSizesCache.containsKey(cameraId)) {
            StreamConfigurationMap map = getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            outputSizesCache.put(cameraId,
                    (map != null) ? map.getOutputSizes(SurfaceTexture.class) : new Size[0]);
        }
        return outputSizesCache.get(cameraId);
    }

    // -------------------------------------------------------------------------
    // Private helpers — scoring
    // -------------------------------------------------------------------------

    /**
     * Scores a candidate FPS range against the effective target. Higher = better.
     *
     * @param min             Lower bound of the range.
     * @param max             Upper bound of the range.
     * @param effectiveTarget Hardware-clamped target FPS.
     * @return Integer score (relative, not absolute).
     */
    private int scoreRange(int min, int max, int effectiveTarget) {
        int score = 0;

        // Fixed range (min == max): stable exposure, no flicker
        if (min == max) score += 100;

        if (max == effectiveTarget) {
            score += 50;                            // exact match
        } else if (max < effectiveTarget) {
            score -= (effectiveTarget - max);       // under target: mild penalty
        } else {
            score -= (max - effectiveTarget) * 2;   // over target: stronger penalty
        }

        return score;
    }
}
