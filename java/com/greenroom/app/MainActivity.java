package com.greenroom.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView web;
    private int notifId = 1000;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int REQ_PICK_IMAGE = 42;
    /**
     * How many of our activities currently exist (foreground or cached in
     * the background). ListenerService stays silent while this is > 0 —
     * the page's own WebSocket already notifies, so this prevents doubles.
     */
    public static volatile int activityCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activityCount++;
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // file:// page opening wss:// relay sockets: allowed (WebSocket has no CORS),
        // but allow file-URL XHR just in case future assets need it.
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        web.addJavascriptInterface(new Bridge(), "GreenRoom");
        web.addJavascriptInterface(new UploadBridge(), "AndroidUpload");
        web.setWebViewClient(new WebViewClient());
        // <input type=file> needs a native picker in a WebView.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Pick a picture"),
                            REQ_PICK_IMAGE);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
        // notifications need an explicit runtime grant on Android 13+
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        // If notifications are on but the realtime listener isn't running
        // (reboot, update, process death), start it now and retire the
        // 15-minute poll — the listener replaces it.
        boolean notifyOn = getSharedPreferences("gr_poll", MODE_PRIVATE)
                .getBoolean("notify_on", false);
        if (notifyOn && !ListenerService.isRunning()) {
            PollJobService.cancel(this);
            ListenerService.start(this);
        }
    }

    @Override
    protected void onDestroy() {
        activityCount = Math.max(0, activityCount - 1);
        super.onDestroy();
    }

    /** Called from the chat page: GreenRoom.notify(title, body). */
    class Bridge {
        @JavascriptInterface
        public void notify(String title, String body) {
            final String t = title, b = body;
            runOnUiThread(new Runnable() {
                @Override public void run() { showNotification(t, b); }
            });
        }

        /**
         * Called from the chat page whenever rooms or identity change:
         * GreenRoom.setPollState(jsonRooms, pubkeyHex).
         * jsonRooms: [{"id":"<64-hex channel>","name":"...","seen":<max created_at rendered>}]
         * The per-room "seen" timestamps keep the background listener from
         * re-notifying messages already read while the app was open.
         */
        @JavascriptInterface
        public void setPollState(String roomsJson, String pubkeyHex) {
            android.content.SharedPreferences prefs =
                    getSharedPreferences("gr_poll", MODE_PRIVATE);
            android.content.SharedPreferences.Editor ed = prefs.edit()
                    .putString("rooms", roomsJson == null ? "[]" : roomsJson)
                    .putString("pubkey", pubkeyHex == null ? "" : pubkeyHex);
            try {
                org.json.JSONArray arr = new org.json.JSONArray(
                        roomsJson == null ? "[]" : roomsJson);
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("id", "");
                    long seen = o.optLong("seen", 0);
                    if (id.length() == 64 && seen > 0) {
                        String k = "lastSeen_" + id;
                        if (seen > prefs.getLong(k, 0)) ed.putLong(k, seen);
                    }
                }
            } catch (Exception ignored) { }
            ed.apply();
            ListenerService.kick(); // resubscribe with the fresh rooms/seen state
        }

        /**
         * Called from the chat page when the notification toggle flips:
         * GreenRoom.setNotifyEnabled(true/false). On = start the realtime
         * foreground listener (and retire the 15-minute poll); off = stop
         * everything.
         */
        @JavascriptInterface
        public void setNotifyEnabled(boolean on) {
            getSharedPreferences("gr_poll", MODE_PRIVATE).edit()
                    .putBoolean("notify_on", on)
                    .apply();
            if (on) {
                PollJobService.cancel(MainActivity.this);
                ListenerService.start(MainActivity.this);
            } else {
                ListenerService.stop(MainActivity.this);
                PollJobService.cancel(MainActivity.this);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && filePathCallback != null) {
            Uri[] uris = null;
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
            filePathCallback.onReceiveValue(uris);
            filePathCallback = null;
        }
    }

    /**
     * Called from the chat page to upload a picture: AndroidUpload.uploadImage(base64Jpeg).
     * Native POST to catbox (no auth, no CORS issues in native code). Result comes back
     * via __androidImageResult(url) / __androidImageError(msg) in the page.
     */
    class UploadBridge {
        @JavascriptInterface
        public void uploadImage(String base64Jpeg) {
            new Thread(new Runnable() {
                @Override public void run() { doUpload(base64Jpeg); }
            }).start();
        }

        private void doUpload(String base64Jpeg) {
            try {
                byte[] img = android.util.Base64.decode(base64Jpeg, android.util.Base64.DEFAULT);
                String boundary = "----gr" + System.currentTimeMillis();
                java.net.URL url = new java.net.URL("https://catbox.moe/user/api.php");
                java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) url.openConnection();
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type",
                        "multipart/form-data; boundary=" + boundary);
                c.setConnectTimeout(30000);
                c.setReadTimeout(120000);
                java.io.OutputStream out = c.getOutputStream();
                String head = "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"reqtype\"\r\n\r\n"
                        + "fileupload\r\n"
                        + "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"fileToUpload\"; "
                        + "filename=\"image.jpg\"\r\n"
                        + "Content-Type: image/jpeg\r\n\r\n";
                out.write(head.getBytes("UTF-8"));
                out.write(img);
                out.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
                out.flush();
                out.close();
                int code = c.getResponseCode();
                java.io.InputStream in =
                        (code == 200) ? c.getInputStream() : c.getErrorStream();
                java.util.Scanner sc =
                        new java.util.Scanner(in, "UTF-8").useDelimiter("\\A");
                String resp = sc.hasNext() ? sc.next().trim() : "";
                sc.close();
                if (code == 200 && resp.startsWith("https://")) {
                    postJs("__androidImageResult("
                            + org.json.JSONObject.quote(resp) + ")");
                } else {
                    postJs("__androidImageError("
                            + org.json.JSONObject.quote("HTTP " + code) + ")");
                }
            } catch (Exception e) {
                postJs("__androidImageError(" + org.json.JSONObject.quote(
                        e.getMessage() == null ? "upload failed" : e.getMessage()) + ")");
            }
        }

        private void postJs(final String expr) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    // "javascript:" prefix keeps evaluateJavascript happy on old WebViews.
                    web.evaluateJavascript("javascript:" + expr, null);
                }
            });
        }
    }

    private void showNotification(String title, String body) {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String ch = "greenroom_msgs";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(
                    ch, "Room messages", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(c);
        }
        Intent i = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, flags);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, ch)
                : new Notification.Builder(this);
        b.setContentTitle(title)
         .setContentText(body)
         .setSmallIcon(R.mipmap.ic_launcher)
         .setContentIntent(pi)
         .setAutoCancel(true)
         .setDefaults(Notification.DEFAULT_ALL);
        nm.notify(notifId++, b.build());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
