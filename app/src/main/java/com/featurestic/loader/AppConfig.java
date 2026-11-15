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
    public static final String VERSION_LABEL       = "Loader (v1.0.4)";
    public static final String INSTALLED_LABEL     = "Installed: 1.0.4";

    // Game Info
    public static final String TARGET_GAME_NAME    = "Battlegrounds India";
    public static final String TARGET_PACKAGE_NAME = "com.pubg.imobile";
    public static final String SUPPORTED_VERSION   = "4.6.0";

    public static final String PKG_BGMI   = "com.pubg.imobile";
    public static final String PKG_GLOBAL = "com.tencent.ig";
    public static final String PKG_KR     = "com.pubg.krmobile";
    public static final String PKG_VN     = "com.vng.pubgmobile";
    public static final String PKG_TW     = "com.rekoo.pubgm";

    // ------------------------------------------------------------------------
    // 2. BACKEND & PANEL AUTHENTICATION SETTINGS
    // ------------------------------------------------------------------------
    public static final String PHP_PANEL_URL       = "https://featurestic-key-center.vercel.app/c/ownerravi6130_loader/connect";
    public static final String SDK_LICENSE_KEY     = "FBVIP-SMC154L690";
    public static final String PHP_GAME_TAG        = "PUBG";

    public static final boolean ALLOW_DEMO_FALLBACK_WHEN_UNCONFIGURED = false;
    public static final String MASTER_TEST_KEY     = "FEATURESTIC-VIP";

    // ------------------------------------------------------------------------
    // 3. VIRTUAL CONTAINER ENGINE SETTINGS
    // ------------------------------------------------------------------------
    public static final boolean ENABLE_AUTO_120_FPS   = true;
    public static final boolean ENABLE_HIDE_ROOT      = true;
    public static final boolean ENABLE_HIDE_XPOSED    = true;
    public static final boolean ENABLE_LOW_LATENCY    = true;

    /**
     * Plugin .so URL or Local Path:
     * Set to "PANEL" to automatically load the .so configured dynamically in Featurestic Box Panel Settings!
     */
    public static final String PLUGIN_SO_URL_OR_PATH = "PANEL";

    // Support Telegram Link
    public static final String TELEGRAM_CHANNEL_URL  = "https://t.me/featurestic";
}
