package org.chromium.chrome.browser.monitoring;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import java.util.List;

public class MonitoringService extends Service {
    private static final String TAG = "KiwiMonitoringService";
    private static final String CHANNEL_ID = "KiwiCrawlerServiceChannel";
    private static final int NOTIFICATION_ID = 101;

    private PowerManager.WakeLock mWakeLock;
    private LocalHttpServer mHttpServer;
    private DatabaseHelper mDbHelper;
    private Handler mHandler;
    private Runnable mSchedulerTask;
    private final int mCheckIntervalMs = 30000; // Evaluate schedules every 30 seconds

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Initializing MonitoringService background threads...");

        mDbHelper = new DatabaseHelper(this);
        mHandler = new Handler(Looper.getMainLooper());

        // Launch telemetry loopback receiver
        mHttpServer = new LocalHttpServer(mDbHelper);
        mHttpServer.start();

        // Acquire System WakeLock to bypass device low-power sleep states during active checks
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KiwiMonitoring::WakeLock");
            mWakeLock.acquire();
        }

        // Initialize background scheduling task
        mSchedulerTask = new Runnable() {
            @Override
            public void run() {
                try {
                    evaluateSchedules();
                } catch (Exception e) {
                    Log.e(TAG, "Error in scheduler evaluation cycle: ", e);
                }
                mHandler.postDelayed(this, mCheckIntervalMs);
            }
        };

        mHandler.postDelayed(mSchedulerTask, mCheckIntervalMs);
    }

    private void evaluateSchedules() {
        long currentTime = System.currentTimeMillis();
        List<DatabaseHelper.MonitoredDomain> domains = mDbHelper.getAllDomains();

        for (DatabaseHelper.MonitoredDomain domain : domains) {
            String status = domain.status;

            // Ignore paused or completed domains
            if ("PAUSED".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status)) {
                continue;
            }

            // Case A: End Time Expired (Transition to COMPLETED)
            if (currentTime > domain.endTime) {
                Log.i(TAG, "Domain monitor expired: " + domain.domainName + ". Marking COMPLETED.");
                mDbHelper.updateDomainStatus(domain.domainName, "COMPLETED");
                continue;
            }

            // Case B: Not Started Yet (Start Time lies in the future)
            if (currentTime < domain.startTime) {
                continue;
            }

            // Case C: Active Monitoring Window (Historical or Live check)
            long timeElapsedSinceLastScan = currentTime - domain.lastScanned;
            long intervalThresholdMs = domain.scanInterval * 60000L; // Convert minutes to milliseconds

            if (timeElapsedSinceLastScan >= intervalThresholdMs) {
                Log.i(TAG, "Triggering scan execution for: " + domain.domainName + " (Mode: " + status + ")");
                
                // Update database timestamp before launching browser session
                mDbHelper.updateLastScanned(domain.domainName, currentTime);

                // Construct full target URL scheme
                String targetUrl = "https://" + domain.domainName;

                // Launch headless tab via MonitoringManager
                MonitoringManager.spawnSession(targetUrl);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
                .setContentTitle("Kiwi Platform Monitor Active")
                .setContentText("Continuously checking target sites in headless session...")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true)
                .build();

        // Start foreground with dataSync type matching manifest configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Kiwi Platform Crawl Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows active schedule crawl state alerts.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Destroying MonitoringService background orchestrators...");
        
        if (mHandler != null && mSchedulerTask != null) {
            mHandler.removeCallbacks(mSchedulerTask);
        }

        if (mHttpServer != null) {
            mHttpServer.shutdown();
        }

        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}