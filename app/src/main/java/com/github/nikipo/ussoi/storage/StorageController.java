package com.github.nikipo.ussoi.storage;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;

import com.github.nikipo.ussoi.storage.logs.Logging;

import java.util.List;

public final class StorageController {
    private static final String TAG = "StorageController";

    public enum InitState {
        OK,
        NEED_FOLDER,
        PERMISSION_REVOKED,
        ERROR
    }

    private final Activity activity;
    private final SaveInputFields saveInputFields;
    private Logging logging;
    private final SharedPreferences pref;
    private final ActivityResultLauncher<Intent> folderPickerLauncher;

    public StorageController(
            Activity activity,
            SaveInputFields saveInputFields,
            ActivityResultLauncher<Intent> launcher
    ) {
        logging = Logging.getInstance(activity);
        this.activity = activity;
        this.saveInputFields = saveInputFields;
        this.pref = saveInputFields.get_shared_pref();
        this.folderPickerLauncher = launcher;
    }


    public InitState init() {
        if (pref == null) {
            logging.log(TAG + " prefs null");
            return InitState.ERROR;
        }

        String uriStr = pref.getString(SaveInputFields.PREF_LOG_URI, null);

        if (uriStr == null) {
            logging.log(TAG + " uri null");
            return InitState.NEED_FOLDER;
        }

        Uri treeUri = Uri.parse(uriStr);

        if (!hasPersistedPermission(treeUri)) {
            logging.log(TAG + " permission revoked");
            pref.edit().remove(SaveInputFields.PREF_LOG_URI).apply();
            return InitState.PERMISSION_REVOKED;
        }

        try {
            createUssoiFolders(treeUri);
        } catch (Exception e) {
            pref.edit().remove(SaveInputFields.PREF_LOG_URI).apply();
            logging.log(TAG + " folder creation failed");
            return InitState.ERROR;
        }

        return InitState.OK;
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
            logging.log(TAG + "Folder No write access");
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


    public void requestFolder() {
        showLogFolderNote();
    }
}
