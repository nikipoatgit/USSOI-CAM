package com.github.nikipo.ussoi.MacroServices;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Logging {
    private static final String TAG = "Logging";
    private static volatile Logging instance; // volatile for double-checked locking safety
    private DocumentFile logDir;
    private DocumentFile logFile;
    private final Context context;
    private final String sessionLogFileName;
    private final ExecutorService logExecutor;


    private Logging(Context context) {
        this.context = context.getApplicationContext();
        this.logExecutor = Executors.newSingleThreadExecutor();
        this.sessionLogFileName = buildSessionLogFileName();
        logExecutor.execute(this::initializeLogging);
    }


    public static Logging getInstance(Context context) {
        if (instance == null) {
            synchronized (Logging.class) {
                if (instance == null) {
                    instance = new Logging(context);
                }
            }
        }
        return instance;
    }

    public static Logging getIfInitialized() {
        return instance;
    }

    private void initializeLogging() {
        SharedPreferences pref =
                SaveInputFields.getInstance(context).get_shared_pref();

        String uriStr = pref.getString(SaveInputFields.PREF_LOG_URI, null);
        if (uriStr == null) return;

        DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(uriStr));
        if (root == null) return;

        logDir = root.findFile("log");
        if (logDir == null) {
            logDir = root.createDirectory("log");
        }

        assert logDir != null;
        logFile = logDir.findFile(sessionLogFileName);
        if (logFile == null) {
            logFile = logDir.createFile("text/plain", sessionLogFileName);
        }
    }


    public void log(String message) {
        if (logExecutor.isShutdown()) return;

        final String ts =
                new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());

        logExecutor.execute(() -> {
            if (logFile == null) {
                Log.e(TAG, "logFile not initialized yet");
                return;
            }
            try (OutputStream os =
                         context.getContentResolver()
                                 .openOutputStream(logFile.getUri(), "wa");
                 BufferedWriter writer =
                         new BufferedWriter(
                                 new OutputStreamWriter(os, StandardCharsets.UTF_8))) {

                writer.write("[" + ts + "] " + message);
                writer.newLine();

            }catch (SecurityException | IOException e) {
                Log.e(TAG, "SAF permission revoked", e);
                closeLogging();
            }

        });
    }

    private String buildSessionLogFileName() {
        Date now = new Date();

        String timePart = new SimpleDateFormat("h_mm_a", Locale.US).format(now);

        String day = new SimpleDateFormat("d", Locale.US).format(now);
        String suffix = getDaySuffix(Integer.parseInt(day));
        String datePart = new SimpleDateFormat("d'" + suffix + "'_MMMM_yyyy", Locale.US).format(now);

        String precisionPart = new SimpleDateFormat("ss.SSS", Locale.US).format(now);

        return "log__" + timePart + "__" + datePart + "__" + precisionPart +".txt";
    }
    private String getDaySuffix(int day) {
        if (day >= 11 && day <= 13) return "th";
        switch (day % 10) {
            case 1: return "st";
            case 2: return "nd";
            case 3: return "rd";
            default: return "th";
        }
    }


    public void closeLogging() {
        logExecutor.shutdown();
        instance = null;
    }

}