package com.featurestic.loader;

/**
 * ============================================================================
 * BGMI CHEAT — LOADER V2 CONFIGURATION & BRANDING
 * ============================================================================
 */
public final class AppConfig {

    private AppConfig() {}

    // ------------------------------------------------------------------------
    // 1. BRANDING & UI TEXT (Matches Screenshot 1 & 2)
    // ------------------------------------------------------------------------
    public static final String BRAND_NAME          = "BGMI CHEAT";
    public static final String BRAND_TAGLINE       = "PLAY BEYOND LIMITS";
    public static final String LOGIN_SUBTITLE      = "Welcome back! Please authenticate to continue.";
    public static final String COPYRIGHT_LABEL     = "Copyright @2026 - Featurestic Leaks";
    public static final String VERSION_LABEL       = "Loader (v" + BuildConfig.VERSION_NAME + ")";
    public static final int VERSION_CODE           = BuildConfig.VERSION_CODE;
    public static final String INSTALLED_LABEL     = "Installed: " + BuildConfig.VERSION_NAME;

    // Game Info
    public static final String TARGET_GAME_NAME    = "Battlegrounds India";
    public static final String TARGET_PACKAGE_NAME = "com.pubg.imobile";
    public static final String SUPPORTED_VERSION   = "4.7.0";

    public static final String PKG_BGMI   = "com.pubg.imobile";
    public static final String PKG_GLOBAL = "com.tencent.ig";
    public static final String PKG_KR     = "com.pubg.krmobile";
    public static final String PKG_VN     = "com.vng.pubgmobile";
    public static final String PKG_TW     = "com.rekoo.pubgm";

    // ------------------------------------------------------------------------
    // 2. BACKEND & PANEL AUTHENTICATION SETTINGS
    // ------------------------------------------------------------------------
    public static final String PHP_PANEL_URL       = "https://featurestic-box-panel.vercel.app";
    public static final String SDK_LICENSE_KEY     = "FBVX-X9R2M7K4Q8";
    public static final String PHP_GAME_TAG        = "PUBG";

    // Set to true so LoginActivity shows up and accepts random keys
    public static final boolean ENABLE_PANEL_AUTH  = true;

    public static final boolean ALLOW_DEMO_FALLBACK_WHEN_UNCONFIGURED = false;
    public static final String MASTER_TEST_KEY     = "FEATURESTIC-VIP";

    // ------------------------------------------------------------------------
    // 3. VIRTUAL CONTAINER ENGINE SETTINGS
    // ------------------------------------------------------------------------
    public static final boolean ENABLE_AUTO_120_FPS   = true;
    public static final boolean ENABLE_HIDE_ROOT      = true;
    public static final boolean ENABLE_HIDE_XPOSED    = true;
    public static final boolean ENABLE_LOW_LATENCY    = true;

    public static final String PLUGIN_SO_URL_OR_PATH = "/storage/emulated/0/lib/libDARK.so";

    // Support Telegram Link
    public static final String TELEGRAM_CHANNEL_URL  = "https://t.me/featurestic";
}
