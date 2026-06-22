package com.github.nikipo.ussoi.tunnel;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file Tunnel
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
public interface Tunnel {
    void init();
    void close();
    void Start();
    void Stop();
    boolean isTunnelRunning();
    String getTunnelName();
}
