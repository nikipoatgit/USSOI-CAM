package com.github.nikipo.ussoi.media.camera;

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
 * <p>
 * This software is licensed under the terms described in the LICENSE file
 * located in the root directory of this project.
 * If no LICENSE file is present, this software is provided "AS IS",
 * without warranty of any kind, express or implied.
 * <p>
 * *****************************************************************************
 */


public class CameraHelper {
    private static final String TAG = "CameraHelper";
    private static final double ASPECT_EPSILON = 0.02;
    private final Context context;

    private String[] cachedCameraIdList = null;
    private final Map<String, CameraCharacteristics> characteristicsCache = new HashMap<>();
    private final Map<String, Size[]>                outputSizesCache      = new HashMap<>();

    public CameraHelper(Context context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        this.context = context.getApplicationContext();
    }
    
    public String[] getCameraIdList() throws CameraAccessException {
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

    public static JSONObject buildAllCamerasJson(CameraManager cameraManager) throws Exception {

        JSONObject root = new JSONObject();
        JSONArray camerasArray = new JSONArray();

        for (String cameraId : cameraManager.getCameraIdList()) {

            CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(cameraId);

            StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (map == null) continue;

            // Normal sizes (example: SurfaceTexture)
            Size[] normalSizes = map.getOutputSizes(SurfaceTexture.class);

            // High-speed sizes
            Size[] highSpeedSizes = map.getHighSpeedVideoSizes();

            JSONObject camObj = new JSONObject();
            camObj.put("cameraId", cameraId);

            // ---------- NORMAL ----------
            JSONArray normalArray = new JSONArray();

            Range<Integer>[] normalRanges =
                    characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

            if (normalSizes != null) {
                for (Size s : normalSizes) {
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

            // ---------- HIGH SPEED ----------
            JSONArray highSpeedArray = new JSONArray();

            if (highSpeedSizes != null) {
                for (Size s : highSpeedSizes) {
                    JSONObject resObj = new JSONObject();
                    resObj.put("width", s.getWidth());
                    resObj.put("height", s.getHeight());

                    JSONArray fpsArray = new JSONArray();

                    Range<Integer>[] ranges = map.getHighSpeedVideoFpsRangesFor(s);

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


    public boolean checkForExactSupportedResolution( String cameraId, int width,int height,int fps) {

        if (cameraId == null || width <= 0 || height <= 0 || fps <= 0) {
            return false;
        }

        try {
            CameraCharacteristics chars = getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (map == null) return false;

            Size[] sizes = getOutputSizes(cameraId);
            boolean resolutionSupported = false;

            for (Size s : sizes) {
                if (s.getWidth() == width && s.getHeight() == height) {
                    resolutionSupported = true;
                    break;
                }
            }

            if (!resolutionSupported) {
                return false;
            }

            Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

            boolean fpsSupported = false;

            if (ranges != null) {
                for (Range<Integer> r : ranges) {
                    if (r.getLower() <= fps && r.getUpper() >= fps) {
                        fpsSupported = true;
                        break;
                    }
                }
            }

            if (!fpsSupported) {
                return false;
            }

            long minFrameDurationNs = map.getOutputMinFrameDuration(SurfaceTexture.class, new Size(width, height));

            if (minFrameDurationNs > 0) {
                int maxFps = (int) (1_000_000_000L / minFrameDurationNs);

                return fps <= maxFps;
            }

            return true;

        } catch (Exception e) {
            Log.e(TAG, "checkForExactSupportedResolution failed", e);
            return false;
        }
    }

    public Range<Integer> getOptimalFpsRange(String cameraId, int targetFps) {
        final Range<Integer> fallback = new Range<>(30, 30);
        try {
            CameraCharacteristics chars = getCameraCharacteristics(cameraId);
            Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

            if (ranges == null || ranges.length == 0) return fallback;

            for (Range<Integer> r : ranges)if (r.getLower() == targetFps && r.getUpper() == targetFps) return r;

            for (Range<Integer> r : ranges)
                if (r.getUpper() == targetFps) return r;


            return Arrays.stream(ranges).min(Comparator.comparingInt(r -> Math.abs(r.getUpper() - targetFps))).orElse(fallback);

        } catch (Exception e) {
            return fallback;
        }
    }}
