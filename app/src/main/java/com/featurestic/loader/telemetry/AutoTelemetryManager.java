package com.featurestic.loader.telemetry;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import com.featurestic.loader.AppConfig;
import com.featurestic.loader.auth.PhpAuthManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-Time Automatic Telemetry & Diagnostic Reporter for FeaturesticLoader-V2.
 * Automatically posts session starts, game launch events, ANR watchdogs, and diagnostic logs
 * directly to the central Vercel / Neon DB telemetry panel without requiring manual button clicks.
 */
public final class AutoTelemetryManager {

    private static final String TELEMETRY_URL = AppConfig.PHP_PANEL_URL + "/api/telemetry/crash";
    private static final AtomicBoolean sSessionReported = new AtomicBoolean(false);
    private static final AtomicBoolean sWatchdogStarted = new AtomicBoolean(false);
    private static volatile long sLastReportTime = 0;

    private AutoTelemetryManager() {}

    /**
     * Initializes background telemetry and starts the automatic ANR watchdog.
     * Called on LoaderApp startup.
     */
    public static void init(final Context context) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext();

        // 1. Install UncaughtExceptionHandler to auto-report crashes without user interaction
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                reportCrash(appCtx, throwable, "Uncaught Exception on " + (thread != null ? thread.getName() : "unknown"));
            } catch (Throwable ignored) {
            } finally {
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, throwable);
                }
            }
        });

        // 2. Send automatic session startup telemetry
        sendSessionStart(appCtx);

        // 3. Start automated ANR Watchdog
        startAnrWatchdog(appCtx);
    }

    /**
     * Collects the last N lines of logcat.
     */
    public static String collectLogcat(int maxLines) {
        try {
            int lines = Math.max(30, Math.min(maxLines, 300));
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-t", String.valueOf(lines), "-v", "time"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            long start = System.currentTimeMillis();
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                if (System.currentTimeMillis() - start > 2000) {
                    break;
                }
            }
            reader.close();
            p.destroy();
            return sb.toString();
        } catch (Throwable t) {
            return "[Logcat unavailable: " + t.getMessage() + "]";
        }
    }

    /**
     * Reports an arbitrary event or diagnostic log automatically.
     */
    public static void reportEvent(final Context context, final String eventType, final String category, final String details) {
        new Thread(() -> {
            try {
                String savedKey = PhpAuthManager.getSavedKey(context);
                StringBuilder sb = new StringBuilder();
                sb.append("=== AUTOMATIC TELEMETRY EVENT ===\n");
                sb.append("Event: ").append(eventType).append("\n");
                sb.append("Details: ").append(details != null ? details : "").append("\n");
                sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
                sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n\n");
                sb.append("=== RECENT LOGCAT (150 Lines) ===\n");
                sb.append(collectLogcat(150));

                postPayload(eventType, category != null ? category : "auto_telemetry", savedKey, sb.toString());
            } catch (Throwable ignored) {
            }
        }, "AutoTelemetry-Event").start();
    }

    /**
     * Reports a Java exception automatically to the panel.
     */
    public static void reportCrash(final Context context, final Throwable throwable, final String details) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            if (throwable != null) {
                throwable.printStackTrace(pw);
            }
            String stacktrace = sw.toString();

            String savedKey = PhpAuthManager.getSavedKey(context);
            StringBuilder sb = new StringBuilder();
            sb.append("=== AUTOMATIC CRASH REPORT ===\n");
            sb.append("Details: ").append(details != null ? details : "Exception").append("\n");
            sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
            sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n\n");
            sb.append("--- EXCEPTION STACKTRACE ---\n");
            sb.append(stacktrace).append("\n\n");
            sb.append("=== RECENT LOGCAT (200 Lines) ===\n");
            sb.append(collectLogcat(200));

            postPayload(throwable != null ? throwable.getClass().getSimpleName() : "Crash", "crash", savedKey, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /**
     * Sends an automatic Session Start report on every app launch.
     */
    public static void sendSessionStart(final Context context) {
        if (!sSessionReported.compareAndSet(false, true)) return;

        new Thread(() -> {
            try {
                String savedKey = PhpAuthManager.getSavedKey(context);

                // Check OBB status
                File hostObb = new File(Environment.getExternalStorageDirectory(), "Android/obb/com.pubg.imobile");
                File virtualObb = new File(Environment.getExternalStorageDirectory(), "SdCard/Android/obb/com.pubg.imobile");
                File obbTxt = new File("/storage/emulated/0/obb.txt");

                boolean hostObbExists = hostObb.exists() && hostObb.isDirectory() && hostObb.list() != null && hostObb.list().length > 0;
                boolean virtObbExists = virtualObb.exists() && virtualObb.isDirectory() && virtualObb.list() != null && virtualObb.list().length > 0;
                boolean obbTxtExists = obbTxt.exists();

                StringBuilder sb = new StringBuilder();
                sb.append("=== AUTOMATIC LOADER SESSION START ===\n");
                sb.append("Loader Version: ").append(AppConfig.VERSION_LABEL).append(" (Code: 31)\n");
                sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n");
                sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
                sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n");
                sb.append("User Key: ").append(savedKey != null && !savedKey.isEmpty() ? savedKey : "None/Not Authenticated").append("\n");
                sb.append("Storage AllFiles Access: ").append(Environment.isExternalStorageManager() ? "GRANTED" : "DENIED").append("\n");
                sb.append("Host OBB (/Android/obb/com.pubg.imobile): ").append(hostObbExists ? "PRESENT" : "MISSING").append("\n");
                sb.append("Virtual OBB (/SdCard/Android/obb/...): ").append(virtObbExists ? "PRESENT" : "MISSING").append("\n");
                sb.append("Auto OBB TXT (/storage/emulated/0/obb.txt): ").append(obbTxtExists ? "FOUND" : "NOT FOUND").append("\n\n");
                sb.append("=== INITIAL LOGCAT BUFFER (150 Lines) ===\n");
                sb.append(collectLogcat(150));

                postPayload("LOADER_LAUNCH", "session", savedKey, sb.toString());
            } catch (Throwable ignored) {
            }
        }, "AutoTelemetry-Session").start();
    }

    /**
     * Sends an automatic telemetry event when the user launches the virtual game.
     */
    public static void sendGameLaunch(final Context context, final String pkg, final boolean success, final String details) {
        new Thread(() -> {
            try {
                String savedKey = PhpAuthManager.getSavedKey(context);
                StringBuilder sb = new StringBuilder();
                sb.append("=== AUTOMATIC GAME LAUNCH EVENT ===\n");
                sb.append("Target Package: ").append(pkg).append("\n");
                sb.append("Status: ").append(success ? "SUCCESS / INITIATED" : "FAILED").append("\n");
                sb.append("Details: ").append(details != null ? details : "").append("\n");
                sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
                sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n\n");
                sb.append("=== RECENT LOGCAT BUFFER (200 Lines) ===\n");
                sb.append(collectLogcat(200));

                postPayload(success ? "GAME_LAUNCH_INITIATED" : "GAME_LAUNCH_FAILED", "launch", savedKey, sb.toString());
            } catch (Throwable ignored) {
            }
        }, "AutoTelemetry-Launch").start();
    }

    /**
     * Automatic ANR Watchdog for the Loader process.
     * Periodically tests MainLooper responsiveness; dumps backtraces + logcat if stalled > 5.0 seconds.
     */
    public static void startAnrWatchdog(final Context context) {
        if (!sWatchdogStarted.compareAndSet(false, true)) return;

        Thread watchdog = new Thread(() -> {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            final int[] tick = new int[]{0};
            final Runnable tickRunnable = () -> tick[0]++;
            long lastReport = 0;

            while (true) {
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException e) {
                    break;
                }
                int curTick = tick[0];
                mainHandler.post(tickRunnable);
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException e) {
                    break;
                }
                if (tick[0] == curTick) {
                    long now = System.currentTimeMillis();
                    if (now - lastReport > 45000) { // Limit to 1 ANR report per 45s
                        lastReport = now;
                        try {
                            String savedKey = PhpAuthManager.getSavedKey(context);
                            StringBuilder sb = new StringBuilder();
                            sb.append("=== AUTOMATIC ANR DETECTED: LOADER UI FROZEN ===\n");
                            sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
                            sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n");

                            sb.append("--- MAIN THREAD STACKTRACE ---\n");
                            Thread mainThread = Looper.getMainLooper().getThread();
                            for (StackTraceElement ste : mainThread.getStackTrace()) {
                                sb.append("    at ").append(ste.toString()).append("\n");
                            }

                            sb.append("\n--- ALL ACTIVE THREADS ---\n");
                            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                                Thread t = entry.getKey();
                                if (t == mainThread) continue;
                                sb.append("Thread: ").append(t.getName()).append(" (state=").append(t.getState()).append(")\n");
                                for (StackTraceElement ste : entry.getValue()) {
                                    sb.append("    at ").append(ste.toString()).append("\n");
                                }
                            }

                            sb.append("\n=== RECENT LOGCAT BUFFER (200 Lines) ===\n");
                            sb.append(collectLogcat(200));

                            postPayload("ANR_LOADER_FROZEN", "anr_watchdog", savedKey, sb.toString());
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }, "AutoTelemetry-AnrWatchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * Low-level HTTP POST to /api/telemetry/crash.
     */
    public static void postPayload(String crashType, String crashCategory, String userKey, String payloadText) {
        if (payloadText == null || payloadText.trim().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - sLastReportTime < 3000) return; // Prevent excessive rapid posts
        sLastReportTime = now;

        try {
            URL url = new URL(TELEMETRY_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("brand", Build.BRAND != null ? Build.BRAND : "Unknown");
            json.put("model", Build.MODEL != null ? Build.MODEL : "Unknown");
            json.put("android_version", Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "Unknown");
            json.put("sdk_int", Build.VERSION.SDK_INT);
            json.put("app_version", "1.3.1");
            json.put("crash_type", crashType != null ? crashType : "Telemetry_Event");
            json.put("crash_category", crashCategory != null ? crashCategory : "auto_telemetry");
            json.put("stacktrace", payloadText);
            if (userKey != null && !userKey.trim().isEmpty()) {
                json.put("user_key", userKey.trim());
            }

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            conn.getResponseCode();
            conn.disconnect();
        } catch (Throwable ignored) {
        }
    }
}
