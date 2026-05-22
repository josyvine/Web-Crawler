package org.chromium.chrome.browser.monitoring;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

public class LocalHttpServer extends Thread {
    private static final String TAG = "KiwiMonitoringServer";
    private final int mPort = 8080;
    private final DatabaseHelper mDbHelper;
    private boolean mRunning = true;
    private ServerSocket mServerSocket;

    public LocalHttpServer(DatabaseHelper dbHelper) {
        this.mDbHelper = dbHelper;
    }

    @Override
    public void run() {
        try {
            mServerSocket = new ServerSocket(mPort);
            Log.i(TAG, "Local loopback telemetry server started on port " + mPort);

            while (mRunning) {
                Socket clientSocket = mServerSocket.accept();
                // Hand off each connection to a separate thread to prevent locking the loop
                new Thread(new SocketHandler(clientSocket)).start();
            }
        } catch (Exception e) {
            if (mRunning) {
                Log.e(TAG, "Server socket execution failed: ", e);
            }
        }
    }

    public void shutdown() {
        mRunning = false;
        try {
            if (mServerSocket != null && !mServerSocket.isClosed()) {
                mServerSocket.close();
            }
            Log.i(TAG, "Local loopback telemetry server shutdown complete.");
        } catch (Exception e) {
            Log.e(TAG, "Error closing server socket: ", e);
        }
    }

    private class SocketHandler implements Runnable {
        private final Socket mSocket;

        public SocketHandler(Socket socket) {
            this.mSocket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(mSocket.getInputStream(), "UTF-8"));
                 OutputStream out = mSocket.getOutputStream()) {

                String requestLine = reader.readLine();
                if (requestLine == null) {
                    return;
                }

                String[] requestParts = requestLine.split(" ");
                if (requestParts.length < 2) {
                    sendErrorResponse(out, 400, "Bad Request");
                    return;
                }

                String method = requestParts[0];
                String rawPath = requestParts[1];

                // Parse query parameters
                String path = rawPath;
                Map<String, String> queryParameters = new HashMap<>();
                if (rawPath.contains("?")) {
                    String[] pathParts = rawPath.split("\\?", 2);
                    path = pathParts[0];
                    String queryString = pathParts[1];
                    String[] pairs = queryString.split("&");
                    for (String pair : pairs) {
                        String[] keyValue = pair.split("=", 2);
                        if (keyValue.length == 2) {
                            queryParameters.put(
                                URLDecoder.decode(keyValue[0], "UTF-8"),
                                URLDecoder.decode(keyValue[1], "UTF-8")
                            );
                        } else if (keyValue.length == 1) {
                            queryParameters.put(URLDecoder.decode(keyValue[0], "UTF-8"), "");
                        }
                    }
                }

                // Read request headers to obtain content length and target origin
                String headerLine;
                int contentLength = 0;
                while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                    if (headerLine.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(headerLine.split(":")[1].trim());
                    }
                }

                // Handle CORS preflight OPTIONS request
                if ("OPTIONS".equalsIgnoreCase(method)) {
                    sendCorsPreflightResponse(out);
                    return;
                }

                // Endpoint: GET /config?domain=xxx
                if ("GET".equalsIgnoreCase(method) && "/config".equals(path)) {
                    handleGetConfig(out, queryParameters);
                    return;
                }

                // Endpoint: POST /submit-data
                if ("POST".equalsIgnoreCase(method) && "/submit-data".equals(path)) {
                    handlePostSubmitData(out, reader, contentLength);
                    return;
                }

                sendErrorResponse(out, 404, "Not Found");

            } catch (Exception e) {
                Log.e(TAG, "Error handling socket communication: ", e);
            } finally {
                try {
                    mSocket.close();
                } catch (Exception ex) {
                    Log.e(TAG, "Failed to close socket: ", ex);
                }
            }
        }

        private void sendCorsPreflightResponse(OutputStream out) throws Exception {
            String response = "HTTP/1.1 200 OK\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: Content-Type\r\n" +
                    "Access-Control-Max-Age: 86400\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(response.getBytes("UTF-8"));
            out.flush();
        }

        private void handleGetConfig(OutputStream out, Map<String, String> params) throws Exception {
            String targetDomain = params.get("domain");
            JSONObject responseJson = new JSONObject();

            if (targetDomain == null || targetDomain.trim().isEmpty()) {
                responseJson.put("status", "ERROR");
                responseJson.put("message", "Missing domain parameter");
                sendJsonResponse(out, 400, responseJson.toString());
                return;
            }

            SQLiteDatabase db = mDbHelper.getReadableDatabase();
            Cursor cursor = db.query(DatabaseHelper.TABLE_DOMAINS, null, 
                    DatabaseHelper.COL_DOM_NAME + " = ?", new String[]{targetDomain}, 
                    null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                responseJson.put("status", "SUCCESS");
                responseJson.put("domain_name", cursor.getString(cursor.getColumnIndex(DatabaseHelper.COL_DOM_NAME)));
                responseJson.put("mode", cursor.getString(cursor.getColumnIndex(DatabaseHelper.COL_DOM_STATUS)));
                responseJson.put("start_time", cursor.getLong(cursor.getColumnIndex(DatabaseHelper.COL_DOM_START_TIME)));
                responseJson.put("end_time", cursor.getLong(cursor.getColumnIndex(DatabaseHelper.COL_DOM_END_TIME)));
                responseJson.put("scan_interval", cursor.getInt(cursor.getColumnIndex(DatabaseHelper.COL_DOM_INTERVAL)));
                cursor.close();
                sendJsonResponse(out, 200, responseJson.toString());
            } else {
                if (cursor != null) {
                    cursor.close();
                }
                responseJson.put("status", "NOT_FOUND");
                sendJsonResponse(out, 404, responseJson.toString());
            }
        }

        private void handlePostSubmitData(OutputStream out, BufferedReader reader, int contentLength) throws Exception {
            if (contentLength <= 0) {
                JSONObject err = new JSONObject();
                err.put("status", "ERROR");
                err.put("message", "Empty payload content");
                sendJsonResponse(out, 400, err.toString());
                return;
            }

            char[] buffer = new char[contentLength];
            int readTotal = 0;
            while (readTotal < contentLength) {
                int read = reader.read(buffer, readTotal, contentLength - readTotal);
                if (read == -1) {
                    break;
                }
                readTotal += read;
            }

            String body = new String(buffer);
            JSONObject payload = new JSONObject(body);

            String domain = payload.getString("domain");
            JSONArray urlsArray = payload.getJSONArray("urls");
            long publishedAt = payload.optLong("publishedAt", System.currentTimeMillis());

            int newUrlsCount = 0;
            for (int i = 0; i < urlsArray.length(); i++) {
                JSONObject urlObject = urlsArray.getJSONObject(i);
                String urlVal = urlObject.getString("url");
                String urlTitle = urlObject.optString("title", "No Title Available");

                boolean success = mDbHelper.addExtractedUrl(urlVal, urlTitle, publishedAt, domain);
                if (success) {
                    newUrlsCount++;
                }
            }

            Log.i(TAG, "Processed extraction batch for " + domain + ". Extracted " + urlsArray.length() + " links. New: " + newUrlsCount);

            JSONObject reply = new JSONObject();
            reply.put("status", "SUCCESS");
            reply.put("new_links_saved", newUrlsCount);
            sendJsonResponse(out, 200, reply.toString());
        }

        private void sendJsonResponse(OutputStream out, int statusCode, String jsonResponse) throws Exception {
            byte[] responseBytes = jsonResponse.getBytes("UTF-8");
            String headers = "HTTP/1.1 " + statusCode + " OK\r\n" +
                    "Content-Type: application/json; charset=UTF-8\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Content-Length: " + responseBytes.length + "\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(headers.getBytes("UTF-8"));
            out.write(responseBytes);
            out.flush();
        }

        private void sendErrorResponse(OutputStream out, int statusCode, String statusText) throws Exception {
            String body = "<html><body><h1>" + statusCode + " " + statusText + "</h1></body></html>";
            byte[] bodyBytes = body.getBytes("UTF-8");
            String headers = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                    "Content-Type: text/html; charset=UTF-8\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Content-Length: " + bodyBytes.length + "\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(headers.getBytes("UTF-8"));
            out.write(bodyBytes);
            out.flush();
        }
    }
}