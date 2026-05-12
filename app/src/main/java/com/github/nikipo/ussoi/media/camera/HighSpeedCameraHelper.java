package com.github.nikipo.ussoi.media.camera;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file HighSpeedCameraHelper
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

/*
 *
 * High-speed counterpart to {@code CameraHelper} (h264 package).
 * All queries use {@link StreamConfigurationMap#getHighSpeedVideoSizes()} and
 * {@link StreamConfigurationMap#getHighSpeedVideoFpsRangesFor(Size)} rather than
 * the standard output-size / AE-range APIs, because those do not apply to
 * constrained high-speed sessions.
 *
 * All expensive system-service calls are cached per camera ID.
 * Call {@link #invalidateCameraCache()} when a camera is released or switched.
 */
public class HighSpeedCameraHelper {

    private static final String TAG = "HighSpeedCameraHelper";

    private final Context context;

    // Caches
    private String[]                                   cachedCameraIdList    = null;
    private final Map<String, CameraCharacteristics>   characteristicsCache  = new HashMap<>();
    private final Map<String, Size[]>                  highSpeedSizesCache   = new HashMap<>();

    public HighSpeedCameraHelper(Context context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        this.context = context.getApplicationContext();
    }

    /**
     * Returns the next camera ID that supports high-speed video, cycling through
     * all qualifying cameras. Initialises to the first qualifying camera if
     * {@code currentCameraId} is {@code null}.
     *
     * @return Next high-speed camera ID, or {@code null} if none available.
     */
    public synchronized String cycleCameraId(String currentCameraId) {
        try {
            List<String> ids = getHighSpeedCameraIdList();

            if (ids.isEmpty()) {
                Log.e(TAG, "No high-speed cameras found on device");
                return null;
            }

            if (currentCameraId == null) return ids.get(0);

            int index = ids.indexOf(currentCameraId);

            if (index < 0) {
                Log.w(TAG, "Current camera ID not found in high-speed list, resetting to first");
                return ids.get(0);
            }

            String next = ids.get((index + 1) % ids.size());
            Log.d(TAG, "Cycled high-speed camera: " + currentCameraId + " -> " + next);
            return next;

        } catch (Exception e) {
            Log.e(TAG, "cycleCameraId failed", e);
            return currentCameraId;
        }
    }

    /**
     * Selects the best high-speed size the hardware supports for the given
     * camera, requested dimensions, and target FPS.
     *
     * <p>A size is only considered if at least one of its high-speed FPS ranges
     * has an upper bound {@code >= targetFps}. Among qualifying sizes the one
     * with the smallest Manhattan distance to the requested dimensions wins.
     *
     * @param cameraId        Camera ID to query.
     * @param reqWidth        Desired width in pixels.
     * @param reqHeight       Desired height in pixels.
     * @param targetFps       Minimum required upper-bound FPS.
     * @return Best matching {@link Size}, or {@code null} if none qualifies.
     */
    public Size getBestHighSpeedSize(String cameraId, int reqWidth, int reqHeight, int targetFps) {
        if (cameraId == null || reqWidth <= 0 || reqHeight <= 0) {
            Log.e(TAG, "getBestHighSpeedSize: invalid arguments");
            return null;
        }
        try {
            Size[] sizes = getHighSpeedSizes(cameraId);
            if (sizes == null || sizes.length == 0) {
                Log.e(TAG, "No high-speed sizes available for camera: " + cameraId);
                return null;
            }

            StreamConfigurationMap map = getStreamConfigurationMap(cameraId);
            if (map == null) return null;

            Size bestSize  = null;
            int  bestScore = Integer.MIN_VALUE;

            for (Size size : sizes) {
                if (!supportsFps(map, size, targetFps)) continue;

                int score = -(Math.abs(size.getWidth()  - reqWidth)
                        + Math.abs(size.getHeight() - reqHeight));

                if (score > bestScore) {
                    bestScore = score;
                    bestSize  = size;
                }
            }

            Log.d(TAG, String.format(
                    "High-speed size [req=%dx%d @%dfps] -> selected=%s",
                    reqWidth, reqHeight, targetFps, bestSize));

            return bestSize;

        } catch (Exception e) {
            Log.e(TAG, "getBestHighSpeedSize failed", e);
            return null;
        }
    }

    /**
     * Selects the best high-speed FPS range for the given camera, size, and
     * target FPS.
     *
     * <p>Scoring (higher = better):
     * <ul>
     *   <li>+1000 fixed range (min == max) — stable exposure, no flicker</li>
     *   <li>+500  exact upper-bound match to target</li>
     *   <li>-diff penalty proportional to distance from target</li>
     * </ul>
     *
     * @param cameraId  Camera ID to query.
     * @param size      High-speed size (must be one returned by
     *                  {@link #getBestHighSpeedSize}).
     * @param targetFps Desired frame rate.
     * @return Best matching {@link Range}, or {@code [60, 60]} as a safe fallback.
     */
    public Range<Integer> getBestHighSpeedFpsRange(String cameraId, Size size, int targetFps) {
        final Range<Integer> fallback = new Range<>(60, 60);
        if (cameraId == null || size == null) return fallback;
        try {
            StreamConfigurationMap map = getStreamConfigurationMap(cameraId);
            if (map == null) return fallback;

            Range<Integer>[] fpsRanges = map.getHighSpeedVideoFpsRangesFor(size);
            if (fpsRanges == null || fpsRanges.length == 0) return fallback;

            Range<Integer> bestRange = null;
            int bestScore = Integer.MIN_VALUE;

            for (Range<Integer> range : fpsRanges) {
                int score = 0;
                if (range.getLower().equals(range.getUpper())) score += 1000;
                if (range.getUpper() == targetFps)              score += 500;
                else                                            score -= Math.abs(range.getUpper() - targetFps);

                if (score > bestScore) {
                    bestScore = score;
                    bestRange = range;
                }
            }

            if (bestRange == null) bestRange = fallback;

            Log.d(TAG, String.format(
                    "High-speed FPS [size=%dx%d, target=%d] -> selected=%s",
                    size.getWidth(), size.getHeight(), targetFps, bestRange));

            return bestRange;

        } catch (Exception e) {
            Log.e(TAG, "getBestHighSpeedFpsRange failed, using 60/60 fallback", e);
            return fallback;
        }
    }

    /**
     * Clears all cached camera data.
     * Must be called when a camera is released or the active camera changes.
     */
    public synchronized void invalidateCameraCache() {
        characteristicsCache.clear();
        highSpeedSizesCache.clear();
        cachedCameraIdList = null;
        Log.d(TAG, "High-speed camera cache invalidated");
    }

    // -------------------------------------------------------------------------
    // Private helpers — caching layer
    // -------------------------------------------------------------------------

    /**
     * Returns only cameras that advertise at least one high-speed video size.
     */
    private List<String> getHighSpeedCameraIdList() throws CameraAccessException {
        if (cachedCameraIdList != null) return List.of(cachedCameraIdList);

        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        List<String> result   = new ArrayList<>();

        for (String id : manager.getCameraIdList()) {
            Size[] sizes = getHighSpeedSizes(id);
            if (sizes != null && sizes.length > 0) result.add(id);
        }

        cachedCameraIdList = result.toArray(new String[0]);
        return result;
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

    private StreamConfigurationMap getStreamConfigurationMap(String cameraId)
            throws CameraAccessException {
        return getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    }

    private Size[] getHighSpeedSizes(String cameraId) throws CameraAccessException {
        if (!highSpeedSizesCache.containsKey(cameraId)) {
            StreamConfigurationMap map = getStreamConfigurationMap(cameraId);
            Size[] sizes = (map != null) ? map.getHighSpeedVideoSizes() : new Size[0];
            highSpeedSizesCache.put(cameraId, sizes != null ? sizes : new Size[0]);
        }
        return highSpeedSizesCache.get(cameraId);
    }

    private boolean supportsFps(StreamConfigurationMap map, Size size, int targetFps) {
        try {
            Range<Integer>[] ranges = map.getHighSpeedVideoFpsRangesFor(size);
            if (ranges == null) return false;
            for (Range<Integer> r : ranges) {
                if (r.getUpper() >= targetFps) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
