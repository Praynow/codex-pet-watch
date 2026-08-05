package com.codexwatch.pet;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AppUpdateManager {
    interface Listener {
        void onStatus(String label, String detail, boolean error);
    }

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_METADATA_BYTES = 64 * 1024;
    private static final long MAX_APK_BYTES = 64L * 1024L * 1024L;
    private static final String APK_FILE_NAME = "codex-pet-watch-update.apk";

    private final Activity activity;
    private final Listener listener;
    private final String metadataUrl;
    private final String token;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile boolean running;
    private volatile boolean closed;
    private UpdateMetadata pendingPermissionUpdate;

    AppUpdateManager(Activity activity, String metadataUrl, String token, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.metadataUrl = metadataUrl == null ? "" : metadataUrl.trim();
        this.token = token == null ? "" : token.trim();
        restoreStatus();
    }

    void checkForUpdate() {
        if (closed || running) {
            return;
        }
        if (!isStrictHttps(metadataUrl)) {
            finish("CONFIG", "HTTPS metadata required", true);
            return;
        }

        running = true;
        pendingPermissionUpdate = null;
        publish("CHECKING", "Contacting server", false);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    UpdateMetadata metadata = fetchMetadata();
                    long currentVersion = currentVersionCode();
                    if (metadata.versionCode <= currentVersion) {
                        finish("CURRENT", "Version " + metadata.versionName, false);
                        return;
                    }
                    requestInstallPermissionOrDownload(metadata);
                } catch (UpdateException error) {
                    finish("UNAVAILABLE", error.getMessage(), true);
                } catch (Exception ignored) {
                    finish("FAILED", "Update check error", true);
                }
            }
        });
    }

    void onResume() {
        if (closed) {
            return;
        }
        UpdateMetadata pending = pendingPermissionUpdate;
        if (pending == null) {
            restoreStatus();
            return;
        }
        if (!canInstallPackages()) {
            pendingPermissionUpdate = null;
            running = false;
            publish("ALLOW", "Tap to grant access", true);
            return;
        }
        pendingPermissionUpdate = null;
        publish("DOWNLOAD", "Version " + pending.versionName, false);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                downloadAndInstall(pending);
            }
        });
    }

    void close() {
        closed = true;
        pendingPermissionUpdate = null;
        executor.shutdownNow();
    }

    private UpdateMetadata fetchMetadata() throws Exception {
        HttpURLConnection connection = openHttps(metadataUrl, "application/json");
        try {
            int contentLength = connection.getContentLength();
            if (contentLength > MAX_METADATA_BYTES) {
                throw new UpdateException("Metadata too large");
            }
            String body = readText(connection.getInputStream(), MAX_METADATA_BYTES);
            JSONObject json = new JSONObject(body);
            int versionCode = json.optInt("version_code", 0);
            String versionName = json.optString("version_name", "").trim();
            String apkUrl = json.optString("apk_url", "").trim();
            String sha256 = json.optString("sha256", "").trim().toLowerCase(Locale.US);
            boolean required = json.optBoolean("required", false);
            String notes = json.optString("notes", "").trim();
            if (versionCode <= 0 || versionName.isEmpty()) {
                throw new UpdateException("Invalid version data");
            }
            if (!isStrictHttps(apkUrl)) {
                throw new UpdateException("HTTPS APK required");
            }
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new UpdateException("Invalid SHA-256");
            }
            return new UpdateMetadata(versionCode, versionName, apkUrl, sha256, required, notes);
        } finally {
            connection.disconnect();
        }
    }

    private void requestInstallPermissionOrDownload(UpdateMetadata metadata) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (closed) {
                    return;
                }
                if (canInstallPackages()) {
                    publish("DOWNLOAD", "Version " + metadata.versionName, false);
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            downloadAndInstall(metadata);
                        }
                    });
                    return;
                }

                pendingPermissionUpdate = metadata;
                publish("ALLOW", "Enable unknown apps", false);
                Intent settingsIntent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())
                );
                try {
                    activity.startActivity(settingsIntent);
                } catch (ActivityNotFoundException | SecurityException ignored) {
                    pendingPermissionUpdate = null;
                    finish("BLOCKED", "Unknown apps unavailable", true);
                }
            }
        });
    }

    private void downloadAndInstall(UpdateMetadata metadata) {
        File updateDirectory = new File(activity.getCacheDir(), "updates");
        File partialFile = new File(updateDirectory, APK_FILE_NAME + ".part");
        File apkFile = new File(updateDirectory, APK_FILE_NAME);
        try {
            if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
                throw new UpdateException("Cannot create update cache");
            }
            deleteQuietly(partialFile);
            deleteQuietly(apkFile);
            downloadApk(metadata, partialFile);
            if (!partialFile.renameTo(apkFile)) {
                throw new UpdateException("Cannot finalize download");
            }
            publish("VERIFY", "SHA-256 verified", false);
            installApk(apkFile);
            running = false;
            publish("CONFIRM", "Approve install", false);
        } catch (UpdateException error) {
            deleteQuietly(partialFile);
            deleteQuietly(apkFile);
            finish("FAILED", error.getMessage(), true);
        } catch (Exception ignored) {
            deleteQuietly(partialFile);
            deleteQuietly(apkFile);
            finish("FAILED", "Update download error", true);
        }
    }

    private void downloadApk(UpdateMetadata metadata, File target) throws Exception {
        HttpURLConnection connection = openHttps(metadata.apkUrl, "application/vnd.android.package-archive");
        try {
            String contentType = connection.getContentType();
            String normalizedType = contentType == null ? "" : contentType.toLowerCase(Locale.US);
            if (!normalizedType.startsWith("application/vnd.android.package-archive")
                    && !normalizedType.startsWith("application/octet-stream")) {
                throw new UpdateException("Unexpected APK type");
            }
            long announcedLength = connection.getContentLengthLong();
            if (announcedLength <= 0 || announcedLength > MAX_APK_BYTES) {
                throw new UpdateException("Invalid APK size");
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            byte[] buffer = new byte[16 * 1024];
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_APK_BYTES) {
                        throw new UpdateException("APK too large");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (total != announcedLength) {
                throw new UpdateException("APK length mismatch");
            }
            String actualHash = toHex(digest.digest());
            if (!MessageDigest.isEqual(
                    metadata.sha256.getBytes(StandardCharsets.US_ASCII),
                    actualHash.getBytes(StandardCharsets.US_ASCII)
            )) {
                throw new UpdateException("SHA-256 mismatch");
            }
        } finally {
            connection.disconnect();
        }
    }

    private void installApk(File apkFile) throws Exception {
        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams parameters = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
        );
        parameters.setAppPackageName(activity.getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            parameters.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }

        int sessionId = installer.createSession(parameters);
        try (PackageInstaller.Session session = installer.openSession(sessionId);
             InputStream input = new BufferedInputStream(new FileInputStream(apkFile))) {
            try (OutputStream sessionOutput = session.openWrite("base.apk", 0, apkFile.length());
                 BufferedOutputStream output = new BufferedOutputStream(sessionOutput)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
                session.fsync(sessionOutput);
            }

            Intent statusIntent = new Intent(activity, UpdateInstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    activity,
                    sessionId,
                    statusIntent,
                    flags
            );
            session.commit(pendingIntent.getIntentSender());
        }
    }

    private HttpURLConnection openHttps(String urlText, String accept) throws Exception {
        URL current = new URL(urlText);
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (!"https".equalsIgnoreCase(current.getProtocol())) {
                throw new UpdateException("HTTPS redirect required");
            }
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(20_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty("Cache-Control", "no-cache");
            if (!token.isEmpty()) {
                connection.setRequestProperty("X-Codex-Watch-Token", token);
            }

            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                return connection;
            }
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    throw new UpdateException("Invalid redirect");
                }
                current = new URL(current, location);
                continue;
            }
            connection.disconnect();
            throw new UpdateException("Server " + code);
        }
        throw new UpdateException("Too many redirects");
    }

    private boolean canInstallPackages() {
        return activity.getPackageManager().canRequestPackageInstalls();
    }

    private long currentVersionCode() throws PackageManager.NameNotFoundException {
        PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
        return info.getLongVersionCode();
    }

    private void restoreStatus() {
        SharedPreferences preferences = activity.getSharedPreferences(
                UpdateInstallReceiver.PREFS,
                Context.MODE_PRIVATE
        );
        String label = preferences.getString(UpdateInstallReceiver.KEY_LABEL, "CHECK");
        String detail = preferences.getString(UpdateInstallReceiver.KEY_DETAIL, "Tap to check");
        boolean error = preferences.getBoolean(UpdateInstallReceiver.KEY_ERROR, false);
        deliver(label, detail, error);
    }

    private void publish(String label, String detail, boolean error) {
        UpdateInstallReceiver.persistStatus(activity, label, detail, error);
        deliver(label, detail, error);
    }

    private void finish(String label, String detail, boolean error) {
        running = false;
        pendingPermissionUpdate = null;
        publish(label, detail, error);
    }

    private void deliver(String label, String detail, boolean error) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!closed) {
                    listener.onStatus(
                            label == null ? "CHECK" : label,
                            detail == null ? "Tap to check" : detail,
                            error
                    );
                }
            }
        });
    }

    private static String readText(InputStream input, int limit) throws Exception {
        StringBuilder builder = new StringBuilder();
        int total = 0;
        char[] buffer = new char[2048];
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new BufferedInputStream(input), StandardCharsets.UTF_8)
        )) {
            int read;
            while ((read = reader.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new UpdateException("Metadata too large");
                }
                builder.append(buffer, 0, read);
            }
        }
        return builder.toString();
    }

    private static boolean isStrictHttps(String value) {
        try {
            URL url = new URL(value);
            return "https".equalsIgnoreCase(url.getProtocol())
                    && url.getHost() != null
                    && !url.getHost().trim().isEmpty()
                    && url.getUserInfo() == null
                    && url.getQuery() == null
                    && url.getRef() == null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static void deleteQuietly(File file) {
        if (file.exists()) {
            // Only app-private cache files constructed above are ever passed here.
            file.delete();
        }
    }

    private static final class UpdateMetadata {
        final int versionCode;
        final String versionName;
        final String apkUrl;
        final String sha256;
        final boolean required;
        final String notes;

        UpdateMetadata(
                int versionCode,
                String versionName,
                String apkUrl,
                String sha256,
                boolean required,
                String notes
        ) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.required = required;
            this.notes = notes;
        }
    }

    private static final class UpdateException extends Exception {
        UpdateException(String message) {
            super(message);
        }
    }
}
