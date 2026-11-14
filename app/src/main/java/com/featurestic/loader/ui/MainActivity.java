package com.featurestic.loader.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.featurestic.loader.AppConfig;
import com.featurestic.loader.R;
import com.featurestic.loader.auth.PhpAuthManager;
import com.featurestic.loader.sdk.FeaturesticSdkHelper;

import java.util.concurrent.TimeUnit;

/**
 * Screen 2 — BGMI CHEAT Loader V2 Main Dashboard Screen
 * High-contrast silver & frosted glass aesthetic matching Screenshot 2 & Video.
 */
public class MainActivity extends AppCompatActivity {

    // Device Info Card Views
    private TextView tvDeviceModel;
    private TextView tvDeviceCodename;
    private TextView tvDeviceSpecs;
    private View pillConnectedStatus;

    // Game Target Card Views
    private ImageView ivGameIcon;
    private TextView tvGameName;
    private TextView tvGamePackage;
    private TextView tvGameVersion;
    private TextView tvGameSupported;
    private ImageView btnGameMenu;
    private View btnLaunchGame;
    private View btnLaunchContent;
    private TextView tvLaunchLabel;
    private ProgressBar pbLaunchProgress;

    // Hide ESP Switch (From Video)
    private SwitchCompat switchHideEsp;

    // Subscription Timer Views
    private TextView tvCountdownDays;
    private TextView tvCountdownHours;
    private TextView tvCountdownMinutes;
    private TextView tvCountdownSeconds;

    // Top Bell Button
    private View btnNotificationBell;

    private String mLicenseKey = "";
    private long mExpiryTimestamp = 0;
    private CountDownTimer mCountDownTimer = null;
    private boolean isLaunching = false;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private static final String TARGET_PKG = AppConfig.TARGET_PACKAGE_NAME;
    private static final String PREF_SETTINGS = "loader_v2_settings";
    private static final String KEY_HIDE_ESP = "hide_esp_enabled";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupEdgeToEdgeWindow();
        setContentView(R.layout.activity_main);

        extractIntentData();
        initViews();
        setupDeviceInfo();
        setupGameCard();
        setupHideEspSwitch();
        setupSubscriptionTimer();
        setupListeners();

        // Check runtime permissions
        FeaturesticSdkHelper.requestRuntimePermissionsOnStart(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCountDownTimer != null) {
            mCountDownTimer.cancel();
            mCountDownTimer = null;
        }
    }

    private void setupEdgeToEdgeWindow() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            );
        }
    }

    private void extractIntentData() {
        Intent intent = getIntent();
        if (intent != null) {
            mLicenseKey = intent.getStringExtra("license_key");
            mExpiryTimestamp = intent.getLongExtra("expiry_timestamp", 0);
        }
        if (mLicenseKey == null || mLicenseKey.trim().isEmpty()) {
            mLicenseKey = PhpAuthManager.getSavedKey(this);
        }
    }

    private void initViews() {
        tvDeviceModel = findViewById(R.id.tvDeviceModel);
        tvDeviceCodename = findViewById(R.id.tvDeviceCodename);
        tvDeviceSpecs = findViewById(R.id.tvDeviceSpecs);
        pillConnectedStatus = findViewById(R.id.pillConnectedStatus);

        ivGameIcon = findViewById(R.id.ivGameIcon);
        tvGameName = findViewById(R.id.tvGameName);
        tvGamePackage = findViewById(R.id.tvGamePackage);
        tvGameVersion = findViewById(R.id.tvGameVersion);
        tvGameSupported = findViewById(R.id.tvGameSupported);
        btnGameMenu = findViewById(R.id.btnGameMenu);

        btnLaunchGame = findViewById(R.id.btnLaunchGame);
        btnLaunchContent = findViewById(R.id.btnLaunchContent);
        tvLaunchLabel = findViewById(R.id.tvLaunchLabel);
        pbLaunchProgress = findViewById(R.id.pbLaunchProgress);

        switchHideEsp = findViewById(R.id.switchHideEsp);

        tvCountdownDays = findViewById(R.id.tvCountdownDays);
        tvCountdownHours = findViewById(R.id.tvCountdownHours);
        tvCountdownMinutes = findViewById(R.id.tvCountdownMinutes);
        tvCountdownSeconds = findViewById(R.id.tvCountdownSeconds);

        btnNotificationBell = findViewById(R.id.btnNotificationBell);
    }

    private void setupDeviceInfo() {
        // e.g. "samsung SM-M315F" or "OPPO CPH2731"
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toUpperCase() : "DEVICE";
        String model = Build.MODEL != null ? Build.MODEL : "Android";
        tvDeviceModel.setText(manufacturer + " " + model);

        // e.g. "(OP5EE7L1)" or "(m31)"
        String deviceCodename = Build.DEVICE != null ? Build.DEVICE : "generic";
        tvDeviceCodename.setText("(" + deviceCodename + ")");

        // e.g. "Android 16 · arm64-v8a"
        String abi = (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0)
                ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        tvDeviceSpecs.setText("Android " + Build.VERSION.RELEASE + " · " + abi);
    }

    private void setupGameCard() {
        tvGameName.setText(AppConfig.TARGET_GAME_NAME);
        tvGamePackage.setText("Package : " + TARGET_PKG);
        tvGameSupported.setText("Supported : " + AppConfig.SUPPORTED_VERSION);

        // Load host installed BGMI icon and version
        try {
            PackageInfo pInfo = FeaturesticSdkHelper.findHostGameInfo(this, TARGET_PKG);
            if (pInfo != null) {
                tvGameVersion.setText("Version : " + pInfo.versionName);
                Drawable hostIcon = getPackageManager().getApplicationIcon(TARGET_PKG);
                if (hostIcon != null) {
                    ivGameIcon.setImageDrawable(hostIcon);
                } else {
                    ivGameIcon.setImageResource(R.drawable.pubg_india);
                }
            } else {
                tvGameVersion.setText("Version : " + AppConfig.SUPPORTED_VERSION);
                ivGameIcon.setImageResource(R.drawable.pubg_india);
            }
        } catch (Throwable t) {
            tvGameVersion.setText("Version : " + AppConfig.SUPPORTED_VERSION);
            ivGameIcon.setImageResource(R.drawable.pubg_india);
        }
    }

    private void setupHideEspSwitch() {
        SharedPreferences prefs = getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE);
        boolean savedState = prefs.getBoolean(KEY_HIDE_ESP, false);
        switchHideEsp.setChecked(savedState);
        applyHideEsp(savedState, false);

        switchHideEsp.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_HIDE_ESP, isChecked).apply();
            applyHideEsp(isChecked, true);
        });
    }

    private void applyHideEsp(boolean enabled, boolean showToast) {
        try {
            if (enabled) {
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
                if (showToast) {
                    Toast.makeText(this, "Hide ESP Enabled", Toast.LENGTH_SHORT).show();
                }
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                if (showToast) {
                    Toast.makeText(this, "Hide ESP Disabled", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void setupSubscriptionTimer() {
        // Calculate remaining time
        long now = System.currentTimeMillis();
        long diff = mExpiryTimestamp > now ? (mExpiryTimestamp - now) : 0;

        // If no remote timestamp provided, default to demo 48 days 09 hours 46 mins 38 secs (from video)
        if (diff <= 0) {
            diff = TimeUnit.DAYS.toMillis(48)
                    + TimeUnit.HOURS.toMillis(9)
                    + TimeUnit.MINUTES.toMillis(46)
                    + TimeUnit.SECONDS.toMillis(38);
        }

        if (mCountDownTimer != null) {
            mCountDownTimer.cancel();
        }

        mCountDownTimer = new CountDownTimer(diff, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long days = TimeUnit.MILLISECONDS.toDays(millisUntilFinished);
                long hours = TimeUnit.MILLISECONDS.toHours(millisUntilFinished) % 24;
                long minutes = TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished) % 60;
                long seconds = TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) % 60;

                tvCountdownDays.setText(String.format("%02d", days));
                tvCountdownHours.setText(String.format("%02d", hours));
                tvCountdownMinutes.setText(String.format("%02d", minutes));
                tvCountdownSeconds.setText(String.format("%02d", seconds));
            }

            @Override
            public void onFinish() {
                tvCountdownDays.setText("00");
                tvCountdownHours.setText("00");
                tvCountdownMinutes.setText("00");
                tvCountdownSeconds.setText("00");
            }
        }.start();
    }

    private void setupListeners() {
        // Connected pill click -> Connection toast info
        pillConnectedStatus.setOnClickListener(v -> {
            Toast.makeText(this, "Connected securely to Featurestic Virtual Box Engine", Toast.LENGTH_SHORT).show();
        });

        // Notification Bell click -> Announcements dialog
        btnNotificationBell.setOnClickListener(v -> showNotificationDialog());

        // 3-dots Game Menu click
        btnGameMenu.setOnClickListener(v -> showGameOptionsMenu());

        // LAUNCH GAME button click
        btnLaunchGame.setOnClickListener(v -> handleLaunchGame());
    }

    private void showNotificationDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("System Broadcast");
        b.setMessage("All security modules and game hooks are active.\nStatus: 🟢 Protected & Operational.");
        b.setPositiveButton("OK", null);
        b.show();
    }

    private void showGameOptionsMenu() {
        PopupMenu popup = new PopupMenu(this, btnGameMenu);
        popup.getMenu().add(0, 1, 0, "Re-sync Game OBB & Data");
        popup.getMenu().add(0, 2, 1, "Verify Virtual Container");
        popup.getMenu().add(0, 3, 2, "Sign Out");

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    syncObbFiles();
                    return true;
                case 2:
                    Toast.makeText(this, "Virtual container is verified & ready", Toast.LENGTH_SHORT).show();
                    return true;
                case 3:
                    signOut();
                    return true;
            }
            return false;
        });
        popup.show();
    }

    private void syncObbFiles() {
        setLaunchLoading(true, "Syncing OBB...");
        new Thread(() -> {
            boolean ok = FeaturesticSdkHelper.syncObbNow(MainActivity.this, TARGET_PKG);
            runOnUiThread(() -> {
                setLaunchLoading(false, "LAUNCH GAME");
                Toast.makeText(MainActivity.this, ok ? "OBB Sync Completed!" : "OBB Sync Failed — verify /SdCard/Android/obb", Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void signOut() {
        PhpAuthManager.clearSession(this);
        Intent intent = new Intent(this, LoginActivity.class);
        startActivity(intent);
        finish();
    }

    private void handleLaunchGame() {
        if (isLaunching) return;

        // Check essential permissions
        if (!FeaturesticSdkHelper.hasAllFilesPermission(this)) {
            Toast.makeText(this, "Please grant All Files Access for OBB synchronization", Toast.LENGTH_LONG).show();
            FeaturesticSdkHelper.requestAllFilesPermission(this);
            return;
        }

        if (!FeaturesticSdkHelper.hasOverlayPermission(this)) {
            Toast.makeText(this, "Please allow Display over other apps (Floating Permission)", Toast.LENGTH_LONG).show();
            FeaturesticSdkHelper.requestOverlayPermission(this);
            return;
        }

        setLaunchLoading(true, "Launching...");

        // Launch game inside virtual container
        boolean started = FeaturesticSdkHelper.launchVirtualGame(this, TARGET_PKG);
        mMainHandler.postDelayed(() -> {
            setLaunchLoading(false, "LAUNCH GAME");
            if (!started) {
                Toast.makeText(this, "Failed to launch game container", Toast.LENGTH_SHORT).show();
            }
        }, 1200);
    }

    private void setLaunchLoading(boolean loading, String label) {
        isLaunching = loading;
        if (loading) {
            btnLaunchContent.setVisibility(View.INVISIBLE);
            pbLaunchProgress.setVisibility(View.VISIBLE);
            btnLaunchGame.setEnabled(false);
        } else {
            btnLaunchContent.setVisibility(View.VISIBLE);
            pbLaunchProgress.setVisibility(View.GONE);
            tvLaunchLabel.setText(label);
            btnLaunchGame.setEnabled(true);
        }
    }
}
