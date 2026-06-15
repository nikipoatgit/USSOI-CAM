package com.github.nikipo.ussoi.media.utility;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONObject;

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
 *
 * *****************************************************************************
 */
public final class CameraHelper {

    private static final String TAG = "CameraHelper";
    private static final double ASPECT_EPSILON = 0.03;

    private static String[] cachedCameraIdList;

    private static final Map<String, CameraCharacteristics>characteristicsCache = new HashMap<>();

    private static final Map<String, Size[]>outputSizesCache = new HashMap<>();

    private CameraHelper() {
    }

    public static String[] getCameraIdList(Context context)
            throws CameraAccessException {

        if (cachedCameraIdList == null) {

            CameraManager manager =
                    (CameraManager) context.getSystemService(
                            Context.CAMERA_SERVICE);

            cachedCameraIdList = manager.getCameraIdList();
        }

        return cachedCameraIdList;
    }

    public static CameraCharacteristics getCameraCharacteristics(
            Context context,
            String cameraId
    ) throws CameraAccessException {

        CameraCharacteristics chars =
                characteristicsCache.get(cameraId);

        if (chars == null) {

            CameraManager manager =
                    (CameraManager) context.getSystemService(
                            Context.CAMERA_SERVICE);

            chars = manager.getCameraCharacteristics(cameraId);

            characteristicsCache.put(cameraId, chars);
        }

        return chars;
    }

    public static Size[] getOutputSizes(
            Context context,
            String cameraId
    ) throws CameraAccessException {

        Size[] sizes = outputSizesCache.get(cameraId);

        if (sizes == null) {

            StreamConfigurationMap map =
                    getCameraCharacteristics(context, cameraId)
                            .get(CameraCharacteristics
                                    .SCALER_STREAM_CONFIGURATION_MAP);

            sizes = (map != null)
                    ? map.getOutputSizes(SurfaceTexture.class)
                    : new Size[0];

            outputSizesCache.put(cameraId, sizes);
        }

        return sizes;
    }

    public static JSONObject buildAllCamerasJson(
            CameraManager cameraManager
    ) throws Exception {

        JSONObject root = new JSONObject();
        JSONArray camerasArray = new JSONArray();

        for (String cameraId : cameraManager.getCameraIdList()) {

            CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(cameraId);

            StreamConfigurationMap map =
                    characteristics.get(
                            CameraCharacteristics
                                    .SCALER_STREAM_CONFIGURATION_MAP);

            if (map == null) {
                continue;
            }

            Size[] normalSizes =
                    map.getOutputSizes(SurfaceTexture.class);

            Size[] highSpeedSizes =
                    map.getHighSpeedVideoSizes();

            JSONObject camObj = new JSONObject();

            camObj.put("cameraId", cameraId);

            JSONArray normalArray = new JSONArray();

            Range<Integer>[] normalRanges =
                    characteristics.get(
                            CameraCharacteristics
                                    .CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

            double nativeAspect = 0;

            if (normalSizes != null && normalSizes.length > 0) {

                Size largest = normalSizes[0];

                for (Size s : normalSizes) {

                    if ((long) s.getWidth() * s.getHeight()
                            >
                            (long) largest.getWidth() * largest.getHeight()) {

                        largest = s;
                    }
                }

                nativeAspect =
                        (double) largest.getWidth()
                                / largest.getHeight();
            }

            if (normalSizes != null) {

                for (Size s : normalSizes) {

                    if (s.getWidth() > 2048 ||
                            s.getHeight() > 2048) {
                        continue;
                    }

                    if (nativeAspect > 0) {

                        double aspect =
                                (double) s.getWidth()
                                        / s.getHeight();

                        if (Math.abs(aspect - nativeAspect)
                                > ASPECT_EPSILON) {
                            continue;
                        }
                    }

                    JSONObject resObj = new JSONObject();

                    resObj.put("width", s.getWidth());
                    resObj.put("height", s.getHeight());

                    JSONArray fpsArray = new JSONArray();

                    if (normalRanges != null) {

                        for (Range<Integer> r : normalRanges) {

                            JSONObject fps = new JSONObject();

                            fps.put("min", r.getLower());
                            fps.put("max", r.getUpper());

                            fpsArray.put(fps);
                        }
                    }

                    resObj.put("fpsRanges", fpsArray);

                    normalArray.put(resObj);
                }
            }

            JSONArray highSpeedArray = new JSONArray();

            if (highSpeedSizes != null) {

                for (Size s : highSpeedSizes) {

                    JSONObject resObj = new JSONObject();

                    resObj.put("width", s.getWidth());
                    resObj.put("height", s.getHeight());

                    JSONArray fpsArray = new JSONArray();

                    Range<Integer>[] ranges =
                            map.getHighSpeedVideoFpsRangesFor(s);

                    if (ranges != null) {

                        for (Range<Integer> r : ranges) {

                            JSONObject fps = new JSONObject();

                            fps.put("min", r.getLower());
                            fps.put("max", r.getUpper());

                            fpsArray.put(fps);
                        }
                    }

                    resObj.put("fpsRanges", fpsArray);

                    highSpeedArray.put(resObj);
                }
            }

            camObj.put("normal", normalArray);
            camObj.put("high_speed", highSpeedArray);

            camerasArray.put(camObj);
        }

        root.put("cameras", camerasArray);

        return root;
    }

    public static boolean checkForExactSupportedResolution(
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

            Size[] sizes =
                    getOutputSizes(context, cameraId);

            boolean resolutionSupported = false;

            for (Size s : sizes) {

                if (s.getWidth() == width &&
                        s.getHeight() == height) {

                    resolutionSupported = true;
                    break;
                }
            }

            if (!resolutionSupported) {
                return false;
            }

            Range<Integer>[] ranges =
                    chars.get(CameraCharacteristics
                            .CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

            boolean fpsSupported = false;

            if (ranges != null) {

                for (Range<Integer> r : ranges) {

                    if (r.getLower() <= fpsRange.getLower() &&
                            r.getUpper() >= fpsRange.getUpper()) {

                        fpsSupported = true;
                        break;
                    }
                }
            }

            if (!fpsSupported) {
                return false;
            }

            long minFrameDurationNs =
                    map.getOutputMinFrameDuration(
                            SurfaceTexture.class,
                            new Size(width, height)
                    );

            if (minFrameDurationNs > 0) {

                int maxFps =
                        (int) (1_000_000_000L /
                                minFrameDurationNs);

                return fpsRange.getUpper() <= maxFps;
            }

            return true;

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "checkForExactSupportedResolution failed",
                    e
            );

            return false;
        }
    }

    public static Range<Integer> getOptimalFpsRange(
            Context context,
            String cameraId,
            int targetFps
    ) {

        Range<Integer> fallback =
                new Range<>(30, 30);

        try {

            CameraCharacteristics chars =
                    getCameraCharacteristics(
                            context,
                            cameraId
                    );

            Range<Integer>[] ranges =
                    chars.get(CameraCharacteristics
                            .CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

            if (ranges == null ||
                    ranges.length == 0) {
                return fallback;
            }

            for (Range<Integer> r : ranges) {

                if (r.getLower() == targetFps &&
                        r.getUpper() == targetFps) {

                    return r;
                }
            }

            for (Range<Integer> r : ranges) {

                if (r.getUpper() == targetFps) {
                    return r;
                }
            }

            return Arrays.stream(ranges)
                    .min(Comparator.comparingInt(
                            r -> Math.abs(
                                    r.getUpper() - targetFps)))
                    .orElse(fallback);

        } catch (Exception e) {

            Log.e(TAG, "getOptimalFpsRange failed", e);

            return fallback;
        }
    }

    public static void clearCache() {

        cachedCameraIdList = null;

        characteristicsCache.clear();

        outputSizesCache.clear();
    }
}