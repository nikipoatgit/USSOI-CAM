package com.github.nikipo.ussoi.ServicesManager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.github.nikipo.ussoi.MacroServices.ClientInfoProvider;
import com.github.nikipo.ussoi.MacroServices.Logging;
import com.github.nikipo.ussoi.MacroServices.SaveInputFields;
import com.github.nikipo.ussoi.MacroServices.WebSocketHandler;

import org.json.JSONException;
import org.json.JSONObject;

public class ConnManager {
    private static final String TAG = "ControlConnectionManager";
    private final Context context;
    private final String wsUrl;

    // REMOVED: ConnRouter connRouter instance (now using static calls)

    private ImpClientInfoSender impClientInfoSender;
    private WebSocketHandler webSocketHandler;
    private ClientInfoProvider clientInfoProvider;
    private SharedPreferences prefs;
    private SaveInputFields saveInputFields;
    private static Logging logger;
    static final String KEY_Session_KEY = "sessionKey";

    public ConnManager(Context ctx, String url) {
        this.context = ctx.getApplicationContext();
        this.wsUrl = url;

        ConnRouter.init(this, ctx);
        logger = Logging.getInstance(context);
        saveInputFields = SaveInputFields.getInstance(ctx);
        this.prefs = saveInputFields.get_shared_pref();
    }

    public WebSocketHandler getWebSocketHandlerObject() {
        return webSocketHandler;
    }

    public void connect() {
        clientInfoProvider = ClientInfoProvider.getInstance(context);
        new Handler(Looper.getMainLooper()).post(() -> {
            clientInfoProvider.startMonitoring();
        });

        webSocketHandler = new WebSocketHandler(context,new WebSocketHandler.MessageCallback() {
            @Override
            public void onOpen() {
                logger.log(TAG + ": WS Connected");
                if (impClientInfoSender == null) {
                    impClientInfoSender = new ImpClientInfoSender(webSocketHandler, clientInfoProvider,ConnManager.this);
                }
                impClientInfoSender.startSending();
            }

            @Override
            public void onPayloadReceivedText(String payload) {
                try {
                    JSONObject json = new JSONObject(payload);
                    ConnRouter.route(json);
                    logger.log(TAG+" INCOMING :"+ json);
                } catch (Exception e) {
                    logger.log(TAG + ": Bad JSON");
                    Log.e(TAG, "Bad JSON", e);
                }
            }

            @Override
            public void onPayloadReceivedByte(byte[] payload) {}

            @Override
            public void onClosed() {
                logger.log(TAG + ": WS Closed");
                if (impClientInfoSender != null) {
                    impClientInfoSender.stopSending();
                }
            }

            @Override
            public void onError(String error) {
                logger.log(TAG + ": WS Error" + error);
            }
        });
        webSocketHandler.setupConnection(wsUrl, prefs.getString(KEY_Session_KEY, "block"));
    }

    public void send(JSONObject obj) {
        if (webSocketHandler != null) {
            logger.log(TAG+" OUTGOING :"+ obj);
            webSocketHandler.connSendPayload(obj);
        }
    }

    public void stopAllServices() {
        if (impClientInfoSender != null) {
            impClientInfoSender.stopSending();
            impClientInfoSender = null;
        }

        if (webSocketHandler != null) {
            webSocketHandler.closeConnection();
            webSocketHandler = null;
        }

        if (clientInfoProvider != null) {
            clientInfoProvider.stopMonitoring();
        }

        ConnRouter.stopAllServices();
    }

    // issue if
    private static class ImpClientInfoSender {
        private final WebSocketHandler webSocketHandler;
        private final ClientInfoProvider clientInfoProvider;
        private final ConnManager sender;
        private volatile boolean running = false;
        private Thread worker;

        ImpClientInfoSender(WebSocketHandler handler, ClientInfoProvider provider,ConnManager connManager) {
            this.webSocketHandler = handler;
            this.clientInfoProvider = provider;
            this.sender = connManager;
        }

        public synchronized void startSending() {
            if (running) return;
            running = true;

            worker = new Thread(() -> {
                while (running) {
                    try {
                        // Safe reference capturing
                        WebSocketHandler handler = webSocketHandler;

                        if (handler == null || !handler.isConnected()) {
                            Thread.sleep(1000);
                            continue;
                        }

                        // Build combined status object
                        JSONObject obj = new JSONObject();
                        obj.put("type", "clientStats");
                        obj.put("hex", clientInfoProvider.ImportantClientInfoConstructor()+ ConnRouter.getClientStat());

                        sender.send(obj);
                        Thread.sleep(5000);

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (JSONException e) {
                        logger.log(TAG + ": Client stats send failed" + e);
                    }
                }
            }, "ClientInfoSender");

            worker.start();
        }

        public synchronized void stopSending() {
            running = false;
            if (worker != null) {
                worker.interrupt();
                worker = null;
            }
        }
    }
}