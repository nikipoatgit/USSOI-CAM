package com.github.nikipo.ussoi.media.h264;

import com.github.nikipo.ussoi.media.utility.Resolution;

/**
 * *****************************************************************************
 *
 * @author nikipo
 * *****************************************************************************
 * @file H264Config
 * @date 6/15/26 12:27 PM
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
public final class H264Config {
    public String cameraId ;
    public Resolution streamConfig =
            new Resolution();
    public Resolution recordingConfig =
            new Resolution();
}
