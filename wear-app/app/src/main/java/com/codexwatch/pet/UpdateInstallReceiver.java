package com.codexwatch.pet;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;

public final class UpdateInstallReceiver extends BroadcastReceiver {
    static final String PREFS = "codex_watch_update";
    static final String KEY_LABEL = "label";
    static final String KEY_DETAIL = "detail";
    static final String KEY_ERROR = "error";

    @Override
    @SuppressLint("WearRecents") // A receiver needs NEW_TASK to launch the system-owned installer UI.
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE
        );
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            persistStatus(context, "CONFIRM", "Approve install", false);
            Intent confirmation = getConfirmationIntent(intent);
            if (confirmation == null) {
                persistStatus(context, "BLOCKED", "No installer UI", true);
                return;
            }
            confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(confirmation);
            } catch (RuntimeException ignored) {
                persistStatus(context, "BLOCKED", "Cannot open installer", true);
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            persistStatus(context, "INSTALLED", "Update complete", false);
            return;
        }

        String label = status == PackageInstaller.STATUS_FAILURE_ABORTED ? "CANCELLED" : "FAILED";
        String detail = installFailureDetail(status);
        persistStatus(context, label, detail, true);
    }

    @SuppressWarnings("deprecation")
    private static Intent getConfirmationIntent(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        }
        return intent.getParcelableExtra(Intent.EXTRA_INTENT);
    }

    private static String installFailureDetail(int status) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "Install cancelled";
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "Install blocked";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return "Signature conflict";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "APK incompatible";
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return "APK invalid";
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "Storage full";
            default:
                return "Installer error";
        }
    }

    static void persistStatus(Context context, String label, String detail, boolean error) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        preferences.edit()
                .putString(KEY_LABEL, label)
                .putString(KEY_DETAIL, detail)
                .putBoolean(KEY_ERROR, error)
                .apply();
    }
}
