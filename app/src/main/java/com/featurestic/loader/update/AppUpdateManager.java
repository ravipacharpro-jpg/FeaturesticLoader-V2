package com.featurestic.loader.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.featurestic.loader.AppConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Smart In-App Auto-Update Manager for FeaturesticLoader.
 * - Semantic version comparison guarantees already-updated users NEVER see prompts.
 * - Non-blocking asynchronous update check.
 * - Frosted-glass style update dialog with changelog & download progress.
 * - Secure FileProvider APK installation.
 */
public final class AppUpdateManager {

    private static final String TAG = "AppUpdateManager";
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static boolean sIsChecking = false;

    private AppUpdateManager() {}

    public static void checkForUpdate(final Context context, final boolean manualCheck) {
        if (context == null || sIsChecking) return;
        sIsChecking = true;

        new Thread(() -> {
            try {
                String currentVer = getCurrentVersionName(context);
                String pkgName = context.getPackageName();

                String panelBase = AppConfig.PHP_PANEL_URL;
                if (panelBase == null || panelBase.trim().isEmpty()) {
                    panelBase = "https://featurestic-box-panel.vercel.app";
                }
                while (panelBase.endsWith("/")) {
                    panelBase = panelBase.substring(0, panelBase.length() - 1);
                }

                String urlStr = panelBase + "/api/public/update-check"
                        + "?version=" + URLEncoder.encode(currentVer, "UTF-8")
                        + "&pkg=" + URLEncoder.encode(pkgName, "UTF-8");

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/json");

                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    br.close();

                    JSONObject json = new JSONObject(sb.toString());
                    boolean hasUpdate = json.optBoolean("has_update", false);
                    String latestVer = json.optString("latest_version", "");
                    String apkUrl = json.optString("apk_url", "");
                    String changelog = json.optString("changelog", "Bug fixes and improvements.");
                    boolean forceUpdate = json.optBoolean("force_update", false);

                    // Check System Broadcast Notice from Panel
                    String broadcastNotice = json.optString("broadcast_notice", "");
                    int broadcastId = json.optInt("broadcast_notice_id", 0);
                    if (!broadcastNotice.trim().isEmpty()) {
                        boolean isNewNotice = com.featurestic.loader.notification.FeaturesticNotifier.checkAndNotifyBroadcast(
                                context, broadcastNotice.trim(), broadcastId);
                        if (isNewNotice && context instanceof Activity) {
                            showBroadcastDialog(context, broadcastNotice.trim());
                        }
                    }

                    // STRICT CHECK: Only trigger if server says update available AND local version is strictly older
                    if (hasUpdate && apkUrl != null && !apkUrl.trim().isEmpty() && isNewerVersion(currentVer, latestVer)) {
                        // Push System Status Bar Notification
                        com.featurestic.loader.notification.FeaturesticNotifier.showUpdateNotification(
                                context, latestVer, apkUrl, changelog);

                        // Show In-App Frosted Update Dialog if user is looking at the screen
                        sMainHandler.post(() -> showUpdateDialog(context, latestVer, apkUrl, changelog, forceUpdate));
                    } else if (manualCheck) {
                        sMainHandler.post(() -> Toast.makeText(context,
                                "Loader is up to date (v" + currentVer + ")",
                                Toast.LENGTH_SHORT).show());
                    }
                } else if (manualCheck) {
                    sMainHandler.post(() -> Toast.makeText(context,
                            "Unable to check for updates (server " + code + ")",
                            Toast.LENGTH_SHORT).show());
                }
                conn.disconnect();
            } catch (Throwable t) {
                Log.w(TAG, "Update check failed: " + t.getMessage());
                if (manualCheck) {
                    sMainHandler.post(() -> Toast.makeText(context,
                            "Check failed: " + (t.getMessage() != null ? t.getMessage() : "Network error"),
                            Toast.LENGTH_SHORT).show());
                }
            } finally {
                sIsChecking = false;
            }
        }, "Featurestic-UpdateCheck").start();
    }

    /**
     * Compares semantic versions (e.g. 1.2.4 vs 1.2.5).
     * Returns true ONLY if candidateVer is strictly newer than currentVer.
     */
    public static boolean isNewerVersion(String currentVer, String candidateVer) {
        if (candidateVer == null || candidateVer.trim().isEmpty()) return false;
        if (currentVer == null || currentVer.trim().isEmpty()) return true;

        String c1 = currentVer.trim().replaceAll("^[vV]", "");
        String c2 = candidateVer.trim().replaceAll("^[vV]", "");

        String[] p1 = c1.split("[.\\-_]");
        String[] p2 = c2.split("[.\\-_]");
        int len = Math.max(p1.length, p2.length);

        for (int i = 0; i < len; i++) {
            int v1 = 0;
            int v2 = 0;
            if (i < p1.length) {
                try {
                    v1 = Integer.parseInt(p1[i].replaceAll("[^0-9]", ""));
                } catch (Throwable ignored) {}
            }
            if (i < p2.length) {
                try {
                    v2 = Integer.parseInt(p2[i].replaceAll("[^0-9]", ""));
                } catch (Throwable ignored) {}
            }
            if (v2 > v1) return true;
            if (v1 > v2) return false;
        }
        return false;
    }

    public static String getCurrentVersionName(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (pi != null && pi.versionName != null) {
                return pi.versionName;
            }
        } catch (Throwable ignored) {}
        return "1.2.5";
    }

    public static void showBroadcastDialog(final Context context, final String broadcastMessage) {
        if (!(context instanceof Activity) || ((Activity) context).isFinishing()) {
            return;
        }
        final Activity act = (Activity) context;
        sMainHandler.post(() -> {
            try {
                AlertDialog.Builder builder = new AlertDialog.Builder(act);
                builder.setTitle("📢 System Announcement");
                builder.setMessage(broadcastMessage);
                builder.setPositiveButton("UNDERSTOOD", (d, w) -> d.dismiss());
                builder.setCancelable(true);
                builder.show();
            } catch (Throwable ignored) {}
        });
    }

    public static void showUpdateDialog(final Context context, final String latestVer,
                                         final String apkUrl, final String changelog,
                                         final boolean forceUpdate) {
        if (!(context instanceof Activity) || ((Activity) context).isFinishing()) {
            return;
        }
        final Activity act = (Activity) context;

        View dialogView = act.getLayoutInflater().inflate(com.featurestic.loader.R.layout.dialog_app_update, null);

        TextView tvSub = dialogView.findViewById(com.featurestic.loader.R.id.tvUpdateVersionSubtitle);
        TextView tvLog = dialogView.findViewById(com.featurestic.loader.R.id.tvUpdateChangelog);
        final View layoutProgress = dialogView.findViewById(com.featurestic.loader.R.id.layoutDownloadProgress);
        final ProgressBar pbDownload = dialogView.findViewById(com.featurestic.loader.R.id.pbUpdateDownload);
        final TextView tvProgressText = dialogView.findViewById(com.featurestic.loader.R.id.tvDownloadProgressText);
        final TextView btnLater = dialogView.findViewById(com.featurestic.loader.R.id.btnUpdateLater);
        final TextView btnUpdate = dialogView.findViewById(com.featurestic.loader.R.id.btnUpdateNow);

        if (tvSub != null) {
            tvSub.setText("Version " + latestVer + " is ready to install");
        }
        if (tvLog != null && changelog != null && !changelog.isEmpty()) {
            tvLog.setText(changelog);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(act);
        builder.setView(dialogView);
        builder.setCancelable(!forceUpdate);
        final AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        if (forceUpdate && btnLater != null) {
            btnLater.setVisibility(View.GONE);
        } else if (btnLater != null) {
            btnLater.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnUpdate != null) {
            btnUpdate.setOnClickListener(v -> {
                btnUpdate.setEnabled(false);
                btnUpdate.setText("DOWNLOADING...");
                if (btnLater != null) btnLater.setVisibility(View.GONE);
                if (layoutProgress != null) layoutProgress.setVisibility(View.VISIBLE);

                startDownloadAndInstall(act, apkUrl, pbDownload, tvProgressText, btnUpdate, dialog);
            });
        }

        dialog.show();
    }

    private static void startDownloadAndInstall(final Activity activity, final String apkUrl,
                                                final ProgressBar pb, final TextView tvProgress,
                                                final TextView btnUpdate, final AlertDialog dialog) {
        new Thread(() -> {
            File tempApk = null;
            try {
                URL curUrl = new URL(apkUrl);
                HttpURLConnection conn = null;
                int redirects = 0;
                while (redirects < 6) {
                    conn = (HttpURLConnection) curUrl.openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("User-Agent", "FeaturesticLoader/1.2.6");
                    conn.connect();
                    int code = conn.getResponseCode();
                    if (code == HttpURLConnection.HTTP_MOVED_PERM 
                            || code == HttpURLConnection.HTTP_MOVED_TEMP 
                            || code == 307 
                            || code == 308) {
                        String newLoc = conn.getHeaderField("Location");
                        if (newLoc != null && !newLoc.isEmpty()) {
                            curUrl = new URL(newLoc);
                            redirects++;
                            conn.disconnect();
                            continue;
                        }
                    }
                    break;
                }

                if (conn == null) throw new Exception("Connection failed");
                int length = conn.getContentLength();
                InputStream is = conn.getInputStream();

                File cacheDir = activity.getExternalCacheDir();
                if (cacheDir == null) {
                    cacheDir = activity.getCacheDir();
                }
                File updatesDir = new File(cacheDir, "updates");
                if (!updatesDir.exists()) {
                    updatesDir.mkdirs();
                }

                tempApk = new File(updatesDir, "FeaturesticLoader_Update.apk");
                if (tempApk.exists()) {
                    tempApk.delete();
                }

                FileOutputStream fos = new FileOutputStream(tempApk);
                byte[] buffer = new byte[16384];
                int read;
                long totalCopied = 0;
                long lastUiMs = 0;

                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                    totalCopied += read;
                    long now = System.currentTimeMillis();
                    if (now - lastUiMs >= 80 || (length > 0 && totalCopied >= length)) {
                        lastUiMs = now;
                        final int progress = length > 0 ? (int) ((totalCopied * 100) / length) : 0;
                        final long copiedMb = totalCopied / (1024 * 1024);
                        final long totalMb = length > 0 ? length / (1024 * 1024) : 0;
                        final long currentCopiedKb = totalCopied / 1024;
                        sMainHandler.post(() -> {
                            if (pb != null && progress > 0) pb.setProgress(progress);
                            if (tvProgress != null) {
                                if (totalMb > 0) {
                                    tvProgress.setText("Downloading: " + progress + "% (" + copiedMb + "/" + totalMb + " MB)");
                                } else {
                                    tvProgress.setText("Downloading: " + currentCopiedKb + " KB...");
                                }
                            }
                        });
                    }
                }
                fos.flush();
                fos.close();
                is.close();
                conn.disconnect();

                if (tempApk.length() < 1000000) {
                    throw new Exception("Downloaded file incomplete (" + tempApk.length() + " bytes)");
                }

                final File finalApk = tempApk;
                sMainHandler.post(() -> {
                    try {
                        dialog.dismiss();
                    } catch (Throwable ignored) {}
                    launchApkInstaller(activity, finalApk);
                });

            } catch (Throwable t) {
                Log.e(TAG, "Download failed: " + t.getMessage());
                if (tempApk != null && tempApk.exists()) {
                    tempApk.delete();
                }
                sMainHandler.post(() -> {
                    if (btnUpdate != null) {
                        btnUpdate.setEnabled(true);
                        btnUpdate.setText("RETRY");
                    }
                    if (tvProgress != null) {
                        tvProgress.setText("Download failed: " + (t.getMessage() != null ? t.getMessage() : "Error"));
                    }
                    Toast.makeText(activity, "Download failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "Featurestic-DownloadApk").start();
    }

    private static void launchApkInstaller(Activity activity, File apkFile) {
        if (activity == null || apkFile == null || !apkFile.exists()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri apkUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(
                        activity,
                        activity.getPackageName() + ".provider",
                        apkFile
                );
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to launch installer: " + t.getMessage());
            Toast.makeText(activity, "Installer launch failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
