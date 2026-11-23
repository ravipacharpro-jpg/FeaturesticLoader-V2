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

                    // STRICT CHECK: Only trigger if server says update available AND local version is strictly older
                    if (hasUpdate && apkUrl != null && !apkUrl.trim().isEmpty() && isNewerVersion(currentVer, latestVer)) {
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

    private static void showUpdateDialog(final Context context, final String latestVer,
                                         final String apkUrl, final String changelog,
                                         final boolean forceUpdate) {
        if (!(context instanceof Activity) || ((Activity) context).isFinishing()) {
            return;
        }
        final Activity act = (Activity) context;

        // Frosted Glass Modern Container
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(60, 50, 60, 50);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E61A1D24")); // Frosted dark glass
        bg.setCornerRadius(32f);
        bg.setStroke(2, Color.parseColor("#334155"));
        root.setBackground(bg);

        // Title
        TextView tvTitle = new TextView(act);
        tvTitle.setText("🚀 New Update Available");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(tvTitle);

        // Version subtitle
        TextView tvSub = new TextView(act);
        tvSub.setText("Version " + latestVer + " is ready to install");
        tvSub.setTextColor(Color.parseColor("#94A3B8"));
        tvSub.setTextSize(13f);
        tvSub.setGravity(Gravity.CENTER_HORIZONTAL);
        tvSub.setPadding(0, 10, 0, 30);
        root.addView(tvSub);

        // Changelog Box
        TextView tvLog = new TextView(act);
        tvLog.setText(changelog != null && !changelog.isEmpty() ? changelog : "Performance optimizations & bug fixes.");
        tvLog.setTextColor(Color.parseColor("#CBD5E1"));
        tvLog.setTextSize(13f);
        tvLog.setPadding(30, 20, 30, 20);

        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(Color.parseColor("#1E293B"));
        logBg.setCornerRadius(16f);
        tvLog.setBackground(logBg);
        root.addView(tvLog);

        // Progress Bar (hidden initially)
        final ProgressBar pbDownload = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        pbDownload.setIndeterminate(false);
        pbDownload.setMax(100);
        pbDownload.setProgress(0);
        pbDownload.setVisibility(View.GONE);
        pbDownload.setPadding(0, 30, 0, 10);
        root.addView(pbDownload);

        final TextView tvProgress = new TextView(act);
        tvProgress.setText("Downloading 0%...");
        tvProgress.setTextColor(Color.parseColor("#38BDF8"));
        tvProgress.setTextSize(12f);
        tvProgress.setGravity(Gravity.CENTER_HORIZONTAL);
        tvProgress.setVisibility(View.GONE);
        root.addView(tvProgress);

        // Action Buttons Row
        LinearLayout buttonRow = new LinearLayout(act);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setPadding(0, 40, 0, 0);
        buttonRow.setWeightSum(forceUpdate ? 1f : 2f);

        final Button btnUpdate = new Button(act);
        btnUpdate.setText("UPDATE NOW");
        btnUpdate.setTextColor(Color.WHITE);
        btnUpdate.setTextSize(14f);
        btnUpdate.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#2563EB")); // Vibrant Blue
        btnBg.setCornerRadius(20f);
        btnUpdate.setBackground(btnBg);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnParams.setMargins(10, 0, 10, 0);
        btnUpdate.setLayoutParams(btnParams);

        AlertDialog.Builder builder = new AlertDialog.Builder(act);
        builder.setView(root);
        builder.setCancelable(!forceUpdate);
        final AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        if (!forceUpdate) {
            Button btnLater = new Button(act);
            btnLater.setText("Later");
            btnLater.setTextColor(Color.parseColor("#94A3B8"));
            btnLater.setTextSize(14f);
            GradientDrawable laterBg = new GradientDrawable();
            laterBg.setColor(Color.parseColor("#334155"));
            laterBg.setCornerRadius(20f);
            btnLater.setBackground(laterBg);

            LinearLayout.LayoutParams laterParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            laterParams.setMargins(10, 0, 10, 0);
            btnLater.setLayoutParams(laterParams);
            btnLater.setOnClickListener(v -> dialog.dismiss());
            buttonRow.addView(btnLater);
        }

        buttonRow.addView(btnUpdate);
        root.addView(buttonRow);

        btnUpdate.setOnClickListener(v -> {
            btnUpdate.setEnabled(false);
            btnUpdate.setText("Downloading...");
            pbDownload.setVisibility(View.VISIBLE);
            tvProgress.setVisibility(View.VISIBLE);

            startDownloadAndInstall(act, apkUrl, pbDownload, tvProgress, btnUpdate, dialog);
        });

        dialog.show();
    }

    private static void startDownloadAndInstall(final Activity activity, final String apkUrl,
                                                final ProgressBar pb, final TextView tvProgress,
                                                final Button btnUpdate, final AlertDialog dialog) {
        new Thread(() -> {
            File tempApk = null;
            try {
                URL url = new URL(apkUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.connect();

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
                byte[] buffer = new byte[8192];
                int read;
                long totalCopied = 0;
                long lastUiMs = 0;

                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                    totalCopied += read;
                    long now = System.currentTimeMillis();
                    if (length > 0 && (now - lastUiMs >= 100 || totalCopied >= length)) {
                        lastUiMs = now;
                        final int progress = (int) ((totalCopied * 100) / length);
                        final long copiedMb = totalCopied / (1024 * 1024);
                        final long totalMb = length / (1024 * 1024);
                        sMainHandler.post(() -> {
                            pb.setProgress(progress);
                            tvProgress.setText("Downloading " + progress + "% (" + copiedMb + "/" + totalMb + " MB)");
                        });
                    }
                }
                fos.flush();
                fos.close();
                is.close();
                conn.disconnect();

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
                    btnUpdate.setEnabled(true);
                    btnUpdate.setText("RETRY");
                    tvProgress.setText("Download failed: " + (t.getMessage() != null ? t.getMessage() : "Error"));
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
