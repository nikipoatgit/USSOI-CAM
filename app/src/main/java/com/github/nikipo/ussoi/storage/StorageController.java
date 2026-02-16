package com.github.nikipo.ussoi.storage;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;

import java.util.List;

public final class StorageController {

    private final Activity activity;
    private final SaveInputFields saveInputFields;
    private final SharedPreferences pref;
    private final ActivityResultLauncher<Intent> folderPickerLauncher;

    public StorageController(
            Activity activity,
            SaveInputFields saveInputFields,
            ActivityResultLauncher<Intent> launcher
    ) {
        this.activity = activity;
        this.saveInputFields = saveInputFields;
        this.pref = saveInputFields.get_shared_pref();
        this.folderPickerLauncher = launcher;
    }



    public boolean init() {
        if (pref == null){
            return false;
        }
        String uriStr = pref.getString(SaveInputFields.PREF_LOG_URI, null);

        // check prefs if Uri was earlier saved
        if (uriStr == null) {
            showLogFolderNote();
            return false;
        }

        Uri treeUri = Uri.parse(uriStr);

        // if permission was revoked initiate one
        if (!hasPersistedPermission(treeUri)) {
            pref.edit().remove(SaveInputFields.PREF_LOG_URI).apply();
            showLogFolderNote();
            return false;
        }

        // create subfolder in not exist
        try {
            createUssoiFolders(treeUri);
        } catch (Exception e) {
            pref.edit().remove(SaveInputFields.PREF_LOG_URI).apply();
            showLogFolderNote();
        }
        return true;
    }


    private boolean hasPersistedPermission(Uri uri) {
        List<UriPermission> perms =
                activity.getContentResolver().getPersistedUriPermissions();

        for (UriPermission p : perms) {
            if (p.getUri().equals(uri) && p.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    private void createUssoiFolders(Uri treeUri) {
        DocumentFile root = DocumentFile.fromTreeUri(activity, treeUri);

        if (root == null || !root.canWrite()) {
            throw new SecurityException("No write access");
        }

        if (root.findFile("log") == null)
            root.createDirectory("log");

        if (root.findFile("videos") == null)
            root.createDirectory("videos");
    }

    // shoe dialog to user to select folder
    private void showLogFolderNote() {
        new AlertDialog.Builder(activity)
                .setTitle("Select Logging Folder")
                .setMessage(
                        "Please select a folder where the app will store logs and videos.\n\n" +
                                "Folder access is required for the application to work."
                )
                .setCancelable(false)
                .setPositiveButton("Select Folder", (d, w) -> launchPicker())
                .setNegativeButton("Close App", (d, w) -> activity.finishAffinity())
                .show();
    }

    private void launchPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        folderPickerLauncher.launch(intent);
    }

    // Set Uri Permission
    public void onFolderPicked(Uri treeUri) {

        activity.getContentResolver().takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        );

        pref.edit()
                .putString(SaveInputFields.PREF_LOG_URI, treeUri.toString())
                .apply();

        createUssoiFolders(treeUri);
    }

}
