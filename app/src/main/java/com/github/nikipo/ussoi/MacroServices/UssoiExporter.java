package com.github.nikipo.ussoi.MacroServices;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.*;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class UssoiExporter {
    private static final String TAG = "UssoiExporter";
    public static final int REQUEST_CODE_EXPORT_USSOI = 5678;

    private final Context context;
    private final File ussoiDir;

    public UssoiExporter(Context context) {
        this.context = context.getApplicationContext();
        this.ussoiDir = new File(context.getFilesDir(), "ussoi");
        // Ensure directory exists to prevent null pointer checks later
        if (!ussoiDir.exists()) ussoiDir.mkdirs();
    }

    // Step 1: Launch SAF
    public void requestExport(Activity activity) {
        if (ussoiDir.listFiles() == null || ussoiDir.listFiles().length == 0) {
            Toast.makeText(activity, "No files to export", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, "ussoi_backup.zip");
        activity.startActivityForResult(intent, REQUEST_CODE_EXPORT_USSOI);
    }

    // Step 2: Handle Export (Fixed Threading)
    public void handleExportResult(Uri uri) {
        if (uri == null) return;

        // Show a toast that background work started
        Toast.makeText(context, "Exporting started...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            File snapshotDir = new File(context.getCacheDir(), "export_snapshot");
            boolean success = false;

            try {
                // 1. CLEANUP OLD SNAPSHOTS
                deleteRecursive(snapshotDir);
                if (!snapshotDir.mkdirs()) {
                    throw new IOException("Could not create snapshot directory");
                }

                // 2. CREATE SNAPSHOT (Now in background thread)
                Log.d(TAG, "Creating snapshot...");
                copyRecursive(ussoiDir, snapshotDir);
                Log.d(TAG, "Snapshot created. Zipping...");

                // 3. ZIP THE SNAPSHOT
                try (OutputStream rawOut = context.getContentResolver().openOutputStream(uri)) {
                    if (rawOut == null) throw new IOException("Cannot open output stream");

                    try (BufferedOutputStream bufferedOut = new BufferedOutputStream(rawOut, 64 * 1024);
                         ZipOutputStream zipOut = new ZipOutputStream(bufferedOut)) {

                        // OPTIMIZATION: Use BEST_SPEED or NO_COMPRESSION for media files
                        zipOut.setLevel(Deflater.BEST_SPEED);

                        zipDirectory(snapshotDir, "", zipOut);

                        zipOut.finish();
                    }
                }
                success = true;
                Log.i(TAG, "Export completed successfully.");

            } catch (IOException e) {
                Log.e(TAG, "Export failed", e);
                // Optional: Try to delete the half-written ZIP file using DocumentsContract if needed
            } finally {
                // 4. CLEANUP SNAPSHOT
                deleteRecursive(snapshotDir);

                // 5. NOTIFY USER (On UI Thread)
                boolean finalSuccess = success;
                new Handler(Looper.getMainLooper()).post(() -> {
                    String msg = finalSuccess ? "Export Complete" : "Export Failed";
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
                });
            }
        }, "UssoiExportThread").start();
    }

    private void zipDirectory(File folder, String parent, ZipOutputStream zipOut) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;

        byte[] buffer = new byte[64 * 1024];

        for (File file : files) {
            // Logic to keep paths relative inside ZIP
            String entryName = parent.isEmpty() ? file.getName() : parent + "/" + file.getName();

            if (file.isDirectory()) {
                zipDirectory(file, entryName, zipOut);
                continue;
            }

            try (InputStream in = new BufferedInputStream(new FileInputStream(file), 64 * 1024)) {
                ZipEntry entry = new ZipEntry(entryName);
                entry.setTime(file.lastModified());
                zipOut.putNextEntry(entry);

                int len;
                while ((len = in.read(buffer)) != -1) {
                    zipOut.write(buffer, 0, len);
                }
                zipOut.closeEntry();
            } catch (Exception e) {
                // If a file in snapshot is corrupt/locked, log but continue zipping others?
                Log.w(TAG, "Failed to zip specific file: " + file.getName(), e);
            }
        }
    }

    private void copyRecursive(File src, File dest) throws IOException {
        if (src.isDirectory()) {
            if (!dest.exists() && !dest.mkdirs()) {
                throw new IOException("Failed to create dir: " + dest.getAbsolutePath());
            }
            String[] files = src.list();
            if (files == null) return;
            for (String file : files) {
                copyRecursive(new File(src, file), new File(dest, file));
            }
        } else {
            try (InputStream in = new BufferedInputStream(new FileInputStream(src));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
                byte[] buffer = new byte[64 * 1024];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }
        }
    }

    public void clearUssoiDirectory() {
        if (!ussoiDir.exists()) return;
        deleteRecursive(ussoiDir);
        ussoiDir.mkdirs(); // Recreate root after deletion
    }

    private void deleteRecursive(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }
}