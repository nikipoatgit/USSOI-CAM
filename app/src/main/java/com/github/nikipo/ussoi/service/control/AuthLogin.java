package com.github.nikipo.ussoi.service.control;

import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_auth_api_path;

import android.util.Log;

import androidx.annotation.NonNull;

import com.github.nikipo.ussoi.storage.logs.Logging;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AuthLogin {
    private static final String TAG = "AuthLogin";
    private Logging logging;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client = new OkHttpClient();

    public interface LoginCallback {
        void onSuccess(String sessionKey,String deviceId);
        void onFailure(String error);
    }

    public void login(Logging logging,
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
                        callback.onFailure("Key HTTP " + response.code());
                        return;
                    }

                    try {
                        JSONObject keyJson = new JSONObject(response.body().string());

                        String publicKey = keyJson.getString("publicKey");
                        String deviceId = keyJson.optString("deviceId", "null");

                        JSONObject inner = new JSONObject();
                        inner.put("roomId", roomId);
                        inner.put("roomPwd", roomPwd);

                        String encrypted = encryptRSA(inner.toString(), publicKey);

                        JSONObject loginJson = new JSONObject();
                        loginJson.put("type", "login");
                        loginJson.put("deviceName", "dev1");
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
                                    callback.onFailure("Login HTTP " + response.code());
                                    return;
                                }

                                try {
                                    JSONObject resp = new JSONObject(response.body().string());

                                    if (!resp.has("deviceToken")) {
                                        callback.onFailure("Token missing");
                                        return;
                                    }

                                    String token = resp.getString("deviceToken");
                                    callback.onSuccess(token,deviceId);

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

    public static String encryptRSA(String plainText, String publicKeyStr) throws Exception {

        publicKeyStr = publicKeyStr
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = android.util.Base64.decode(publicKeyStr, android.util.Base64.DEFAULT);

        java.security.spec.X509EncodedKeySpec spec =
                new java.security.spec.X509EncodedKeySpec(keyBytes);

        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        java.security.PublicKey publicKey = kf.generatePublic(spec);

        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, publicKey);

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
        Log.d(TAG,"Auth URL used " + inputUrl);
        return inputUrl;
    }
}