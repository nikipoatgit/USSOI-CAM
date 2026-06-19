package com.github.nikipo.ussoi.system.deviceInfo;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.List;

/**
 * Collects lightweight device state. System Space Metrics stay packed in positional arrays,
 * while serving and neighbor radio configurations map out clean short-key JSONObjects.
 */
public class DeviceInfoDynamic {
    private static final String TAG = "DeviceInfoDynamic";
    private final Context context;

    public DeviceInfoDynamic(Context context) {
        this.context = context.getApplicationContext();
    }

    private JSONObject buildBase(JSONArray servingCellsOut, JSONArray neighborCellsOut) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("1", getSystemSpaceMetrics());

        getRadioMetrics(servingCellsOut, neighborCellsOut);

        root.put("2", servingCellsOut);
        root.put("3", neighborCellsOut);
        return root;
    }

    public JSONObject buildJsonPacket() {
        try {
            JSONArray servingCells = new JSONArray();
            JSONArray neighborCells = new JSONArray();
            return buildBase(servingCells, neighborCells);
        } catch (Exception e) {
            Log.e(TAG, "Error compiling base serial packet", e);
            return new JSONObject();
        }
    }

    public JSONObject getAll() {
        try {
            JSONArray servingCells = new JSONArray();
            JSONArray neighborCells = new JSONArray();
            JSONObject root = buildBase(servingCells, neighborCells);
            root.put("4", getNetworkType());
            return root;
        } catch (JSONException e) {
            Log.e(TAG, "JSON exception parsing internal metrics map", e);
            return new JSONObject();
        }
    }

    private JSONArray getSystemSpaceMetrics() throws JSONException {
        JSONArray metrics = new JSONArray();

        // 1. RAM Usage (Positions 0, 1)
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            metrics.put(toGB(memoryInfo.totalMem));
            metrics.put(toGB(memoryInfo.availMem));
        } else {
            metrics.put(-1.0).put(-1.0);
        }

        // 2. Internal Storage (Positions 2, 3)
        try {
            File internalPath = Environment.getDataDirectory();
            StatFs internalStat = new StatFs(internalPath.getPath());
            metrics.put(toGB(internalStat.getTotalBytes()));
            metrics.put(toGB(internalStat.getFreeBytes()));
        } catch (Exception e) {
            Log.d(TAG, "Failed reading internal storage info: " + e.getMessage());
        }

        // 3. External Volumes (Appends pairs sequentially)
        File[] externalFilesDirs = context.getExternalFilesDirs(null);
        if (externalFilesDirs != null && externalFilesDirs.length > 1) {
            for (int i = 1; i < externalFilesDirs.length; i++) {
                File path = externalFilesDirs[i];
                if (path != null) {
                    boolean isRemovable = false;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        try {
                            isRemovable = Environment.isExternalStorageRemovable(path);
                        } catch (IllegalArgumentException e) {
                            isRemovable = false;
                        }
                    } else {
                        isRemovable = Environment.isExternalStorageRemovable();
                    }

                    if (isRemovable) {
                        try {
                            StatFs extStat = new StatFs(path.getPath());
                            metrics.put(toGB(extStat.getTotalBytes()));
                            metrics.put(toGB(extStat.getFreeBytes()));
                        } catch (Exception e) {
                            Log.d(TAG, "Failed tracking stats for storage volume: " + e.getMessage());
                        }
                    }
                }
            }
        }
        return metrics;
    }

    private void getRadioMetrics(JSONArray servingCellsOut, JSONArray neighborCellsOut) {
        TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null || ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        try {
            List<CellInfo> cellInfoList = tm.getAllCellInfo();
            if (cellInfoList != null) {
                for (CellInfo cellInfo : cellInfoList) {
                    if (cellInfo == null) continue;

                    JSONObject parsedCell = parseCellToMap(cellInfo);
                    if (parsedCell != null) {
                        if (cellInfo.isRegistered()) {
                            servingCellsOut.put(parsedCell);
                        } else {
                            neighborCellsOut.put(parsedCell);
                        }
                    }
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Insufficient location run-time access rights to probe cellular layers", e);
        } catch (Throwable t) {
            Log.w(TAG, "Fatal OEM hardware telephony layer exception avoided", t);
        }
    }

    private JSONObject parseCellToMap(CellInfo cellInfo) {
        try {
            JSONObject cell = new JSONObject();

            // 1. 5G NR Handling
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo instanceof CellInfoNr) {
                CellInfoNr nr = (CellInfoNr) cellInfo;
                if (!(nr.getCellIdentity() instanceof CellIdentityNr)) return null;

                CellIdentityNr identity = (CellIdentityNr) nr.getCellIdentity();
                CellSignalStrengthNr strength = (CellSignalStrengthNr) nr.getCellSignalStrength();

                cell.put("g", 5);
                cell.put("nci", sLong(identity.getNci()));
                cell.put("tac", sInt(identity.getTac()));
                cell.put("pci", sInt(identity.getPci()));
                cell.put("f", sInt(identity.getNrarfcn()));
                cell.put("rsrp", sInt(strength.getSsRsrp()));
                cell.put("rsrq", sInt(strength.getSsRsrq()));
                cell.put("snr", sInt(strength.getSsSinr()));

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    cell.put("ta", sInt(strength.getTimingAdvanceMicros()));
                } else {
                    cell.put("ta", -1);
                }
                return cell;
            }
            // 2. 4G LTE Handling
            else if (cellInfo instanceof CellInfoLte) {
                CellInfoLte lte = (CellInfoLte) cellInfo;
                CellIdentityLte identity = lte.getCellIdentity();
                CellSignalStrengthLte strength = lte.getCellSignalStrength();

                cell.put("g", 4);
                cell.put("tac", sInt(identity.getTac()));
                cell.put("pci", sInt(identity.getPci()));
                cell.put("f", sInt(identity.getEarfcn()));

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    cell.put("rsrp", sInt(strength.getRsrp()));
                    cell.put("rsrq", sInt(strength.getRsrq()));
                    cell.put("snr", sInt(strength.getRssnr()));
                } else {
                    cell.put("rsrp", -1).put("rsrq", -1).put("snr", -1);
                }

                cell.put("ta", sInt(strength.getTimingAdvance()));
                return cell;
            }
            // 3. 3G WCDMA Handling
            else if (cellInfo instanceof CellInfoWcdma) {
                CellInfoWcdma wcdma = (CellInfoWcdma) cellInfo;
                CellIdentityWcdma identity = wcdma.getCellIdentity();
                CellSignalStrengthWcdma strength = wcdma.getCellSignalStrength();

                cell.put("g", 3);
                cell.put("cid", sInt(identity.getCid()));
                cell.put("lac", sInt(identity.getLac()));
                cell.put("psc", sInt(identity.getPsc()));

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    cell.put("f", sInt(identity.getUarfcn()));
                } else {
                    cell.put("f", -1);
                }

                cell.put("dbm", sInt(strength.getDbm()));
                cell.put("p1", -1);
                cell.put("p2", -1);
                cell.put("ta", -1); // Hardware fallback
                return cell;
            }
            // 4. 2G GSM Handling
            else if (cellInfo instanceof CellInfoGsm) {
                CellInfoGsm gsm = (CellInfoGsm) cellInfo;
                CellIdentityGsm identity = gsm.getCellIdentity();
                CellSignalStrengthGsm strength = gsm.getCellSignalStrength();

                cell.put("g", 2);
                cell.put("cid", sInt(identity.getCid()));
                cell.put("lac", sInt(identity.getLac()));
                cell.put("p1", -1);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    cell.put("f", sInt(identity.getArfcn()));
                } else {
                    cell.put("f", -1);
                }

                cell.put("dbm", sInt(strength.getDbm()));
                cell.put("p2", -1);
                cell.put("p3", -1);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    cell.put("ta", sInt(strength.getTimingAdvance()));
                } else {
                    cell.put("ta", -1);
                }
                return cell;
            }
        } catch (Throwable t) {
            Log.d(TAG, "Skipping corrupted cell record caused by vendor anomalies: " + t.getMessage());
        }
        return null;
    }

    private String getNetworkType() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
                    if (caps != null) {
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi+vpn";
                            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular+vpn";
                            return "vpn";
                        }
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return "bluetooth";
                    }
                }
            } else {
                android.net.NetworkInfo activeInfo = cm.getActiveNetworkInfo();
                if (activeInfo != null && activeInfo.isConnected()) {
                    if (activeInfo.getType() == ConnectivityManager.TYPE_WIFI) return "wifi";
                    if (activeInfo.getType() == ConnectivityManager.TYPE_MOBILE) return "cellular";
                }
            }
        }
        return "none";
    }

    private int sInt(int val) {
        return (val == Integer.MAX_VALUE || val == CellInfo.UNAVAILABLE) ? -1 : val;
    }

    private long sLong(long val) {
        long unavailableLong = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ? CellInfo.UNAVAILABLE_LONG : Long.MAX_VALUE;
        return (val == Long.MAX_VALUE || val == unavailableLong || val == -1L) ? -1 : val;
    }

    private double toGB(long bytes) {
        if (bytes <= 0) return 0.0;
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        return Math.round(gb * 100.0) / 100.0;
    }
}