package com.github.nikipo.ussoi.service;

import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_auth_api_path;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_device_name;

import android.util.Log;
import androidx.annotation.NonNull;
import com.github.nikipo.ussoi.storage.logs.Logging;
import org.json.JSONObject;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file AuthLogin
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
public class AuthLogin {

    private static final String TAG = "AuthLogin";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client = new OkHttpClient();

    public interface LoginCallback {
        void onSuccess(String sessionKey, String deviceId);
        void onFailure(String error);
    }

    public void login(Logging logging,
                      String deviceName,
                      String roomId,
                      String roomPwd,
                      String LOGIN_URL,
                      LoginCallback callback) {

        try {
            String url = normalizeUrl(LOGIN_URL);

            JSONObject keyReq = new JSONObject();
            keyReq.put("type", "getKey");

            Request keyRequest = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(keyReq.toString(), JSON))
                    .build();

            client.newCall(keyRequest).enqueue(new Callback() {

                @Override
                public void onFailure(@NonNull Call call, IOException e) {
                    callback.onFailure("Key fetch failed: " + e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {

                    if (!response.isSuccessful()) {
                        callback.onFailure("Key HTTP " + response.code() +" " + response.body().string());
                        return;
                    }

                    try {
                        JSONObject keyJson = new JSONObject(response.body().string());

                        if (!keyJson.has("publicKey") ||
                                !keyJson.has("deviceId") ||
                                !keyJson.has("challenge")) {

                            callback.onFailure("Invalid key response");
                            return;
                        }

                        String publicKey = keyJson.getString("publicKey");
                        String deviceId = keyJson.getString("deviceId");
                        String challenge = keyJson.getString("challenge");

                        // --- payload ---
                        JSONObject inner = new JSONObject();
                        inner.put("roomId", roomId);
                        inner.put("roomPwd", roomPwd);
                        inner.put("challenge", challenge);
                        inner.put("timestamp", System.currentTimeMillis());

                        String encrypted = encryptRSA(inner.toString(), publicKey);

                        JSONObject loginJson = new JSONObject();
                        loginJson.put("type", "login");
                        loginJson.put("deviceName",deviceName);
                        loginJson.put("deviceId", deviceId);
                        loginJson.put("data", encrypted);

                        Request loginRequest = new Request.Builder()
                                .url(url)
                                .post(RequestBody.create(loginJson.toString(), JSON))
                                .build();

                        client.newCall(loginRequest).enqueue(new Callback() {

                            @Override
                            public void onFailure(@NonNull Call call, IOException e) {
                                callback.onFailure("Login failed: " + e.getMessage());
                            }

                            @Override
                            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {

                                if (!response.isSuccessful()) {
                                    callback.onFailure("Login HTTP " + response.code() + " " +response.body().string());
                                    return;
                                }

                                try {
                                    JSONObject resp = new JSONObject(response.body().string());

                                    if (!resp.has("deviceToken")) {
                                        callback.onFailure("Token missing");
                                        return;
                                    }

                                    String token = resp.getString("deviceToken");
                                    callback.onSuccess(token, deviceId);

                                } catch (Exception e) {
                                    callback.onFailure("Invalid login response");
                                }
                            }
                        });

                    } catch (Exception e) {
                        callback.onFailure("Key parse error");
                    }
                }
            });

        } catch (Exception e) {
            callback.onFailure(e.getMessage());
        }
    }

    // --- RSA OAEP ENCRYPT ---
    public static String encryptRSA(String plainText, String publicKeyStr) throws Exception {

        publicKeyStr = publicKeyStr
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = android.util.Base64.decode(publicKeyStr, android.util.Base64.DEFAULT);

        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PublicKey publicKey = kf.generatePublic(spec);

        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");

        OAEPParameterSpec oaepParams = new OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT
        );

        cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepParams);

        byte[] encrypted = cipher.doFinal(plainText.getBytes("UTF-8"));

        return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP);
    }

    public static String normalizeUrl(String inputUrl) {
        if (inputUrl == null || inputUrl.isEmpty()) return "https://10.0.0.1";

        inputUrl = inputUrl.trim();

        if (!inputUrl.endsWith("/")) {
            inputUrl += "/";
        }
        inputUrl += KEY_auth_api_path;

        Log.d(TAG, "Auth URL used " + inputUrl);
        return inputUrl;
    }
}
