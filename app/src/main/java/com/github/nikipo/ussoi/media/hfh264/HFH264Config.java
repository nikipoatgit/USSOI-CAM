package com.github.nikipo.ussoi.media.hfh264;

import android.util.Range;
import android.util.Size;

/**
 * *****************************************************************************
 *
 * @author nikipo
 * *****************************************************************************
 * @file HFH264Config
 * @date 6/17/26 6:17 PM
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
public class HFH264Config {
    public Size res;
    public Range<Integer> fpsRange;
    public int streamFps;
    public String cameraId;
    public int hqBitrate;
    public int streamJpegQuality;
}
