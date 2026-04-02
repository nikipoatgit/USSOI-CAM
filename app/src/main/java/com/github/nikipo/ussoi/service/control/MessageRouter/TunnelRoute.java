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
public class TunnelRoute  {

    private static final String TAG = "TunnelRoute";
    private final Context context;
    private final Router router;
    private final SharedPreferences preferences;
    private final ConnectionManager connectionManager;

    private final boolean useBT;
    private final boolean useUSB;
    public final List<BluetoothDevice> selectedBtDevices;
    private final List<Tunnel> tunnels = new ArrayList<>();

    public TunnelRoute(ConnectionManager connectionManager, Router router, Context ctx) {
        this.context = ctx;
        this.router = router;
        this.connectionManager = connectionManager;
        preferences = SaveInputFields.getInstance(ctx).get_shared_pref();
        selectedBtDevices = SaveInputFields.selectedBtDevices;
        useBT  = preferences.getBoolean(KEY_BT_SWITCH, false);
        useUSB = preferences.getBoolean(KEY_USB_Switch, false);

        if (useBT) {
            initBtTunnels(ctx);
            Log.d(TAG, "Tunnel created: BluetoothHandler");
        } else if (useUSB) {
            tunnels.add(UsbHandler.getInstance(ctx));
            Log.d(TAG, "Tunnel created: UsbHandler");
        } else {
            // No-op tunnel placeholder so the list is never empty
            tunnels.add(new Tunnel() {
                @Override public void init()               {}
                @Override public void close()              {}
                @Override public boolean isTunnelRunning() { return false; }
                @Override public String getTunnelName()    { return ""; }
            });
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

    // ─── Command dispatcher ───────────────────────────────────────────────────

    /**
     * Routes an incoming tunnel command.
     *
     * start_tunnel / stop_tunnel expect: { ..., "tunnelName":"<name>" }
     * get_tunnels  returns an ACK with the list of all tunnel names.
     *
     * ACK  : { "type":"ack",  "cmd":"<cmd>", "cmdId":"<uuid>" }
     * NACK : { "type":"nack", "cmd":"<cmd>", "cmdId":"<uuid>", "error":"<reason>" }
     * Data : { "type":"ack",  "cmd":"get_tunnels", "cmdId":"<uuid>",
     *           "tunnels":["name1","name2",...] }
     */
    public void route(JSONObject json) {
        String cmd        = json.optString("cmd",        "");
        String cmdId      = json.optString("cmdId",      "");
        String tunnelName = json.optString("tunnelName", "");   // top-level field, not inside payload

        try {
            switch (cmd) {

                case "start_tunnel": {
                    if (startTunnel(tunnelName)) {
                        router.sendAck(connectionManager, cmdId, cmd);
                    } else {
                        router.sendNack(connectionManager, cmdId, cmd,
                                "Tunnel '" + tunnelName + "' not found or failed to start");
                    }
                    break;
                }

                case "stop_tunnel": {
                    if (stopTunnel(tunnelName)) {
                        router.sendAck(connectionManager, cmdId, cmd);
                    } else {
                        router.sendNack(connectionManager, cmdId, cmd,
                                "Tunnel '" + tunnelName + "' not found or failed to stop");
                    }
                    break;
                }

                case "get_tunnels": {
                    // Build array of tunnel names
                    JSONArray tunnelsArray = new JSONArray();
                    for (Tunnel t : tunnels) {
                        if (t != null) {
                            String name = t.getTunnelName();
                            if (name != null && !name.isEmpty()) {
                                tunnelsArray.put(name);
                            }
                        }
                    }

                    // ACK format expected by server's processGetTunnels handler:
                    // { "type":"ack", "cmd":"get_tunnels", "cmdId":"...", "tunnels":["t1","t2"] }
                    JSONObject res = new JSONObject();
                    res.put("type",    "ack");
                    res.put("cmd",     "get_tunnels");
                    res.put("cmdId",   cmdId);
                    res.put("tunnels", tunnelsArray);
                    connectionManager.send(res);
                    break;
                }

                default:
                    router.sendNack(connectionManager, cmdId, cmd, "Unknown tunnel command: " + cmd);
                    break;
            }

        } catch (Exception e) {
            router.sendNack(connectionManager, cmdId, cmd, "Internal error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ─── Tunnel lookup ────────────────────────────────────────────────────────

    /**
     * Finds a tunnel by its name string (matching getTunnelName()).
     * Returns null if no match is found.
     */
    private Tunnel findTunnel(String name) {
        if (name == null || name.isEmpty()) return null;
        for (Tunnel t : tunnels) {
            if (t != null && name.equals(t.getTunnelName())) return t;
        }
        return null;
    }

    private boolean startTunnel(String tunnelName) {
        Tunnel t = findTunnel(tunnelName);
        if (t == null) return false;
        try {
            t.init();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "startTunnel failed: " + tunnelName, e);
            return false;
        }
    }

    private boolean stopTunnel(String tunnelName) {
        Tunnel t = findTunnel(tunnelName);
        if (t == null) return false;
        try {
            t.close();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "stopTunnel failed: " + tunnelName, e);
            return false;
        }
    }

    // ─── Status / lifecycle ───────────────────────────────────────────────────

    /**
     * Returns true if at least one tunnel in the list is currently running.
     */
    public boolean isTunnelRunning() {
        for (Tunnel t : tunnels) {
            if (t != null && t.isTunnelRunning()) return true;
        }
        return false;
    }

    /**
     * Closes all active tunnels (called on service shutdown).
     */
    public void stopTunnel() {
        for (Tunnel t : tunnels) {
            try {
                if (t != null) t.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing tunnel: " + e.getMessage(), e);
            }
        }
    }
}
