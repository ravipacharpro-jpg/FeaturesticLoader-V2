package com.featurestic.loader;

import android.app.Application;
import android.content.Context;

import com.featurestic.loader.sdk.FeaturesticSdkHelper;
import com.featurestic.loader.telemetry.AutoTelemetryManager;

/**
 * Clean 2-Phase Application Integration with FeaturesticBox-release.aar:
 *   1. attachBaseContext(base) -> unseals Hidden APIs & activates SDK Core Crash Sentinel
 *   2. onCreate() -> initializes virtual services after ContentProviders are ready
 */
public class LoaderApp extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        FeaturesticSdkHelper.attachBaseContext(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        FeaturesticSdkHelper.initEngine(this);
        AutoTelemetryManager.init(this);
    }
}
