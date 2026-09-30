package com.greenroom.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.app.Service;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Realtime listener: a foreground service that holds relay WebSockets open so
 * room messages (and DMs) notify within a second or two, even with the app
 * fully swiped away. This replaces the 15-minute JobScheduler poll while it
 * runs — Android batches periodic jobs, which is why notifications used to
 * arrive randomly late.
 *
 * The mandatory persistent notification lives on a dedicated IMPORTANCE_MIN
 * channel ("Listening"): no status-bar icon, no sound, no vibration — it only
 * appears as a collapsed line at the bottom of the pulled-down shade. That is
 * the least intrusive Android allows for a foreground service.
 *
 * Notes:
 * - Foreground services are exempt from Doze, so the sockets stay live.
 * - While the app UI is in the foreground, notifications are skipped here —
 *   the page's own WebSocket + focus guard handles them (no duplicates).
 * - DM contents can't be decrypted natively (no secp256k1/AES stack in the
 *   manual build), so DM notifications are generic: "new direct message".
 * - Rooms/pubkey are re-read from prefs on every (re)connect; the page kicks
 *   a resubscribe via ListenerService.kick() whenever they change.
 */
public class ListenerService extends Service {
    private static final String PREFS = "gr_poll";
    private static final String LISTEN_CHANNEL = "gr_listener";
    private static final String MSG_CHANNEL = "greenroom_msgs";
    private static final int FOREGROUND_ID = 7;
    private static final String[] RELAYS = {
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://relay.snort.social",
            "wss://relay.damus.io"
    };

    private static volatile ListenerService instance = null;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, ListenerService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, ListenerService.class));
    }

    public static boolean isRunning() { return instance != null; }

    /** Ask the running service to drop its sockets and resubscribe (prefs changed). */
    public static void kick() {
        ListenerService s = instance;
        if (s != null) s.requestResub();
    }

    private volatile boolean serviceRunning = false;
    private volatile long resubGen = 0; // bumped by kick(); relay threads track their own copy
    private final Object kickLock = new Object();
    private final List<Thread> relayThreads = new ArrayList<Thread>();
    private final Set<String> seenIds = new HashSet<String>();
    private final Map<String, Long> lastSeen = new HashMap<String, Long>();
    private int notifId = 3000;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                // Least intrusive a foreground service can be: no status-bar
                // icon, no sound, no vibration. Visible only as a collapsed
                // line at the bottom of the expanded notification shade.
                NotificationChannel c = new NotificationChannel(
                        LISTEN_CHANNEL, "Listening",
                        NotificationManager.IMPORTANCE_MIN);
                c.setDescription("Keeps the Green Room connection open for instant notifications. Silent.");
                c.setShowBadge(false);
                nm.createNotificationChannel(c);
                // Message alerts reuse the normal channel (already created by
                // the poller/page path if needed; ensure it exists here too).
                NotificationChannel m = new NotificationChannel(
                        MSG_CHANNEL, "Room messages",
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(m);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!prefs().getBoolean("notify_on", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(FOREGROUND_ID, persistentNotification());
        if (!serviceRunning) {
            serviceRunning = true;
            loadLastSeen();
            for (final String relay : RELAYS) {
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { relayLoop(relay); }
                }, "gr-listen-" + relay);
                relayThreads.add(t);
                t.start();
            }
        } else {
            requestResub();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        serviceRunning = false;
        instance = null;
        synchronized (kickLock) { kickLock.notifyAll(); }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void requestResub() {
        resubGen++;
        synchronized (kickLock) { kickLock.notifyAll(); }
    }

    private Notification persistentNotification() {
        Intent i = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, flags);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, LISTEN_CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("Green Room listening")
         .setContentText("Instant notifications on")
         .setSmallIcon(R.mipmap.ic_launcher)
         .setContentIntent(pi)
         .setOngoing(true)
         .setShowWhen(false);
        return b.build();
    }

    private static class Room {
        final String id, name;
        Room(String id, String name) { this.id = id; this.name = name; }
    }

    private List<Room> readRooms() {
        List<Room> rooms = new ArrayList<Room>();
        try {
            JSONArray arr = new JSONArray(prefs().getString("rooms", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && o.optString("id", "").length() == 64) {
                    rooms.add(new Room(o.optString("id"), o.optString("name", "a room")));
                }
            }
        } catch (Exception ignored) { }
        return rooms;
    }

    private void loadLastSeen() {
        SharedPreferences p = prefs();
        for (Room r : readRooms()) {
            if (!lastSeen.containsKey(r.id)) {
                lastSeen.put(r.id, p.getLong("lastSeen_" + r.id, 0));
            }
        }
        if (!lastSeen.containsKey("dm")) {
            lastSeen.put("dm", p.getLong("lastSeen_dm", 0));
        }
    }

    private void saveLastSeen(String key, long ts) {
        lastSeen.put(key, ts);
        prefs().edit().putLong("lastSeen_" + key, ts).apply();
    }

    /** One relay: connect, subscribe, idle until kick/close, reconnect with backoff. */
    private void relayLoop(String relayUrl) {
        int backoffSec = 5;
        long myGen = resubGen;
        while (serviceRunning) {
            if (!prefs().getBoolean("notify_on", false)) {
                stopSelf();
                return;
            }
            final List<Room> rooms = readRooms();
            final String myPub = prefs().getString("pubkey", "");
            loadLastSeen();
            myGen = resubGen;

            long since = Long.MAX_VALUE;
            for (Long t : lastSeen.values()) since = Math.min(since, t);
            if (since == Long.MAX_VALUE) since = 0;

            String req;
            try {
                JSONArray eTags = new JSONArray();
                for (Room r : rooms) eTags.put(r.id);
                JSONObject f42 = new JSONObject();
                f42.put("kinds", new JSONArray().put(42));
                f42.put("#e", eTags);
                f42.put("since", since);
                JSONObject f4 = new JSONObject();
                f4.put("kinds", new JSONArray().put(4));
                f4.put("#p", new JSONArray().put(myPub));
                f4.put("since", since);
                req = new JSONArray().put("REQ").put("grlisten")
                        .put(f42).put(f4).toString();
            } catch (Exception e) {
                sleepQuiet(5000);
                continue;
            }
            final String sub = req;

            WebSocketClient ws;
            try {
                ws = new WebSocketClient(new URI(relayUrl)) {
                    @Override public void onOpen(ServerHandshake h) { send(sub); }
                    @Override public void onMessage(String msg) {
                        try {
                            JSONArray a = new JSONArray(msg);
                            if (!"EVENT".equals(a.optString(0, "")) || a.length() < 3) return;
                            handleEvent(a.getJSONObject(2), rooms, myPub);
                        } catch (Exception ignored) { }
                    }
                    @Override public void onClose(int code, String reason, boolean remote) {
                        synchronized (kickLock) { kickLock.notifyAll(); }
                    }
                    @Override public void onError(Exception ex) {
                        synchronized (kickLock) { kickLock.notifyAll(); }
                    }
                };
            } catch (Exception e) {
                sleepQuiet(backoffSec * 1000L);
                backoffSec = Math.min(60, backoffSec * 2);
                continue;
            }

            try {
                ws.setConnectionLostTimeout(60); // ping keeps NAT/Doze happy
                if (!ws.connectBlocking(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    sleepQuiet(backoffSec * 1000L);
                    backoffSec = Math.min(60, backoffSec * 2);
                    continue;
                }
            } catch (Exception e) {
                sleepQuiet(backoffSec * 1000L);
                backoffSec = Math.min(60, backoffSec * 2);
                continue;
            }
            backoffSec = 5; // connected — reset backoff

            // Idle until the socket dies, prefs change, or the 60s recheck.
            while (serviceRunning && resubGen == myGen && ws.isOpen()) {
                synchronized (kickLock) {
                    try { kickLock.wait(60000); } catch (InterruptedException ignored) { }
                }
                if (!prefs().getBoolean("notify_on", false)) break;
            }
            try { ws.send("[\"CLOSE\",\"grlisten\"]"); } catch (Exception ignored) { }
            try { ws.closeBlocking(); } catch (Exception ignored) { }
            if (!serviceRunning || !prefs().getBoolean("notify_on", false)) return;
            // brief pause before reconnect so a kick doesn't hot-loop
            sleepQuiet(1000);
        }
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private void handleEvent(JSONObject ev, List<Room> rooms, String myPub) {
        String id = ev.optString("id", "");
        if (id.isEmpty()) return;
        synchronized (seenIds) {
            if (!seenIds.add(id)) return; // dedup across relays
            if (seenIds.size() > 10000) seenIds.clear();
        }
        int kind = ev.optInt("kind", -1);
        String pubkey = ev.optString("pubkey", "");
        long created = ev.optLong("created_at", 0);
        if (pubkey.equals(myPub)) return; // never notify for own messages

        if (kind == 42) {
            String channel = null;
            String roomName = "a room";
            JSONArray tags = ev.optJSONArray("tags");
            if (tags != null) {
                outer:
                for (int i = 0; i < tags.length(); i++) {
                    JSONArray t = tags.optJSONArray(i);
                    if (t != null && "e".equals(t.optString(0, "")) && t.length() > 1) {
                        String v = t.optString(1, "");
                        for (Room r : rooms) {
                            if (r.id.equals(v)) { channel = v; roomName = r.name; break outer; }
                        }
                    }
                }
            }
            if (channel == null) return;
            Long base = lastSeen.get(channel);
            if (base == null) base = 0L;
            if (created > base) saveLastSeen(channel, created);
            else return; // already seen (e.g. while the app was open)
            String content = ev.optString("content", "");
            if (content.isEmpty()) return;
            notifyMessage("New message · " + roomName, content);
        } else if (kind == 4) {
            Long base = lastSeen.get("dm");
            if (base == null) base = 0L;
            if (created > base) saveLastSeen("dm", created);
            else return;
            // Can't decrypt NIP-04 natively — keep it generic.
            notifyMessage("\uD83D\uDCAC New direct message", "Open the app to read it.");
        }
    }

    private void notifyMessage(String title, String body) {
        // If any activity exists (foreground OR background-but-alive), the
        // page's own WebSocket already notified — stay silent, no doubles.
        if (MainActivity.activityCount > 0) return;
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        String b = body.length() > 140 ? body.substring(0, 140) + "…" : body;
        Intent i = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, flags);
        Notification.Builder nb = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, MSG_CHANNEL)
                : new Notification.Builder(this);
        nb.setContentTitle(title)
          .setContentText(b)
          .setSmallIcon(R.mipmap.ic_launcher)
          .setContentIntent(pi)
          .setAutoCancel(true)
          .setDefaults(Notification.DEFAULT_ALL);
        nm.notify(notifId++, nb.build());
    }
}
