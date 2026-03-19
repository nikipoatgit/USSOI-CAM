package com.github.nikipo.ussoi.media;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file CameraControl
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
public interface CameraControl {
    void setExposureCompensation(int value);
    void enableAutoExposure();

    void disableAutoExposure();
    void setManualExposure(long exposureTimeNs, int iso);

    void setManualFocus(float diopters);
    void enableAutoFocus();
    void apply();
}
