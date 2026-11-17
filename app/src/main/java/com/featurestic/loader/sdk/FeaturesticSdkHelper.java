package com.featurestic.loader.sdk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import com.featurestic.loader.AppConfig;
import com.featurestic.box.core.env.FeaturesticStorageManager;
import com.featurestic.sdk.FeaturesticBox;

import java.io.File;

/**
 * Clean SDK Controller that separates FeaturesticBox Virtual Engine calls from UI code.
 * Handles:
 *   1. Display Over Other Apps (SYSTEM_ALERT_WINDOW / Floating Permission)
 *   2. Android 11–16 All Files Access (MANAGE_EXTERNAL_STORAGE) for /SdCard/Android/obb
 *   3. Android 13+ Notification Permission (POST_NOTIFICATIONS)
 *   4. 4-Layer Host Game Detection & SDK Multi-Layer OBB Sync into /storage/emulated/0/SdCard/Android/obb/<pkg>
 *   5. One-Click Game Launch inside FeaturesticBox with automatic .so plugin staging
 */
public final class FeaturesticSdkHelper {

    private static final String PREF_SETUP = "featurestic_setup_state";

    private FeaturesticSdkHelper() {}

    public static void attachBaseContext(Context base) {
        try {
            FeaturesticBox.doAttachBaseContext(base);
        } catch (Throwable ignored) {
        }
    }

    public static void initEngine(Context context) {
        try {
            clearCachedPluginSoIfDisabled(context);
            FeaturesticBox.setSdkKey(context, AppConfig.SDK_LICENSE_KEY);
            FeaturesticBox.doCreate(context);
            FeaturesticBox.setFeature("hide_root", AppConfig.ENABLE_HIDE_ROOT);
            FeaturesticBox.setFeature("hide_xposed", AppConfig.ENABLE_HIDE_XPOSED);
            FeaturesticBox.setFeature("low_latency_network", AppConfig.ENABLE_LOW_LATENCY);
            if (AppConfig.ENABLE_AUTO_120_FPS) {
                FeaturesticBox.get().setTargetGameFps(120);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Resolves the effective .so plugin URL according to strict priority:
     * 1. Priority 1 (Top): SDK Panel link for this specific user/key (if configured).
     * 2. Priority 2: Fallback to Loader's built-in Git/local link (if AppConfig.PLUGIN_SO_URL_OR_PATH is not empty and not "PANEL").
     */
    public static String resolveEffectivePluginSo(Context context) {
        if (context == null) return "";
        try {
            String remoteSo = FeaturesticBox.getRemotePluginUrl(context);
            if (remoteSo != null && !remoteSo.trim().isEmpty()) {
                return remoteSo.trim();
            }
        } catch (Throwable ignored) {
        }
        String fallback = AppConfig.PLUGIN_SO_URL_OR_PATH;
        if (fallback != null && !fallback.trim().isEmpty() && !"PANEL".equalsIgnoreCase(fallback.trim())) {
            return fallback.trim();
        }
        File localSigner = new File("/storage/emulated/0/Download/libsigner.so");
        if (localSigner.exists() && localSigner.length() > 50000) {
            return localSigner.getAbsolutePath();
        }
        return "";
    }

    /**
     * Computes and verifies SHA-256 anti-tamper hash of a local .so file.
     */
    public static boolean verifyFileSha256(File file, String expectedSha) {
        if (file == null || !file.exists() || expectedSha == null || expectedSha.trim().isEmpty()) {
            return true; // No hash enforcement requested
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream is = new java.io.FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) > 0) {
                    md.update(buffer, 0, read);
                }
            }
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().equalsIgnoreCase(expectedSha.trim());
        } catch (Throwable t) {
            return false;
        }
    }

    private static void clearCachedPluginSoIfDisabled(Context context) {
        if (context == null) return;
        String eff = resolveEffectivePluginSo(context);
        if (eff != null && !eff.trim().isEmpty()) {
            return;
        }
        try {
            File filesDir = context.getFilesDir();
            File noBackupDir = context.getNoBackupFilesDir();
            File sysDir = com.featurestic.box.core.env.BEnvironment.getSystemDir();
            File[] targets = new File[]{
                    new File(sysDir, "pending_plugin_so.conf"),
                    new File(sysDir, "FeaturesticBox.so"),
                    new File(noBackupDir, "native/FeaturesticBox.so"),
                    new File(filesDir, "native/FeaturesticBox.so"),
                    new File(filesDir, "loader/FeaturesticBox.so"),
                    new File(filesDir, "FeaturesticBox.so")
            };
            for (File f : targets) {
                if (f != null && f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            File[] cacheDirs = new File[]{
                    new File(filesDir, "fbox_so_cache"),
                    new File(filesDir, "fbox_so_assets"),
                    new File(noBackupDir, "native"),
                    new File(filesDir, "native")
            };
            for (File d : cacheDirs) {
                if (d != null && d.isDirectory()) {
                    File[] children = d.listFiles();
                    if (children != null) {
                        for (File c : children) {
                            if (c != null && c.isFile() && c.getName().endsWith(".so")) {
                                //noinspection ResultOfMethodCallIgnored
                                c.delete();
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void requestRuntimePermissionsOnStart(Activity activity) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                java.util.List<String> perms = new java.util.ArrayList<>();
                if (Build.VERSION.SDK_INT >= 33) {
                    if (activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                        perms.add("android.permission.POST_NOTIFICATIONS");
                    }
                    if (activity.checkSelfPermission("android.permission.READ_MEDIA_IMAGES") != PackageManager.PERMISSION_GRANTED) {
                        perms.add("android.permission.READ_MEDIA_IMAGES");
                        perms.add("android.permission.READ_MEDIA_VIDEO");
                        perms.add("android.permission.READ_MEDIA_AUDIO");
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    if (activity.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
                        perms.add("android.permission.BLUETOOTH_CONNECT");
                    }
                }
                if (activity.checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
                    perms.add("android.permission.RECORD_AUDIO");
                }
                if (activity.checkSelfPermission("android.permission.READ_PHONE_STATE") != PackageManager.PERMISSION_GRANTED) {
                    perms.add("android.permission.READ_PHONE_STATE");
                }
                if (activity.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
                    perms.add("android.permission.READ_EXTERNAL_STORAGE");
                    perms.add("android.permission.WRITE_EXTERNAL_STORAGE");
                }
                if (!perms.isEmpty()) {
                    activity.requestPermissions(perms.toArray(new String[0]), 1002);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Checks if "Display over other apps" (Float / Overlay) permission is granted.
     */
    public static boolean hasOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT >= 23 && context != null) {
            try {
                return Settings.canDrawOverlays(context);
            } catch (Throwable ignored) {
                return true;
            }
        }
        return true;
    }

    /**
     * Opens the system "Display over other apps" settings screen for this Loader.
     */
    public static void requestOverlayPermission(Activity activity) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName())
                );
                activity.startActivityForResult(intent, 1003);
            } catch (Throwable ignored) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    activity.startActivityForResult(intent, 1003);
                } catch (Throwable ignored2) {}
            }
        }
    }

    /**
     * Checks if All Files Access (Android 11+) or Legacy Storage Permission (Android 10) is granted.
     */
    public static boolean hasAllFilesPermission(Context context) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                return Environment.isExternalStorageManager();
            } catch (Throwable ignored) {
                return true;
            }
        }
        if (Build.VERSION.SDK_INT >= 23 && context != null) {
            return context.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /**
     * Checks if "Install unknown apps" (REQUEST_INSTALL_PACKAGES) permission is granted.
     * Required on Android 8.0+ to install APKs from this Loader.
     */
    public static boolean canRequestPackageInstalls(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context != null) {
            try {
                return context.getPackageManager()
                        .canRequestPackageInstalls();
            } catch (Throwable ignored) {
                return true;
            }
        }
        return true;
    }

    /**
     * Opens the "Install unknown apps" settings screen for this Loader,
     * so the user can allow installing APKs from this app.
     */
    public static void requestInstallPackagesPermission(Activity activity) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivityForResult(intent, 1004);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Opens the Android 11+ "Allow access to manage all files" settings screen for this Loader,
     * or requests READ/WRITE_EXTERNAL_STORAGE on Android 10.
     */
    public static void requestAllFilesPermission(Activity activity) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivityForResult(intent, 1001);
                return;
            } catch (Throwable ignored) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    activity.startActivityForResult(intent, 1001);
                    return;
                } catch (Throwable ignored2) {}
            }
        }
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                activity.requestPermissions(new String[]{
                        "android.permission.READ_EXTERNAL_STORAGE",
                        "android.permission.WRITE_EXTERNAL_STORAGE"
                }, 1001);
            } catch (Throwable ignored) {}
        }
    }

    public static PackageInfo findHostGameInfo(Context context, String packageName) {
        return FeaturesticBox.findHostGameInfo(context, packageName);
    }

    public static boolean isInstalledOnHost(Context context, String packageName) {
        return FeaturesticBox.isGameInstalledOnHost(context, packageName);
    }

    public static boolean hasHostObbFiles(Context context, String packageName) {
        return !FileCopyTask.findSourceObbFiles(context, packageName).isEmpty();
    }

    public static boolean hasVirtualObbFiles(String packageName) {
        return FileCopyTask.hasValidVirtualObb(packageName);
    }

    public static boolean isGameSetupReady(final Context context, final String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return false;
        if (!isInstalledOnHost(context, packageName)) return false;

        boolean installedInBox = false;
        try {
            installedInBox = FeaturesticBox.isInstalled(packageName, 0);
        } catch (Throwable ignored) {
        }
        boolean hasVirtObb = hasVirtualObbFiles(packageName);
        return installedInBox && hasVirtObb;
    }

    public static void markGameSetupState(Context context, String packageName, boolean completed) {
        SharedPreferences sp = context.getSharedPreferences(PREF_SETUP, Context.MODE_PRIVATE);
        sp.edit().putBoolean("setup_done_" + packageName, completed).apply();
    }

    public static void setupGameEnvironment(final Context context, final String packageName, final FeaturesticBox.SetupCallback callback) {
        final Context appCtx = context != null ? context.getApplicationContext() : null;
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            try {
                if (callback != null) {
                    mainHandler.post(() -> callback.onProgress(15,
                            "Step 1/3 • Initializing Virtual Container...",
                            "Preparing sandbox for " + packageName));
                }

                if (appCtx != null) {
                    com.featurestic.box.FeaturesticBoxCore.get().ensureInitialized(appCtx);
                    try {
                        com.featurestic.box.utils.compat.CrashShieldAndResourceHealer
                                .healGameResources(appCtx, packageName, 0);
                    } catch (Throwable ignored) {
                    }
                }

                try {
                    String effSo = resolveEffectivePluginSo(appCtx);
                    if (!effSo.isEmpty()) {
                        FeaturesticBox.loadCustomLibrary(effSo);
                    }
                } catch (Throwable ignored) {
                }

                if (callback != null) {
                    mainHandler.post(() -> callback.onProgress(35,
                            "Step 2/3 • Installing Game & Syncing OBB...",
                            "Cloning APK and copying OBB to /SdCard/Android/obb/" + packageName));
                }

                FileCopyTask task = new FileCopyTask(appCtx);
                task.installOrFixPkg(packageName, (percent, copiedBytes, totalBytes, fileName) -> {
                    if (callback != null) {
                        int overallPct = 40 + (percent * 55 / 100);
                        long copiedMb = copiedBytes / (1024L * 1024L);
                        long totalMb = Math.max(1L, totalBytes / (1024L * 1024L));
                        mainHandler.post(() -> callback.onProgress(overallPct,
                                "Step 2/3 • Copying OBB (" + percent + "%) • " + copiedMb + " / " + totalMb + " MB",
                                fileName));
                    }
                }, (obbSuccess, obbMessage) -> {
                    new Thread(() -> {
                        boolean installed = false;
                        try {
                            installed = FeaturesticBox.isInstalled(packageName, 0);
                        } catch (Throwable ignored) {
                        }
                        boolean obbReady = hasVirtualObbFiles(packageName);

                        if (installed && obbReady) {
                            try {
                                com.featurestic.box.core.env.FeaturesticStorageManager
                                        .syncDataToVirtualIfNeeded(packageName);
                                if (AppConfig.ENABLE_AUTO_120_FPS) {
                                    com.featurestic.box.FeaturesticBoxCore.get().setTargetGameFps(120);
                                }
                                String effPlugin = resolveEffectivePluginSo(appCtx);
                                if (appCtx != null && !effPlugin.isEmpty()) {
                                    try {
                                        java.lang.reflect.Method m = com.featurestic.box.core.RNative.class.getMethod("preparePendingPluginOnWorkerThread", Context.class);
                                        m.invoke(null, appCtx);
                                    } catch (Throwable ignoredPlugin) {
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }

                        final boolean finalSuccess = installed && obbReady;
                        final String finalMsg;
                        if (finalSuccess) {
                            finalMsg = obbMessage != null ? obbMessage : "Game environment ready";
                        } else if (!installed) {
                            finalMsg = obbMessage != null ? obbMessage : "APK clone failed";
                        } else {
                            finalMsg = obbMessage != null ? obbMessage : "OBB copy failed";
                        }

                        if (appCtx != null) {
                            markGameSetupState(appCtx, packageName, finalSuccess);
                        }

                        mainHandler.post(() -> {
                            if (callback != null) {
                                if (finalSuccess) {
                                    callback.onProgress(100, "Setup Complete!",
                                            "APK & OBB synced in /SdCard/Android/obb/" + packageName);
                                }
                                callback.onComplete(finalSuccess, finalMsg);
                            }
                        });
                    }, "Featurestic-Setup-Finalize").start();
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onComplete(false, t.getMessage() != null ? t.getMessage() : "Setup error");
                    }
                });
            }
        }, "Featurestic-Setup-Runner").start();
    }

    /**
     * Launch the virtualized game.
     * Ensures APK is installed in FeaturesticBoxCore and OBB is copied via FileChannel.transferTo()
     * into /storage/emulated/0/SdCard/Android/obb/<pkg>/ before launching.
     */
    public static boolean launchVirtualGame(Context context, String packageName) {
        try {
            String savedKey = com.featurestic.loader.auth.PhpAuthManager.getSavedKey(context);
            if (savedKey != null && !savedKey.isEmpty()) {
                com.featurestic.loader.auth.PhpAuthManager.syncLicenseFileForPlugin(savedKey);
            }
        } catch (Throwable ignored) {
        }

        final Context ctx = context.getApplicationContext();
        final String pkg = packageName;
        final Activity act = (context instanceof Activity) ? (Activity) context : null;

        if (act != null && !hasAllFilesPermission(ctx)) {
            act.runOnUiThread(() -> Toast.makeText(ctx,
                    "All Files Access required to copy OBB. Grant permission in Settings.",
                    Toast.LENGTH_LONG).show());
            requestAllFilesPermission(act);
            return false;
        }

        new Thread(() -> {
            try {
                boolean installed = false;
                try {
                    installed = FeaturesticBox.isInstalled(pkg, 0);
                } catch (Throwable ignored) {
                }

                boolean obbReady = hasVirtualObbFiles(pkg);
                if (!installed || !obbReady) {
                    FileCopyTask task = new FileCopyTask(ctx);
                    StringBuilder errOut = new StringBuilder();
                    boolean fixed = task.installOrFixPkgSync(pkg, null, errOut);
                    obbReady = hasVirtualObbFiles(pkg);
                    installed = FeaturesticBox.isInstalled(pkg, 0);
                    if (!fixed || !installed || !obbReady) {
                        final String msg = errOut.length() > 0
                                ? errOut.toString()
                                : "OBB copy failed — check /storage/emulated/0/Android/obb/" + pkg;
                        if (act != null) {
                            act.runOnUiThread(() -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
                        }
                        return;
                    }
                }

                clearCachedPluginSoIfDisabled(ctx);
                String soTarget = resolveEffectivePluginSo(ctx);

                // .so Anti-Tamper SHA-256 Checksum Enforcement
                try {
                    SharedPreferences secPrefs = ctx.getSharedPreferences("featurestic_so_security", Context.MODE_PRIVATE);
                    String expectedSha = secPrefs.getString("server_so_sha256", "").trim();
                    if (!expectedSha.isEmpty() && soTarget != null && !soTarget.startsWith("http://") && !soTarget.startsWith("https://")) {
                        File localSoFile = new File(soTarget);
                        if (localSoFile.exists() && !verifyFileSha256(localSoFile, expectedSha)) {
                            localSoFile.delete();
                            android.util.Log.e("FeaturesticSO", "SECURITY ABORT: .so SHA-256 checksum mismatch!");
                            if (act != null) {
                                act.runOnUiThread(() -> Toast.makeText(ctx,
                                        "Security Alert: .so Anti-Tamper Checksum Failed! Modified file blocked.",
                                        Toast.LENGTH_LONG).show());
                            }
                            return;
                        }
                    }
                } catch (Throwable ignored) {
                }

                android.util.Log.d("FeaturesticSO", "Launching game with effective .so: " + (soTarget.isEmpty() ? "NONE" : soTarget));
                boolean launched = FeaturesticBox.startGame(ctx, pkg, 0, soTarget);
                android.util.Log.d("FeaturesticOBB", "startGame launched=" + launched);
                if (!launched && act != null) {
                    act.runOnUiThread(() -> Toast.makeText(ctx,
                            "Failed to launch " + pkg + " inside Virtual Box",
                            Toast.LENGTH_LONG).show());
                }
            } catch (Throwable t) {
                android.util.Log.e("FeaturesticOBB", "launchVirtualGame error: " + t.getMessage());
                if (act != null) {
                    act.runOnUiThread(() -> Toast.makeText(ctx,
                            "Launch error: " + (t.getMessage() != null ? t.getMessage() : "Unknown"),
                            Toast.LENGTH_LONG).show());
                }
            }
        }, "Featurestic-Game-Launch").start();

        return true;
    }

    /**
     * Standalone OBB sync — copies host OBB into /SdCard/Android/obb/<pkg>.
     */
    public static boolean syncObbNow(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return false;
        FileCopyTask task = new FileCopyTask(context);
        return task.installOrFixPkgSync(packageName, null, null);
    }
}
