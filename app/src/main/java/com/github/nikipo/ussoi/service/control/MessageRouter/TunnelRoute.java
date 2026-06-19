package com.github.nikipo.ussoi.service.control.MessageRouter;

import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_BT_SWITCH;
import static com.github.nikipo.ussoi.storage.SaveInputFields.KEY_USB_Switch;
import static com.github.nikipo.ussoi.ui.UssoiStrings.*;

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
            tunnels.add(new Tunnel() {
                @Override public void init()               {}
                @Override public void close()              {}
                @Override public boolean isTunnelRunning() { return false; }
                @Override public String getTunnelName()    { return EMPTY; }
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

    // TODO implement this
    private void initUsbTunnels(Context ctx) {
        for (BluetoothDevice bt : selectedBtDevices) {
            BluetoothHandler handler = new BluetoothHandler(ctx);
            handler.setDevice(bt);
            tunnels.add(handler);
        }
    }

    public void route(JSONObject json) {
        String cmd        = json.optString(CMD,        EMPTY);
        String cmdId      = json.optString(CMD_ID,      EMPTY);
        String tunnelName = json.optString(TUNNEL_NAME, EMPTY);
        try {
            switch (cmd) {
                case START_TUNNEL: {
                    if (startTunnel(tunnelName)) {
                        router.sendResponse(connectionManager, cmd,cmdId,null );
                    } else {
                        router.sendError(connectionManager, cmdId, cmd,"Tunnel '" + tunnelName + "' not found");
                    }
                    break;
                }
                case STOP_TUNNEL: {
                    if (stopTunnel(tunnelName)) {
                        router.sendResponse(connectionManager, cmd,cmdId, null);
                    } else {
                        router.sendError(connectionManager, cmdId, cmd,"Tunnel '" + tunnelName + "' not found");
                    }
                    break;
                }

                default:
                    router.sendError(connectionManager, cmdId, cmd, "Unknown tunnel command: " + cmd);
                    break;
            }

        } catch (Exception e) {
            // LOG TODO
            e.printStackTrace();
        }
    }

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
            // only init tunnel if not running
            if(!t.isTunnelRunning()){
                t.init();
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
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

    public JSONObject getTunnels() {
        JSONObject root = new JSONObject();
        JSONArray tunnelsArray = new JSONArray();

        for (Tunnel t : tunnels) {
            if (t == null) continue;

            String name = t.getTunnelName();
            if (name == null || name.isEmpty()) continue;

            tunnelsArray.put(name);
        }

        try {
            root.put("tunnels", tunnelsArray);
        }
        catch (Exception e){
            e.printStackTrace();
            return null;
        }
        return root;
    }

    public boolean isTunnelRunning() {
        // todo for now only return tunnel 0 status
        for (Tunnel t : tunnels) {
            if (t != null && t.isTunnelRunning()) return true;
        }
        return false;
    }


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
