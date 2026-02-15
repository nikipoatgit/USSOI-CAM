package com.github.nikipo.ussoi.network.update;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public final class VersionChecker {

    public interface Callback {
        void onResult(String text);
    }

    public static void check(
            String currentVersion,
            Callback cb
    ) {
        new Thread(() -> {
            String result;

            try {
                String latestVersion = fetchLatestVersion();

                if (isNewerVersion(latestVersion, currentVersion)) {
                    result = "Update available: v" + latestVersion;
                } else {
                    result = "Version: v" + currentVersion;
                }

            } catch (Exception e) {
                result = "Version check failed";
            }

            cb.onResult(result);
        }).start();
    }

    private static String fetchLatestVersion() throws Exception {
        HttpURLConnection conn = null;

        try {
            URL url = new URL(
                    "https://api.github.com/repos/nikipoatgit/USSOI-CAM/releases/latest"
            );

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream())
            );

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();

            JSONObject json = new JSONObject(sb.toString());
            return json.getString("tag_name")
                    .replace("v", "")
                    .trim();

        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean isNewerVersion(String latest, String current) {
        String[] l = latest.split("\\.");
        String[] c = current.split("\\.");

        int len = Math.max(l.length, c.length);

        for (int i = 0; i < len; i++) {
            int lv = i < l.length ? Integer.parseInt(l[i]) : 0;
            int cv = i < c.length ? Integer.parseInt(c[i]) : 0;

            if (lv > cv) return true;
            if (lv < cv) return false;
        }
        return false;
    }
}
