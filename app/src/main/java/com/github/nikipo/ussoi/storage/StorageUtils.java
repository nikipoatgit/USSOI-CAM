package com.github.nikipo.ussoi.storage;

import java.net.IDN;

/**
 * *****************************************************************************
 *
 * @author nikipo
 * *****************************************************************************
 * @file StorageUtils
 * @date 5/29/26 10:22 AM
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
public class StorageUtils {
    private static final String TAG = "StorageUtils";

    public static String normaliseUrl(String url) {

        if (url == null) {
            return null;
        }

        url = url.trim();

        if (url.isEmpty()) {
            return "";
        }

        // Remove accidental spaces inside
        url = url.replace(" ", "");

        // Fix common slash mistakes
        url = url.replace('\\', '/');

        // Fix malformed protocol variants
        url = url.replaceFirst("^(?i)https:/([^/])", "https://$1");
        url = url.replaceFirst("^(?i)http:/([^/])", "http://$1");

        // Remove duplicate protocol slashes
        url = url.replaceFirst("^(?i)(https?:)//+", "$1//");

        // Add protocol if missing
        if (!url.matches("^(?i)https?://.*")) {

            // protocol-relative URL
            if (url.startsWith("//")) {
                url = "https:" + url;
            } else {
                url = "https://" + url;
            }
        }

        // Convert unicode domains safely
        try {

            int protoEnd = url.indexOf("://");

            if (protoEnd > 0) {

                String protocol = url.substring(0, protoEnd + 3);
                String remaining = url.substring(protoEnd + 3);

                int slashIndex = remaining.indexOf('/');

                String host;
                String path;

                if (slashIndex >= 0) {
                    host = remaining.substring(0, slashIndex);
                    path = remaining.substring(slashIndex);
                } else {
                    host = remaining;
                    path = "";
                }

                // Remove accidental trailing dots
                host = host.replaceAll("\\.+$", "");

                // Convert IDN safely
                host = IDN.toASCII(host);

                url = protocol + host + path;
            }

        } catch (Exception ignored) {
        }

        return url;
    }
}
