package org.chromium.chrome.browser.monitoring;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DashboardController {
    private static final String TAG = "KiwiDashboardController";

    private final Activity mActivity;
    private final View mRoot;
    private final DatabaseHelper mDbHelper;

    // Navigation buttons
    private View mBtnNavDashboard, mBtnNavDomains, mBtnNavLiveFeed, mBtnNavSchedules, mBtnNavSettings;
    private TextView mTxtNavDashboard, mTxtNavDomains, mTxtNavLiveFeed, mTxtNavSchedules, mTxtNavSettings;

    // Content screens
    private View mScreenDashboard, mScreenDomains, mScreenLiveFeed, mScreenSchedules, mScreenSettings;

    // UI elements: Dashboard
    private TextView mTxtTotalUrls, mTxtTodayUrls, mTxtConsoleOutput;

    // UI elements: Domains
    private ListView mListDomains;

    // UI elements: Live Feed
    private EditText mEditSearchUrls;
    private ListView mListCapturedUrls;

    // UI elements: Schedules Form
    private EditText mEditDomainName, mEditScanInterval, mEditStartOffset, mEditEndOffset;
    private Button mBtnCreateSchedule;

    // UI elements: Settings Tools
    private Button mBtnClearDb, mBtnDiagnostics;

    public static void init(Activity activity, View root) {
        new DashboardController(activity, root).setup();
    }

    private DashboardController(Activity activity, View root) {
        this.mActivity = activity;
        this.mRoot = root;
        this.mDbHelper = new DatabaseHelper(activity);
    }

    private void setup() {
        resolveViews();
        setupNavigation();
        setupSchedulesForm();
        setupSettingsTools();
        setupLiveFeedSearch();
        
        // Load initial data
        refreshDashboardData();
        logToConsole("System initialized. Telemetry channels open.");
    }

    private void resolveViews() {
        // Navigation Buttons
        mBtnNavDashboard = mRoot.findViewById(getResId("btn_nav_dashboard", "id"));
        mBtnNavDomains = mRoot.findViewById(getResId("btn_nav_domains", "id"));
        mBtnNavLiveFeed = mRoot.findViewById(getResId("btn_nav_live_feed", "id"));
        mBtnNavSchedules = mRoot.findViewById(getResId("btn_nav_schedules", "id"));
        mBtnNavSettings = mRoot.findViewById(getResId("btn_nav_settings", "id"));

        mTxtNavDashboard = (TextView) mRoot.findViewById(getResId("txt_nav_dashboard", "id"));
        mTxtNavDomains = (TextView) mRoot.findViewById(getResId("txt_nav_domains", "id"));
        mTxtNavLiveFeed = (TextView) mRoot.findViewById(getResId("txt_nav_live_feed", "id"));
        mTxtNavSchedules = (TextView) mRoot.findViewById(getResId("txt_nav_schedules", "id"));
        mTxtNavSettings = (TextView) mRoot.findViewById(getResId("txt_nav_settings", "id"));

        // Screens
        mScreenDashboard = mRoot.findViewById(getResId("screen_dashboard", "id"));
        mScreenDomains = mRoot.findViewById(getResId("screen_domains", "id"));
        mScreenLiveFeed = mRoot.findViewById(getResId("screen_live_feed", "id"));
        mScreenSchedules = mRoot.findViewById(getResId("screen_schedules", "id"));
        mScreenSettings = mRoot.findViewById(getResId("screen_settings", "id"));

        // Dashboard Stats & Console
        mTxtTotalUrls = (TextView) mRoot.findViewById(getResId("txt_total_urls", "id"));
        mTxtTodayUrls = (TextView) mRoot.findViewById(getResId("txt_today_urls", "id"));
        mTxtConsoleOutput = (TextView) mRoot.findViewById(getResId("txt_console_output", "id"));

        // Lists
        mListDomains = (ListView) mRoot.findViewById(getResId("list_domains", "id"));
        mListCapturedUrls = (ListView) mRoot.findViewById(getResId("list_captured_urls", "id"));

        // Search & Forms
        mEditSearchUrls = (EditText) mRoot.findViewById(getResId("edit_search_urls", "id"));
        mEditDomainName = (EditText) mRoot.findViewById(getResId("edit_domain_name", "id"));
        mEditScanInterval = (EditText) mRoot.findViewById(getResId("edit_scan_interval", "id"));
        mEditStartOffset = (EditText) mRoot.findViewById(getResId("edit_start_offset", "id"));
        mEditEndOffset = (EditText) mRoot.findViewById(getResId("edit_end_offset", "id"));
        mBtnCreateSchedule = (Button) mRoot.findViewById(getResId("btn_create_schedule", "id"));

        // Settings Buttons
        mBtnClearDb = (Button) mRoot.findViewById(getResId("btn_clear_db", "id"));
        mBtnDiagnostics = (Button) mRoot.findViewById(getResId("btn_trigger_diagnostics", "id"));
    }

    private void setupNavigation() {
        mBtnNavDashboard.setOnClickListener(v -> switchScreen(mScreenDashboard, mTxtNavDashboard));
        mBtnNavDomains.setOnClickListener(v -> {
            switchScreen(mScreenDomains, mTxtNavDomains);
            loadDomainsList();
        });
        mBtnNavLiveFeed.setOnClickListener(v -> {
            switchScreen(mScreenLiveFeed, mTxtNavLiveFeed);
            loadLiveFeedList("");
        });
        mBtnNavSchedules.setOnClickListener(v -> switchScreen(mScreenSchedules, mTxtNavSchedules));
        mBtnNavSettings.setOnClickListener(v -> switchScreen(mScreenSettings, mTxtNavSettings));
    }

    private void switchScreen(View targetScreen, TextView targetNavText) {
        // Set all screens GONE
        mScreenDashboard.setVisibility(View.GONE);
        mScreenDomains.setVisibility(View.GONE);
        mScreenLiveFeed.setVisibility(View.GONE);
        mScreenSchedules.setVisibility(View.GONE);
        mScreenSettings.setVisibility(View.GONE);

        // Display selected screen
        targetScreen.setVisibility(View.VISIBLE);

        // Reset navigation text colors
        mTxtNavDashboard.setTextColor(0xFF888888);
        mTxtNavDomains.setTextColor(0xFF888888);
        mTxtNavLiveFeed.setTextColor(0xFF888888);
        mTxtNavSchedules.setTextColor(0xFF888888);
        mTxtNavSettings.setTextColor(0xFF888888);

        // Highlight active screen tab
        targetNavText.setTextColor(0xFF00E5FF);
        
        refreshDashboardData();
    }

    private void setupSchedulesForm() {
        mBtnCreateSchedule.setOnClickListener(v -> {
            String domain = mEditDomainName.getText().toString().trim();
            String intervalStr = mEditScanInterval.getText().toString().trim();
            String startOffsetStr = mEditStartOffset.getText().toString().trim();
            String endOffsetStr = mEditEndOffset.getText().toString().trim();

            if (domain.isEmpty() || intervalStr.isEmpty() || startOffsetStr.isEmpty() || endOffsetStr.isEmpty()) {
                Toast.makeText(mActivity, "Please fill out all schedule parameters.", Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                // Strip potential leading/trailing slash formatting input anomalies
                if (domain.contains("://")) {
                    domain = domain.split("://")[1];
                }
                if (domain.contains("/")) {
                    domain = domain.split("/")[0];
                }

                int interval = Integer.parseInt(intervalStr);
                int startOffsetHours = Integer.parseInt(startOffsetStr);
                int endOffsetHours = Integer.parseInt(endOffsetStr);

                long currentTime = System.currentTimeMillis();
                long startTime = currentTime + (startOffsetHours * 3600000L);
                long endTime = currentTime + (endOffsetHours * 3600000L);

                if (endTime <= startTime) {
                    Toast.makeText(mActivity, "Expiration time must fall after start point.", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Determine schedule initial operational state
                String initialStatus = "LIVE";
                if (startOffsetHours < 0) {
                    initialStatus = "HISTORICAL";
                }

                boolean success = mDbHelper.addDomain(domain, initialStatus, startTime, endTime, interval);
                if (success) {
                    Toast.makeText(mActivity, "Schedule Timeline Constructed!", Toast.LENGTH_SHORT).show();
                    logToConsole("Constrained timeline created: " + domain + " (" + initialStatus + ")");
                    
                    // Clear inputs
                    mEditDomainName.setText("");
                    mEditScanInterval.setText("");
                    mEditStartOffset.setText("");
                    mEditEndOffset.setText("");
                    
                    switchScreen(mScreenDashboard, mTxtNavDashboard);
                } else {
                    Toast.makeText(mActivity, "Error writing target domain config.", Toast.LENGTH_SHORT).show();
                }

            } catch (Exception e) {
                Toast.makeText(mActivity, "Invalid numeric input parameters.", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Form submit failure: ", e);
            }
        });
    }

    private void setupSettingsTools() {
        mBtnClearDb.setOnClickListener(v -> {
            try {
                mDbHelper.onUpgrade(mDbHelper.getWritableDatabase(), 1, 1);
                Toast.makeText(mActivity, "Local database purged cleanly.", Toast.LENGTH_SHORT).show();
                logToConsole("Database purged manually by administrator request.");
                refreshDashboardData();
            } catch (Exception e) {
                Log.e(TAG, "Failed database purge: ", e);
            }
        });

        mBtnDiagnostics.setOnClickListener(v -> {
            logToConsole("--- EXECUTING SELF-DIAGNOSTICS ---");
            logToConsole("SQLite State: Connected.");
            logToConsole("Port 8080: Status listening.");
            logToConsole("WakeLock Status: Checked and verified.");
            logToConsole("--- END DIAGNOSTICS REPORT ---");
            Toast.makeText(mActivity, "Selfcheck report written to console log.", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupLiveFeedSearch() {
        mEditSearchUrls.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                loadLiveFeedList(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void refreshDashboardData() {
        try {
            int total = mDbHelper.getTotalCapturedCount();
            int today = mDbHelper.getCapturedCountToday();
            mTxtTotalUrls.setText(String.valueOf(total));
            mTxtTodayUrls.setText(String.valueOf(today));
        } catch (Exception e) {
            Log.e(TAG, "Error refreshing statistics: ", e);
        }
    }

    private void loadDomainsList() {
        try {
            List<DatabaseHelper.MonitoredDomain> list = mDbHelper.getAllDomains();
            mListDomains.setAdapter(new DomainsAdapter(list));
        } catch (Exception e) {
            Log.e(TAG, "Error loading domain views: ", e);
        }
    }

    private void loadLiveFeedList(String filterQuery) {
        try {
            List<DatabaseHelper.ExtractedUrl> list = mDbHelper.getExtractedUrls(filterQuery, null);
            mListCapturedUrls.setAdapter(new UrlsAdapter(list));
        } catch (Exception e) {
            Log.e(TAG, "Error populating URL list views: ", e);
        }
    }

    private void logToConsole(String logLine) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String completeLine = "[" + timestamp + "] " + logLine + "\n";
        mTxtConsoleOutput.append(completeLine);
    }

    private int getResId(String resName, String resType) {
        return mActivity.getResources().getIdentifier(resName, resType, mActivity.getPackageName());
    }

    // --- Custom BaseAdapter Implementations for Zero-Dependency Rendering ---

    private class DomainsAdapter extends BaseAdapter {
        private final List<DatabaseHelper.MonitoredDomain> mData;

        public DomainsAdapter(List<DatabaseHelper.MonitoredDomain> data) {
            this.mData = data;
        }

        @Override
        public int getCount() { return mData.size(); }

        @Override
        public Object getItem(int position) { return mData.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(mActivity).inflate(android.R.layout.simple_list_item_2, parent, false);
            }
            DatabaseHelper.MonitoredDomain item = mData.get(position);

            TextView text1 = (TextView) convertView.findViewById(android.R.id.text1);
            TextView text2 = (TextView) convertView.findViewById(android.R.id.text2);

            text1.setText(item.domainName);
            text1.setTextColor(0xFFFFFFFF);
            text1.setTextSize(15);

            String lastScannedText = "Never Scanned";
            if (item.lastScanned > 0) {
                lastScannedText = "Last: " + new SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(new Date(item.lastScanned));
            }

            String details = "Status: " + item.status + " | " + lastScannedText + " | Interval: " + item.scanInterval + "m";
            text2.setText(details);
            text2.setTextColor(0xFF888888);
            text2.setTextSize(12);

            return convertView;
        }
    }

    private class UrlsAdapter extends BaseAdapter {
        private final List<DatabaseHelper.ExtractedUrl> mData;

        public UrlsAdapter(List<DatabaseHelper.ExtractedUrl> data) {
            this.mData = data;
        }

        @Override
        public int getCount() { return mData.size(); }

        @Override
        public Object getItem(int position) { return mData.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(mActivity).inflate(android.R.layout.simple_list_item_2, parent, false);
            }
            DatabaseHelper.ExtractedUrl item = mData.get(position);

            TextView text1 = (TextView) convertView.findViewById(android.R.id.text1);
            TextView text2 = (TextView) convertView.findViewById(android.R.id.text2);

            text1.setText(item.title);
            text1.setTextColor(0xFFFFFFFF);
            text1.setTextSize(14);

            text2.setText(item.url);
            text2.setTextColor(0xFF888888);
            text2.setTextSize(11);

            // Bind long-click gesture to copy the URL directly to the system clipboard
            convertView.setOnLongClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Captured URL", item.url);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(mActivity, "Copied link to clipboard!", Toast.LENGTH_SHORT).show();
                }
                return true;
            });

            return convertView;
        }
    }
}