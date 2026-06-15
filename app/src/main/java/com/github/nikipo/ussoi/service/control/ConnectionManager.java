package com.github.nikipo.ussoi.service.control;

import static com.github.nikipo.ussoi.ui.UssoiStrings.TELEMETRY_INTERVAL;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.github.nikipo.ussoi.service.control.MessageRouter.Router;
import com.github.nikipo.ussoi.storage.logs.Logging;
import com.github.nikipo.ussoi.storage.SaveInputFields;
import com.github.nikipo.ussoi.network.Webscoket.WebSocketHandler;
import com.github.nikipo.ussoi.system.telemetry.SysTelemetry;
import com.github.nikipo.ussoi.ui.UssoiStrings;

import org.json.JSONException;
import org.json.JSONObject;

public class ConnectionManager {
    private static final String TAG = "ControlConnectionManager";
    private final Context context;
    private final String wsUrl;
    private static volatile ConnectionManager instance;
    private ImpClientInfoSender impClientInfoSender;
    private WebSocketHandler webSocketHandler;
    private SysTelemetry sysTelemetry;
    private SharedPreferences prefs;
    private SaveInputFields saveInputFields;
    private Logging logger;
    private Router router;
    static final String KEY_Session_KEY = "sessionKey";

    private ConnectionManager(Context ctx, String url) {
        this.context = ctx.getApplicationContext();
        this.wsUrl = url;

        logger = Logging.getInstance(context);
        saveInputFields = SaveInputFields.getInstance(ctx);
        this.prefs = saveInputFields.get_shared_pref();

        router= new Router(this,context);
    }

    public static ConnectionManager getInstance(Context ctx, String url){
        if (instance == null){
           instance = new ConnectionManager(ctx,url);
        }
        return instance;
    }


    public WebSocketHandler getWebSocketHandlerObject() {
        return webSocketHandler;
    }

    public void connect() {

        // create info  object
        sysTelemetry = SysTelemetry.getInstance(context);

        new Handler(Looper.getMainLooper()).post(() -> {
            sysTelemetry.startMonitoring();
        });

        webSocketHandler = new WebSocketHandler(context,new WebSocketHandler.MessageCallback() {
            @Override
            public void onOpen() {
                logger.log(TAG + ": WS Connected");
                if (impClientInfoSender == null) {
                    impClientInfoSender = new ImpClientInfoSender(webSocketHandler, sysTelemetry, ConnectionManager.this,logger);
                }
                impClientInfoSender.startSending();
            }

            @Override
            public void onPayloadReceivedText(String payload) {
                try {
                    JSONObject json = new JSONObject(payload);
                    router.route(json);
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

    private char getStatusTelem() {
        return router.getStatusTelem();
    }

    public void stopAllServices() {
        if (impClientInfoSender != null) {
            impClientInfoSender.stopSending();
            impClientInfoSender = null;
        }

        if (webSocketHandler != null) {
            webSocketHandler.close();
            webSocketHandler = null;
        }

        if (sysTelemetry != null) {
            sysTelemetry.stopMonitoring();
        }

        router.stop();
    }

    // issue if
    private static class ImpClientInfoSender {
        private final WebSocketHandler webSocketHandler;
        private final SysTelemetry telemetry;
        private final ConnectionManager sender;
        private volatile boolean running = false;
        private Thread worker;
        private Logging logger;

        ImpClientInfoSender(WebSocketHandler handler, SysTelemetry provider, ConnectionManager connManager, Logging logger) {
            this.webSocketHandler = handler;
            this.telemetry = provider;
            this.sender = connManager;
            this.logger = logger;
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
                            Thread.sleep(UssoiStrings.TELEMETRY_INTERVAL);
                            continue;
                        }

                        // Build combined status object
                        JSONObject obj = new JSONObject();
                        obj.put("type", "telem");
                        obj.put("cmd", "telem");
                        obj.put("hex", telemetry.getPacket() + sender.getStatusTelem());

                        Log.d(TAG, "Telemetry TX = " + obj);

                        sender.send(obj);
                        Thread.sleep(TELEMETRY_INTERVAL);

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