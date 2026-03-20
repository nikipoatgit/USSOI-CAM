package com.github.nikipo.ussoi.service.control.MessageRouter;

import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_BT_SWITCH;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_USB_Switch;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.github.nikipo.ussoi.service.control.ConnectionManager;
import com.github.nikipo.ussoi.storage.SaveInputFields;
import com.github.nikipo.ussoi.tunnel.bt.BluetoothHandler;
import com.github.nikipo.ussoi.tunnel.Tunnel;
import com.github.nikipo.ussoi.tunnel.usb.UsbHandler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file TunnelRouter
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
public class TunnelRoute {

    private static final String TAG = "TunnelRoute";
    private Context context;
    private Router router;
    private SharedPreferences preferences;
    private ConnectionManager connectionManager;
    // HW BT AND USB
    private boolean useBT;
    private boolean useUSB;
    public final List<BluetoothDevice> selectedBtDevices;
    private List<Tunnel> tunnels = new ArrayList<>();

    public TunnelRoute(ConnectionManager connectionManager,Router router,Context ctx){
        this.context = ctx;
        this.router = router;
        this.connectionManager = connectionManager;
        preferences = SaveInputFields.getInstance(ctx).get_shared_pref();
        selectedBtDevices = SaveInputFields.selectedBtDevices;
        useBT = preferences.getBoolean(KEY_BT_SWITCH,false);
        useUSB = preferences.getBoolean(KEY_USB_Switch,false);

        if (useBT) {
            initBtTunnels(ctx);
            Log.d(TAG, "Tunnel created: BluetoothHandler");
        } else if (useUSB) {
            tunnels.add(UsbHandler.getInstance(ctx));   // UsbHandler is a singleton itself
            Log.d(TAG, "Tunnel created: UsbHandler");
        } else {
            Log.w(TAG, "No tunnel type selected in preferences");
        }
    }

    private void initBtTunnels(Context ctx) {
        for (BluetoothDevice bt : selectedBtDevices) {
            BluetoothHandler handler = new BluetoothHandler(ctx);
            handler.setDevice(bt);
            tunnels.add(handler);
        }
    }

    public void route(JSONObject json) {
        try {
            String cmd   = json.optString("cmd", "");
            String cmdId = json.optString("cmdId", "");
            JSONObject payload = json.optJSONObject("payload");

            switch (cmd) {

                case "start_tunnel":
                    if (startTunnel(payload != null ? payload.optInt("tunnelId", -1) : -1)) {
                        router.sendAck(connectionManager, cmdId, "Tunnel Started");
                    } else {
                        router.sendNack(connectionManager, cmdId, "Tunnel Issue / not selected");
                    }
                    break;

                case "stop_tunnel":
                    if (stopTunnel( payload != null ? payload.optInt("tunnelId", -1) : -1)) {
                        router.sendAck(connectionManager, cmdId, "Tunnel Stopped");
                    } else {
                        router.sendNack(connectionManager, cmdId, "Tunnel Issue / not selected");
                    }
                    break;

                case "get_tunnels": {

                    JSONObject res = new JSONObject();
                    res.put("type", "data");
                    res.put("cmd", "tunnels");
                    res.put("cmdId", cmdId);
                    res.put("timestamp", System.currentTimeMillis());

                    JSONArray tunnelsArray = new JSONArray();
                    if (tunnels != null) {
                        for (Tunnel t : tunnels) {
                            if (t != null && t.getTunnelName() != null) {
                                tunnelsArray.put(t.getTunnelName());
                            }
                        }
                    }

                    JSONObject payload1 = new JSONObject();
                    payload1.put("tunnels", tunnelsArray);
                    res.put("payload", payload1);

                    connectionManager.send(res);
//                    {
//                        "type": "data",
//                            "cmd": "tunnels",
//                            "cmdId": "c9012",
//                            "timestamp": 1710000001,
//                            "payload": {
//                        "tunnels": ["tunn1","tunn2","tunn3"]
//                    }
                    break;
                }

                default:
                    router.sendNack(connectionManager, cmdId, cmd);
                    break;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean startTunnel(int tunnelId) {
        if (tunnels == null || tunnelId < 0 || tunnelId >= tunnels.size()) {
            return false;
        }

        Tunnel t = tunnels.get(tunnelId);
        if (t == null) return false;

        try {
            t.init();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean stopTunnel(int tunnelId) {
        if (tunnels == null || tunnelId < 0 || tunnelId >= tunnels.size()) {
            return false;
        }

        Tunnel t = tunnels.get(tunnelId);
        if (t == null) return false;

        try {
            t.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }


}
