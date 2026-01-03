package com.github.nikipo.ussoi.ServicesManager;

import android.util.Log;

import androidx.annotation.NonNull;

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
    private static final MediaType JSON
            = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client = new OkHttpClient();

    public interface LoginCallback {
        void onSuccess(String sessionKey);
        void onFailure(String error);
    }

    public void login(String roomId, String roomPwd,String LOGIN_URL, LoginCallback callback) {

        try {
            Log.d(TAG,"Auth in progress ");
            JSONObject json = new JSONObject();
            json.put("roomId", roomId);
            json.put("roomPwd", roomPwd);

            RequestBody body = RequestBody.create(json.toString(), JSON);

            Request request = new Request.Builder()
                    .url(normalizeUrl(LOGIN_URL))
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, IOException e) {
                    callback.onFailure(e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        Log.d(TAG,"HTTPS " + response.code() );
                        callback.onFailure("HTTPS " + response.code());
                        return;
                    }

                    String responseBody = response.body().string();
                    JSONObject respJson = null;
                    try {
                        respJson = new JSONObject(responseBody);
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }

                    if (!respJson.has("sessionKey")) {
                        Log.d(TAG,"Session key missing ");
                        callback.onFailure("Session key missing");
                        return;
                    }

                    String sessionKey = respJson.optString("sessionKey","null");
                    Log.d(TAG,"sessionKey Updated : " + sessionKey);
                    callback.onSuccess(sessionKey);
                }
            });

        } catch (Exception e) {
            callback.onFailure(e.getMessage());
        }
    }

    public static String normalizeUrl(String inputUrl) {
        if (inputUrl == null || inputUrl.isEmpty()) return "https://10.0.0.1";

        inputUrl = inputUrl.trim();

        if (!inputUrl.endsWith("/")) {
            inputUrl += "/";
        }
        inputUrl += "authentication/";
        Log.d(TAG,"Auth URL used " + inputUrl);
        return inputUrl;
    }
}