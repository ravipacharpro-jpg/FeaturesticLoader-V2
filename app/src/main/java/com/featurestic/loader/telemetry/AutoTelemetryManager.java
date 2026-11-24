package com.featurestic.loader.telemetry;

import com.featurestic.loader.BuildConfig;

import android.app.ActivityManager;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-Time 100% Automatic Telemetry, Crash & Diagnostic Sentinel for FeaturesticLoader-V2.
 * Automatically detects and posts:
 *   1. Uncaught Java exceptions (with sync flush before exit)
 *   2. Native crashes (SIGSEGV, SIGABRT, SIGBUS, SIGILL, debuggerd dumps) via continuous Logcat Sentinel
 *   3. Game process termination / deaths / LMK kills without requiring manual user button clicks
 *   4. UI stalls and ANR watchdogs (> 5.0 seconds)
 *   5. Session starts, game launches, and offline persisted crash recovery
 */
public final class AutoTelemetryManager {

    private static final String TELEMETRY_URL = AppConfig.PHP_PANEL_URL + "/api/telemetry/crash";
    private static final String PERSISTED_CRASH_FILE = "last_auto_crash.log";
    private static final int MAX_BUFFER_LINES = 400;
    private static final ArrayDeque<String> sLogcatRingBuffer = new ArrayDeque<>(MAX_BUFFER_LINES);

    private static final AtomicBoolean sSessionReported = new AtomicBoolean(false);
    private static final AtomicBoolean sWatchdogStarted = new AtomicBoolean(false);
    private static final AtomicBoolean sSentinelStarted = new AtomicBoolean(false);
    private static final AtomicBoolean sGameWatcherActive = new AtomicBoolean(false);

    private static volatile long sLastReportTime = 0;
    private static volatile long sLastSentinelReportTime = 0;

    private AutoTelemetryManager() {}

    /**
     * Initializes background telemetry and starts the automatic ANR watchdog and logcat sentinel.
     * Called on LoaderApp startup.
     */
    public static void init(final Context context) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext();

        // 1. Install UncaughtExceptionHandler to auto-report crashes synchronously before exit
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                reportCrashSync(appCtx, throwable, "Uncaught Exception on " + (thread != null ? thread.getName() : "unknown"));
            } catch (Throwable ignored) {
            } finally {
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, throwable);
                }
            }
        });

        // 2. Check and upload any persisted offline crash from previous session
        checkAndSendPersistedOfflineCrash(appCtx);

        // 3. Send automatic session startup telemetry
        sendSessionStart(appCtx);

        // 4. Start automated ANR Watchdog
        startAnrWatchdog(appCtx);

        // 5. Start real-time background Logcat Crash Sentinel (detects native crashes, LMK, UE4 errors automatically)
        startContinuousLogcatSentinel(appCtx);
    }

    /**
     * Helper to read the current app version name dynamically.
     */
    public static String getAppVersion(Context ctx) {
        try {
            if (ctx != null) {
                return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            }
        } catch (Throwable ignored) {}
        return BuildConfig.VERSION_NAME;
    }

    /**
     * Collects the last N lines of logcat.
     */
    public static String collectLogcat(int maxLines) {
        synchronized (sLogcatRingBuffer) {
            if (!sLogcatRingBuffer.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                int total = sLogcatRingBuffer.size();
                int target = Math.min(total, Math.max(30, maxLines));
                int skip = total - target;
                int idx = 0;
                for (String l : sLogcatRingBuffer) {
                    if (idx++ >= skip) {
                        sb.append(l).append('\n');
                    }
                }
                return sb.toString();
            }
        }
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
     * Continuous background Logcat Sentinel.
     * Listens to logcat output in real-time. Whenever an uncaught native crash,
     * debuggerd trace, LMK kill, ANR, or fatal error occurs, it instantly packages
     * and auto-uploads the diagnostics to the panel without waiting for user action.
     */
    public static void startContinuousLogcatSentinel(final Context context) {
        if (!sSentinelStarted.compareAndSet(false, true)) return;

        Thread sentinelThread = new Thread(() -> {
            while (true) {
                Process process = null;
                BufferedReader reader = null;
                try {
                    process = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "time"});
                    reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (sLogcatRingBuffer) {
                            if (sLogcatRingBuffer.size() >= MAX_BUFFER_LINES) {
                                sLogcatRingBuffer.pollFirst();
                            }
                            sLogcatRingBuffer.addLast(line);
                        }
                        if (isCriticalErrorLine(line)) {
                            handleAutoDetectedCrash(context, line);
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (reader != null) {
                        try { reader.close(); } catch (Throwable ignored) {}
                    }
                    if (process != null) {
                        try { process.destroy(); } catch (Throwable ignored) {}
                    }
                }
                // If stream died or logcat exited, pause briefly and restart
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "AutoTelemetry-Sentinel");
        sentinelThread.setDaemon(true);
        sentinelThread.start();
    }

    /**
     * Matches critical error signatures in logcat lines.
     */
    private static boolean isCriticalErrorLine(String line) {
        if (line == null) return false;
        return line.contains("Fatal signal 11")
                || line.contains("Fatal signal 6")
                || line.contains("Fatal signal 7")
                || line.contains("Fatal signal 4")
                || line.contains("SEGV_ACCERR")
                || line.contains("SEGV_MAPERR")
                || line.contains("*** *** *** *** *** *** *** ***")
                || line.contains("FATAL EXCEPTION")
                || (line.contains("Process com.pubg.imobile") && line.contains("has died"))
                || line.contains("App Died: com.pubg.imobile")
                || (line.contains("Killing") && line.contains("com.pubg.imobile"))
                || line.contains("ANR in com.pubg.imobile")
                || line.contains("ANR in com.featurestic.loader")
                || line.contains("ForegroundServiceDidNotStartInTimeException")
                || line.contains("FEATURESTIC RESCUE: Safely aborting failing call")
                || line.contains("FEATURESTIC RESCUE: Terminating runaway");
    }

    /**
     * Handles an auto-detected crash or error from logcat.
     */
    private static void handleAutoDetectedCrash(final Context context, final String triggerLine) {
        long now = System.currentTimeMillis();
        if (now - sLastSentinelReportTime < 15000) {
            return; // Throttle to 1 report every 15 seconds to avoid server flooding
        }
        sLastSentinelReportTime = now;

        new Thread(() -> {
            try {
                String savedKey = PhpAuthManager.getSavedKey(context);
                String crashSignature = "AUTO_CRASH_DETECTED";
                if (triggerLine.contains("Fatal signal 11") || triggerLine.contains("SEGV_")) {
                    crashSignature = "AUTO_NATIVE_SIGSEGV";
                } else if (triggerLine.contains("Fatal signal 6") || triggerLine.contains("SIGABRT")) {
                    crashSignature = "AUTO_NATIVE_SIGABRT";
                } else if (triggerLine.contains("Fatal signal 7") || triggerLine.contains("SIGBUS")) {
                    crashSignature = "AUTO_NATIVE_SIGBUS";
                } else if (triggerLine.contains("has died") || triggerLine.contains("App Died") || triggerLine.contains("Killing")) {
                    crashSignature = "AUTO_PROCESS_TERMINATED";
                } else if (triggerLine.contains("ANR in")) {
                    crashSignature = "AUTO_ANR_DETECTED";
                } else if (triggerLine.contains("FATAL EXCEPTION")) {
                    crashSignature = "AUTO_JAVA_FATAL_EXCEPTION";
                } else if (triggerLine.contains("FEATURESTIC RESCUE")) {
                    crashSignature = "AUTO_CONTAINER_FAULT_RESCUE";
                }

                StringBuilder sb = new StringBuilder();
                sb.append("=== AUTOMATIC SENTINEL ERROR DETECTION ===\n");
                sb.append("Signature: ").append(crashSignature).append("\n");
                sb.append("Trigger Line: ").append(triggerLine.trim()).append("\n");
                sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
                sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
                sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n");
                sb.append("User Key: ").append(savedKey != null && !savedKey.isEmpty() ? savedKey : "N/A").append("\n\n");
                sb.append("=== RECENT LOGCAT BUFFER (120 Lines) ===\n");
                String logcatData = collectLogcat(120);
                sb.append(logcatData);

                sb.append("\n=== ACTIVE VM THREAD STACKTRACES (FREEZE / DEADLOCK ANALYSIS) ===\n");
                try {
                    for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                        Thread t = entry.getKey();
                        sb.append("Thread [").append(t.getName()).append("] state=").append(t.getState())
                          .append(" daemon=").append(t.isDaemon()).append("\n");
                        for (StackTraceElement ste : entry.getValue()) {
                            sb.append("    at ").append(ste.toString()).append("\n");
                        }
                    }
                } catch (Throwable ignored) {}

                // Persist locally in case process is about to be terminated by OS
                saveCrashToDisk(context, sb.toString());

                // Post to central telemetry panel
                postPayload(crashSignature, "auto_sentinel", savedKey, sb.toString());
            } catch (Throwable ignored) {
            }
        }, "AutoTelemetry-SentinelReporter").start();
    }

    /**
     * Saves crash data locally to disk as a recovery backup.
     */
    private static void saveCrashToDisk(Context context, String content) {
        try {
            File f = new File(context.getFilesDir(), PERSISTED_CRASH_FILE);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Checks if a previously saved crash file exists from an offline session and uploads it.
     */
    private static void checkAndSendPersistedOfflineCrash(final Context context) {
        new Thread(() -> {
            try {
                File f = new File(context.getFilesDir(), PERSISTED_CRASH_FILE);
                if (f.exists() && f.length() > 0) {
                    byte[] data = new byte[(int) Math.min(f.length(), 65536)];
                    try (FileInputStream fis = new FileInputStream(f)) {
                        int read = fis.read(data);
                        if (read > 0) {
                            String content = new String(data, 0, read, StandardCharsets.UTF_8);
                            String savedKey = PhpAuthManager.getSavedKey(context);
                            postPayload("PERSISTED_OFFLINE_CRASH", "auto_sentinel", savedKey, content);
                        }
                    }
                    f.delete();
                }
            } catch (Throwable ignored) {}
        }, "AutoTelemetry-OfflineRecover").start();
    }

    /**
     * Starts monitoring the virtual game process post-launch.
     * Detects if the game exits prematurely or silently fails to boot.
     */
    public static void startGameLifecycleWatcher(final Context context, final String pkg) {
        if (!sGameWatcherActive.compareAndSet(false, true)) return;

        Thread watcher = new Thread(() -> {
            try {
                // Check at T+10 seconds: Did game process actually start?
                Thread.sleep(10000);
                boolean runningAt10s = isGameProcessRunning(context, pkg);
                if (!runningAt10s) {
                    reportEvent(context, "AUTO_GAME_FAILED_TO_START_10S", "launch_warning",
                            "Game process (" + pkg + ") not found in running processes 10s after launch");
                    sGameWatcherActive.set(false);
                    return;
                }

                // Check at T+25 seconds: Did game pass UE4 initialization?
                Thread.sleep(15000);
                boolean runningAt25s = isGameProcessRunning(context, pkg);
                if (!runningAt25s) {
                    reportEvent(context, "AUTO_GAME_INIT_TERMINATION_25S", "launch_warning",
                            "Game process (" + pkg + ") died during early boot (10s-25s window)");
                    sGameWatcherActive.set(false);
                    return;
                }

                // Periodic health poll while game is running
                while (true) {
                    Thread.sleep(5000);
                    if (!isGameProcessRunning(context, pkg)) {
                        // Game process terminated
                        reportEvent(context, "AUTO_GAME_TERMINATED", "auto_lifecycle",
                                "Game process (" + pkg + ") exited or terminated after active run");
                        break;
                    }
                }
            } catch (InterruptedException ignored) {
            } finally {
                sGameWatcherActive.set(false);
            }
        }, "AutoTelemetry-GameWatcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static boolean isGameProcessRunning(Context ctx, String pkg) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
                if (list != null) {
                    for (ActivityManager.RunningAppProcessInfo pi : list) {
                        if (pi.processName != null && (pi.processName.equals(pkg) || pi.processName.contains(pkg) || pi.processName.endsWith(":p0"))) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
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
     * Reports a Java exception automatically to the panel (asynchronously).
     */
    public static void reportCrash(final Context context, final Throwable throwable, final String details) {
        reportCrashSync(context, throwable, details);
    }

    /**
     * Reports a Java exception synchronously with latch wait to guarantee delivery before process death.
     */
    public static void reportCrashSync(final Context context, final Throwable throwable, final String details) {
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

            saveCrashToDisk(context, sb.toString());

            CountDownLatch latch = new CountDownLatch(1);
            new Thread(() -> {
                try {
                    postPayload(throwable != null ? throwable.getClass().getSimpleName() : "Crash", "crash", savedKey, sb.toString());
                } finally {
                    latch.countDown();
                }
            }).start();
            try {
                latch.await(2000, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {}
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
                boolean virtObbExists = com.featurestic.loader.sdk.FileCopyTask.hasValidVirtualObb("com.pubg.imobile")
                        || (virtualObb.exists() && virtualObb.isDirectory() && virtualObb.list() != null && virtualObb.list().length > 0);
                boolean obbTxtExists = obbTxt.exists();

                StringBuilder sb = new StringBuilder();
                sb.append("=== AUTOMATIC LOADER SESSION START ===\n");
                sb.append("Loader Version: ").append(AppConfig.VERSION_LABEL).append(" (Code: ").append(AppConfig.VERSION_CODE).append(")\n");
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

                if (success) {
                    startGameLifecycleWatcher(context, pkg);
                }
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
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                }
                int curTick = tick[0];
                mainHandler.post(tickRunnable);
                try {
                    Thread.sleep(5000);
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
        if (payloadText.length() > 28000) {
            payloadText = "[...older log lines trimmed to fit server limit...]\n" + payloadText.substring(payloadText.length() - 27000);
        }
        long now = System.currentTimeMillis();
        if (now - sLastReportTime < 2000) return; // Prevent excessive rapid duplicate posts
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
            json.put("app_version", BuildConfig.VERSION_NAME);
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
