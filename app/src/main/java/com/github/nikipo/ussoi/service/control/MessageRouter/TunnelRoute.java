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
import com.github.nikipo.ussoi.tunnel.Tunnel;
import com.github.nikipo.ussoi.tunnel.BluetoothHandler;
import com.github.nikipo.ussoi.tunnel.UsbHandler;

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
                @Override public void Start() {}
                @Override  public void Stop() {}
                @Override public boolean isTunnelRunning() { return false; }
                @Override public String getTunnelName()    { return EMPTY; }
            });
            Log.w(TAG, "No tunnel type selected in preferences");
        }
    }

    private void initBtTunnels(Context ctx) {
        for (BluetoothDevice bt : selectedBtDevices) {
            BluetoothHandler handler = new BluetoothHandler(ctx, bt);
            tunnels.add(handler);
        }
    }


    public void route(JSONObject json) {
        String cmd        = json.optString(CMD,        EMPTY);
        String cmdId      = json.optString(CMD_ID,      EMPTY);

        JSONObject param = json.optJSONObject("param");
        String tunnelName = param != null ? param.optString("name") : null;

        try {
            //{"cmd":"start_tunnel","param":{"name":"BT200:21:13:00:35:41"},"cmdId":"c1782107969500_6"}

            switch (cmd) {
                case START_TUNNEL : {
                    if (startTunnel(tunnelName)) {
                        router.sendResponse(connectionManager, cmd, cmdId, null);
                    } else {
                        throw new IllegalStateException("Tunnel " + tunnelName + "not found");
                    }
                    break;
                }
                case STOP_TUNNEL : {
                    if (stopTunnel(tunnelName)) {
                        router.sendResponse(connectionManager, cmd, cmdId, null);
                    } else {
                        throw new IllegalStateException("Tunnel " + tunnelName + "not found");
                    }
                    break;
                }
                default :  throw new IllegalStateException("Unknown command");
            }
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    private Tunnel findTunnel(String name) {
        Log.d(TAG, "findTunnel() called, requested=" + name);

        if (name == null || name.isEmpty()) {
            Log.w(TAG, "findTunnel(): tunnel name is null/empty");
            return null;
        }

        for (Tunnel t : tunnels) {
            if (t == null) {
                Log.w(TAG, "findTunnel(): encountered null tunnel");
                continue;
            }

            String currentName = t.getTunnelName();

            Log.d(TAG, "findTunnel(): checking tunnel=" + currentName);

            if (name.equals(currentName)) {
                Log.d(TAG, "findTunnel(): match found -> " + currentName);
                return t;
            }
        }

        Log.e(TAG, "findTunnel(): tunnel not found -> " + name);

        StringBuilder available = new StringBuilder();
        for (Tunnel t : tunnels) {
            if (t != null) {
                available.append(t.getTunnelName()).append(", ");
            }
        }

        Log.e(TAG, "Available tunnels: " + available);

        return null;
    }

    private boolean startTunnel(String tunnelName) {
        Tunnel t = findTunnel(tunnelName);
        if (t == null) return false;
        try {
            if(!t.isTunnelRunning()){
                t.Start();;
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
            throw new IllegalStateException(tunnelName + e.getMessage());
        }
    }

    public JSONObject getTunnels() {
        JSONObject data = new JSONObject();

        for (Tunnel t : tunnels) {
            if (t == null) continue;

            String name = t.getTunnelName();
            boolean status = t.isTunnelRunning();

            if (name == null || name.isEmpty()) continue;

            try {
                data.put(name, status);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return data;
    }

    public boolean isTunnelRunning() {
        // todo for now only return tunnel 0 status
        for (Tunnel t : tunnels) {
            if (t != null && t.isTunnelRunning()) return true;
        }
        return false;
    }


    public void close() {
        for (Tunnel t : tunnels) {
            try {
                if (t != null) t.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing tunnel: " + e.getMessage(), e);
            }
        }
    }
}