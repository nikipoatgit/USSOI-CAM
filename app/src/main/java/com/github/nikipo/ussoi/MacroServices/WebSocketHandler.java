package com.github.nikipo.ussoi.MacroServices;

import static com.github.nikipo.ussoi.MacroServices.SaveInputFields.KEY_url;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class WebSocketHandler {
    private static final String TAG = "ConnHandle";
    private static final int MAX_RECONNECT_ATTEMPTS = 5;

    // Use a Main Looper handler for UI thread callbacks
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client;
    private final MessageCallback callback;
    private final Logging logging;

    // Volatile state
    private WebSocket webSocket;
    private boolean isManualClose = false;
    private volatile boolean isConnected = false;
    private int reconnectAttempts = 0;

    // Dependencies
    private SharedPreferences prefs;
    private String currentUrlPath;
    private String currentSessionKey;

    public interface MessageCallback {
        void onOpen();
        void onPayloadReceivedText(String payload);
        void onPayloadReceivedByte(byte[] payload);
        void onClosed();
        void onError(String error);
    }

    public WebSocketHandler(Context context, MessageCallback callback) {
        // Initialize SharedPrefs once here to avoid holding Context unnecessarily
        SaveInputFields saveInputFields = SaveInputFields.getInstance(context.getApplicationContext());
        this.prefs = saveInputFields.get_shared_pref();
        this.callback = callback;

        // Setup OkHttp with timeouts
        this.client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS) // WebSockets should generally have 0 read timeout (keep alive)
                .pingInterval(30, TimeUnit.SECONDS)    // Keep connection alive
                .build();

        this.logging = Logging.getIfInitialized();
    }

    public void setupConnection(String urlPath, String sessionKey) {
        // Reset state for new connection
        this.currentUrlPath = urlPath;
        this.currentSessionKey = sessionKey;
        this.isManualClose = false;

        connect();
    }

    private void connect() {
        if (webSocket != null) {
            // Cancel existing connection attempts if any
            webSocket.cancel();
        }

        String baseUrl = prefs.getString(KEY_url, "10.0.0.1");
        String wsUrl = normalizeUrl(baseUrl) + currentUrlPath.trim();

        Log.d(TAG, "Connecting to: " + wsUrl);

        Request request = new Request.Builder()
                .url(wsUrl)
                .addHeader("Authorization", "Bearer " + currentSessionKey)
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                isConnected = true;
                reconnectAttempts = 0; // Reset counter on success
                Log.d(TAG, "WebSocket Opened");

                // Switch to Main Thread for callback
                mainHandler.post(callback::onOpen);
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                // Switch to Main Thread for callback
                mainHandler.post(() -> callback.onPayloadReceivedText(text));
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
                byte[] byteArray = bytes.toByteArray();
                mainHandler.post(() -> callback.onPayloadReceivedByte(byteArray));
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                isConnected = false;
                Log.e(TAG, "WebSocket Failure: " + t.getMessage());

                if (logging != null) {
                    logging.log("WS Error: " + t.getMessage());
                }

                // Notify UI of error
                mainHandler.post(() -> callback.onError(t.getMessage()));

                // Reconnect logic
                if (!isManualClose) {
                    initiateReconnect();
                }
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                isConnected = false;
                Log.d(TAG, "WebSocket closed: " + reason);
                if (logging != null) {
                    logging.log("WS Closed: " + reason);
                }
                mainHandler.post(callback::onClosed);
            }
        });
    }

    private void initiateReconnect() {
        // Exponential backoff or simple limit
        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++;
            long delay = 3000L * reconnectAttempts; // Wait longer each time (3s, 6s, 9s...)

            Log.d(TAG, "Reconnecting attempt " + reconnectAttempts + " in " + delay + "ms");

            mainHandler.postDelayed(this::connect, delay);
        } else {
            Log.e(TAG, "Max reconnection attempts reached.");
            mainHandler.post(() -> callback.onError("Max reconnection attempts reached"));
        }
    }

    public static String normalizeUrl(String inputUrl) {
        if (inputUrl == null || inputUrl.isEmpty()) return "ws://10.0.0.1/";

        inputUrl = inputUrl.trim();

        // Ensure protocol is present
        if (inputUrl.startsWith("https://")) {
            inputUrl = "wss://" + inputUrl.substring(8);
        } else if (inputUrl.startsWith("http://")) {
            inputUrl = "ws://" + inputUrl.substring(7);
        } else if (!inputUrl.startsWith("ws://") && !inputUrl.startsWith("wss://")) {
            // Default to wss if no protocol specified
            inputUrl = "wss://" + inputUrl;
        }

        // Ensure trailing slash for concatenation safety
        if (!inputUrl.endsWith("/")) {
            inputUrl += "/";
        }

        return inputUrl;
    }

    public void connSendPayload(JSONObject payload) {
        if (webSocket != null && isConnected) {
            try {
                webSocket.send(payload.toString());
            } catch (Exception e) {
                Log.e(TAG, "Failed to send JSON: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "Cannot send JSON: WebSocket is not connected.");
        }
    }

    public void connSendPayloadBytes(byte[] serialBytesReceived) {
        if (webSocket != null && isConnected) {
            try {
                webSocket.send(ByteString.of(serialBytesReceived));
            } catch (Exception e) {
                Log.e(TAG, "Failed to send BYTES: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "Cannot send Bytes: WebSocket is not connected.");
        }
    }

    public void closeConnection() {
        isManualClose = true;
        if (webSocket != null) {
            webSocket.close(1000, "Closing manually");
        }
    }

    public boolean isConnected() {
        return isConnected;
    }
}