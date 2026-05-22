package org.chromium.chrome.browser.monitoring;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.ThreadUtils;
import org.chromium.chrome.browser.ChromeActivity;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tabmodel.TabCreatorManager.TabCreator;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.content_public.browser.LoadUrlParams;
import java.util.List;

public class MonitoringManager {
    private static final String TAG = "KiwiMonitoringManager";
    private static final long SESSION_TIMEOUT_MS = 60000L; // Destroy tab after 60 seconds to conserve memory

    public static void spawnSession(final String targetUrl) {
        // Run lookups inside standard Android process state list to find the active ChromeActivity
        ChromeActivity targetActivity = null;
        List<Activity> runningActivities = ApplicationStatus.getRunningActivities();
        
        for (Activity activity : runningActivities) {
            if (activity instanceof ChromeActivity && !activity.isFinishing()) {
                targetActivity = (ChromeActivity) activity;
                break;
            }
        }

        final ChromeActivity chromeActivity = targetActivity;

        // Fallback: If process memory has cleared, start ChromeTabbedActivity silently in background
        if (chromeActivity == null) {
            Log.w(TAG, "ChromeActivity is currently dead. Booting layout in background...");
            try {
                Context context = org.chromium.base.ContextUtils.getApplicationContext();
                Intent relaunchIntent = new Intent();
                relaunchIntent.setClassName(context, "org.chromium.chrome.browser.ChromeTabbedActivity");
                relaunchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(relaunchIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to execute silent bootloader fallback: ", e);
            }
            return;
        }

        // Spawning must occur strictly on Chromium's UI thread
        ThreadUtils.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (chromeActivity.isFinishing() || chromeActivity.isDestroyed()) {
                        Log.w(TAG, "Activity state invalid. Aborting session spawn.");
                        return;
                    }

                    TabCreator tabCreator = chromeActivity.getTabCreator(false);
                    if (tabCreator == null) {
                        Log.w(TAG, "Standard TabCreator is unavailable.");
                        return;
                    }

                    Log.i(TAG, "Creating offscreen crawler session tab for: " + targetUrl);
                    LoadUrlParams urlParams = new LoadUrlParams(targetUrl);
                    
                    // Create the tab using the standard launch type (it is added off-screen)
                    final Tab hiddenTab = tabCreator.createNewTab(urlParams, TabModel.TabLaunchType.FROM_CHROME_UI, null);

                    if (hiddenTab == null) {
                        Log.e(TAG, "Engine returned null when requesting new headless tab.");
                        return;
                    }

                    // Enforce structural tab isolation by disabling standard picture-in-picture triggers
                    hiddenTab.setPictureInPictureEnabled(false);

                    // Schedule automatic tab destruction after the target execution window expires
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            ThreadUtils.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    destroySessionTab(chromeActivity, hiddenTab);
                                }
                            });
                        }
                    }, SESSION_TIMEOUT_MS);

                } catch (Exception e) {
                    Log.e(TAG, "Exception encountered inside UI Thread spawning session: ", e);
                }
            }
        });
    }

    private static void destroySessionTab(ChromeActivity activity, Tab tab) {
        try {
            if (tab != null && tab.isInitialized() && !tab.isClosing()) {
                Log.i(TAG, "Closing completed browser crawler tab: " + tab.getUrl());
                
                // Load clean blank canvas prior to destruction to force immediate renderer cache release
                tab.loadUrl(new LoadUrlParams("about:blank"));
                
                TabModel model = activity.getTabModelSelector().getCurrentModel();
                if (model != null) {
                    model.closeTab(tab);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finalizing session tab destruction: ", e);
        }
    }
}