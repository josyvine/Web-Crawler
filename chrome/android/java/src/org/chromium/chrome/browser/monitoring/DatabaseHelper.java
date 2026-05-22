package org.chromium.chrome.browser.monitoring;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "crawler_intelligence.db";
    private static final int DATABASE_VERSION = 1;

    // Table names
    public static final String TABLE_DOMAINS = "domains";
    public static final String TABLE_URLS = "extracted_urls";

    // Domains table columns
    public static final String COL_DOM_ID = "id";
    public static final String COL_DOM_NAME = "domain_name";
    public static final String COL_DOM_STATUS = "status"; // "HISTORICAL", "LIVE", "PAUSED", "COMPLETED"
    public static final String COL_DOM_START_TIME = "start_time";
    public static final String COL_DOM_END_TIME = "end_time";
    public static final String COL_DOM_INTERVAL = "scan_interval";
    public static final String COL_DOM_LAST_SCANNED = "last_scanned";

    // Extracted URLs table columns
    public static final String COL_URL_ID = "id";
    public static final String COL_URL_VAL = "url";
    public static final String COL_URL_TITLE = "title";
    public static final String COL_URL_TIMESTAMP = "timestamp";
    public static final String COL_URL_SOURCE = "source_domain";

    public static class MonitoredDomain {
        public int id;
        public String domainName;
        public String status;
        public long startTime;
        public long endTime;
        public int scanInterval;
        public long lastScanned;
    }

    public static class ExtractedUrl {
        public int id;
        public String url;
        public String title;
        public long timestamp;
        public String sourceDomain;
    }

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createDomainsTable = "CREATE TABLE " + TABLE_DOMAINS + " ("
                + COL_DOM_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_DOM_NAME + " TEXT UNIQUE, "
                + COL_DOM_STATUS + " TEXT, "
                + COL_DOM_START_TIME + " INTEGER, "
                + COL_DOM_END_TIME + " INTEGER, "
                + COL_DOM_INTERVAL + " INTEGER, "
                + COL_DOM_LAST_SCANNED + " INTEGER"
                + ")";

        String createUrlsTable = "CREATE TABLE " + TABLE_URLS + " ("
                + COL_URL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_URL_VAL + " TEXT UNIQUE, "
                + COL_URL_TITLE + " TEXT, "
                + COL_URL_TIMESTAMP + " INTEGER, "
                + COL_URL_SOURCE + " TEXT"
                + ")";

        db.execSQL(createDomainsTable);
        db.execSQL(createUrlsTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_DOMAINS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_URLS);
        onCreate(db);
    }

    // --- Domain CRUD Operations ---

    public boolean addDomain(String domainName, String status, long startTime, long endTime, int interval) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_DOM_NAME, domainName);
        values.put(COL_DOM_STATUS, status);
        values.put(COL_DOM_START_TIME, startTime);
        values.put(COL_DOM_END_TIME, endTime);
        values.put(COL_DOM_INTERVAL, interval);
        values.put(COL_DOM_LAST_SCANNED, 0L);

        long result = db.insertWithOnConflict(TABLE_DOMAINS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        return result != -1;
    }

    public List<MonitoredDomain> getAllDomains() {
        List<MonitoredDomain> list = new ArrayList<>();
        String selectQuery = "SELECT * FROM " + TABLE_DOMAINS + " ORDER BY " + COL_DOM_NAME + " ASC";
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(selectQuery, null);

        if (cursor.moveToFirst()) {
            do {
                MonitoredDomain domain = new MonitoredDomain();
                domain.id = cursor.getInt(cursor.getColumnIndex(COL_DOM_ID));
                domain.domainName = cursor.getString(cursor.getColumnIndex(COL_DOM_NAME));
                domain.status = cursor.getString(cursor.getColumnIndex(COL_DOM_STATUS));
                domain.startTime = cursor.getLong(cursor.getColumnIndex(COL_DOM_START_TIME));
                domain.endTime = cursor.getLong(cursor.getColumnIndex(COL_DOM_END_TIME));
                domain.scanInterval = cursor.getInt(cursor.getColumnIndex(COL_DOM_INTERVAL));
                domain.lastScanned = cursor.getLong(cursor.getColumnIndex(COL_DOM_LAST_SCANNED));
                list.add(domain);
            } while (cursor.moveToNext());
        }
        cursor.close();
        return list;
    }

    public void updateDomainStatus(String domainName, String status) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_DOM_STATUS, status);
        db.update(TABLE_DOMAINS, values, COL_DOM_NAME + " = ?", new String[]{domainName});
    }

    public void updateLastScanned(String domainName, long timestamp) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_DOM_LAST_SCANNED, timestamp);
        db.update(TABLE_DOMAINS, values, COL_DOM_NAME + " = ?", new String[]{domainName});
    }

    public void deleteDomain(String domainName) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_DOMAINS, COL_DOM_NAME + " = ?", new String[]{domainName});
    }

    // --- Extracted URLs CRUD Operations ---

    public boolean addExtractedUrl(String url, String title, long timestamp, String sourceDomain) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_URL_VAL, url);
        values.put(COL_URL_TITLE, title);
        values.put(COL_URL_TIMESTAMP, timestamp);
        values.put(COL_URL_SOURCE, sourceDomain);

        long result = db.insertWithOnConflict(TABLE_URLS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        return result != -1;
    }

    public List<ExtractedUrl> getExtractedUrls(String query, String domainFilter) {
        List<ExtractedUrl> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        
        StringBuilder selection = new StringBuilder("1=1");
        List<String> argsList = new ArrayList<>();

        if (query != null && !query.trim().isEmpty()) {
            selection.append(" AND (").append(COL_URL_VAL).append(" LIKE ? OR ").append(COL_URL_TITLE).append(" LIKE ?)");
            argsList.add("%" + query + "%");
            argsList.add("%" + query + "%");
        }

        if (domainFilter != null && !domainFilter.trim().isEmpty()) {
            selection.append(" AND ").append(COL_URL_SOURCE).append(" = ?");
            argsList.add(domainFilter);
        }

        String[] selectionArgs = argsList.isEmpty() ? null : argsList.toArray(new String[0]);
        String orderBy = COL_URL_TIMESTAMP + " DESC";

        Cursor cursor = db.query(TABLE_URLS, null, selection.toString(), selectionArgs, null, null, orderBy);

        if (cursor.moveToFirst()) {
            do {
                ExtractedUrl url = new ExtractedUrl();
                url.id = cursor.getInt(cursor.getColumnIndex(COL_URL_ID));
                url.url = cursor.getString(cursor.getColumnIndex(COL_URL_VAL));
                url.title = cursor.getString(cursor.getColumnIndex(COL_URL_TITLE));
                url.timestamp = cursor.getLong(cursor.getColumnIndex(COL_URL_TIMESTAMP));
                url.sourceDomain = cursor.getString(cursor.getColumnIndex(COL_URL_SOURCE));
                list.add(url);
            } while (cursor.moveToNext());
        }
        cursor.close();
        return list;
    }

    public int getCapturedCountToday() {
        SQLiteDatabase db = this.getReadableDatabase();
        long midnight = (System.currentTimeMillis() / 86400000L) * 86400000L;
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_URLS + " WHERE " + COL_URL_TIMESTAMP + " >= ?", 
                new String[]{String.valueOf(midnight)});
        int count = 0;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        }
        cursor.close();
        return count;
    }

    public int getTotalCapturedCount() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_URLS, null);
        int count = 0;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        }
        cursor.close();
        return count;
    }
}