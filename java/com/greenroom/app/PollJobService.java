package com.greenroom.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Background poller for new room messages. Android lets periodic jobs run at
 * most every ~15 minutes, so this is a "check for anything new" sweep rather
 * than realtime. It only runs while the user has the in-app notification
 * toggle switched on (the web page reports that via the JS bridge).
 */
public class PollJobService extends JobService {
    public static final int JOB_ID = 1001;
    private static final String PREFS = "gr_poll";
    private static final String CHANNEL = "greenroom_msgs";
    private static final long PERIOD_MS = 15 * 60 * 1000;
    private static final String[] RELAYS = {
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://relay.snort.social",
            "wss://relay.damus.io"
    };

    public static void schedule(Context ctx) {
        JobScheduler jm = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jm == null) return;
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, PollJobService.class))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        jm.schedule(info);
    }

    public static void cancel(Context ctx) {
        JobScheduler jm = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jm != null) jm.cancel(JOB_ID);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override public void run() {
                try { doPoll(); } catch (Exception ignored) { /* next run in 15 min */ }
                jobFinished(params, false);
            }
        }).start();
        return true; // work continues on our thread
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // reschedule if we get killed mid-poll
    }

    private static class Room {
        final String id, name;
        Room(String id, String name) { this.id = id; this.name = name; }
    }

    private static class Fresh {
        final String roomId, content;
        final long created;
        Fresh(String roomId, String content, long created) {
            this.roomId = roomId; this.content = content; this.created = created;
        }
    }

    private void doPoll() throws Exception {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!p.getBoolean("notify_on", false)) return;
        final String myPub = p.getString("pubkey", "");
        final List<Room> rooms = new ArrayList<Room>();
        JSONArray arr = new JSONArray(p.getString("rooms", "[]"));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optString("id", "").length() == 64) {
                rooms.add(new Room(o.optString("id"), o.optString("name", "a room")));
            }
        }
        if (rooms.isEmpty()) return;

        // Baseline per room; REQ uses the oldest so no room is starved.
        final Map<String, Long> baseTs = new HashMap<String, Long>();
        final Map<String, Long> maxTs = new HashMap<String, Long>();
        long since = Long.MAX_VALUE;
        for (Room r : rooms) {
            long t = p.getLong("lastSeen_" + r.id, 0);
            baseTs.put(r.id, t);
            maxTs.put(r.id, t);
            since = Math.min(since, t);
        }
        if (since == Long.MAX_VALUE) since = 0;

        JSONArray eTags = new JSONArray();
        for (Room r : rooms) eTags.put(r.id);
        JSONObject filter = new JSONObject();
        filter.put("kinds", new JSONArray().put(42));
        filter.put("#e", eTags);
        filter.put("since", since);
        final String req = new JSONArray().put("REQ").put("grpoll").put(filter).toString();

        final Set<String> seenIds = new HashSet<String>();
        final List<Fresh> fresh = new ArrayList<Fresh>();
        for (String relay : RELAYS) {
            pollRelay(relay, req, rooms, myPub, baseTs, maxTs, seenIds, fresh);
        }

        SharedPreferences.Editor ed = p.edit();
        for (Room r : rooms) ed.putLong("lastSeen_" + r.id, maxTs.get(r.id));
        ed.apply();

        if (!fresh.isEmpty()) notifyFresh(rooms, fresh);
    }

    private void pollRelay(String relayUrl, final String req, final List<Room> rooms,
                           final String myPub, final Map<String, Long> baseTs,
                           final Map<String, Long> maxTs, final Set<String> seenIds,
                           final List<Fresh> fresh) {
        final CountDownLatch done = new CountDownLatch(1);
        WebSocketClient ws;
        try {
            ws = new WebSocketClient(new URI(relayUrl)) {
                @Override public void onOpen(ServerHandshake h) { send(req); }
                @Override public void onMessage(String msg) {
                    try {
                        JSONArray a = new JSONArray(msg);
                        String t = a.optString(0, "");
                        if ("EOSE".equals(t)) { done.countDown(); return; }
                        if (!"EVENT".equals(t) || a.length() < 3) return;
                        handleEvent(a.getJSONObject(2), rooms, myPub, baseTs, maxTs, seenIds, fresh);
                    } catch (Exception ignored) { }
                }
                @Override public void onClose(int code, String reason, boolean remote) { done.countDown(); }
                @Override public void onError(Exception ex) { done.countDown(); }
            };
        } catch (Exception e) { return; }
        try {
            ws.setConnectionLostTimeout(15);
            if (!ws.connectBlocking(8, TimeUnit.SECONDS)) return;
            done.await(10, TimeUnit.SECONDS);
            try { ws.send("[\"CLOSE\",\"grpoll\"]"); } catch (Exception ignored) { }
            ws.closeBlocking();
        } catch (Exception ignored) { }
    }

    private void handleEvent(JSONObject ev, List<Room> rooms, String myPub,
                             Map<String, Long> baseTs, Map<String, Long> maxTs,
                             Set<String> seenIds, List<Fresh> fresh) {
        String id = ev.optString("id", "");
        if (id.isEmpty() || !seenIds.add(id)) return; // dedup across relays
        String pubkey = ev.optString("pubkey", "");
        long created = ev.optLong("created_at", 0);
        String channel = null;
        JSONArray tags = ev.optJSONArray("tags");
        if (tags != null) {
            outer:
            for (int i = 0; i < tags.length(); i++) {
                JSONArray t = tags.optJSONArray(i);
                if (t != null && "e".equals(t.optString(0, "")) && t.length() > 1) {
                    String v = t.optString(1, "");
                    for (Room r : rooms) {
                        if (r.id.equals(v)) { channel = v; break outer; }
                    }
                }
            }
        }
        if (channel == null) return;
        if (created > maxTs.get(channel)) maxTs.put(channel, created);
        if (!pubkey.equals(myPub) && created > baseTs.get(channel)) {
            fresh.add(new Fresh(channel, ev.optString("content", ""), created));
        }
    }

    private void notifyFresh(List<Room> rooms, List<Fresh> fresh) {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Map<String, String> names = new HashMap<String, String>();
        for (Room r : rooms) names.put(r.id, r.name);
        Map<String, List<Fresh>> byRoom = new HashMap<String, List<Fresh>>();
        for (Fresh f : fresh) {
            List<Fresh> l = byRoom.get(f.roomId);
            if (l == null) { l = new ArrayList<Fresh>(); byRoom.put(f.roomId, l); }
            l.add(f);
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Room messages", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent i = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, flags);
        int nid = 2000;
        for (Map.Entry<String, List<Fresh>> e : byRoom.entrySet()) {
            String roomName = names.get(e.getKey());
            if (roomName == null) roomName = "a room";
            List<Fresh> l = e.getValue();
            String title, body;
            if (l.size() == 1) {
                title = "New message · " + roomName;
                String c = l.get(0).content;
                body = c.length() > 140 ? c.substring(0, 140) + "…" : c;
            } else {
                title = l.size() + " new messages · " + roomName;
                String c = l.get(l.size() - 1).content;
                body = c.length() > 140 ? c.substring(0, 140) + "…" : c;
            }
            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(this, CHANNEL)
                    : new Notification.Builder(this);
            b.setContentTitle(title)
             .setContentText(body)
             .setSmallIcon(R.mipmap.ic_launcher)
             .setContentIntent(pi)
             .setAutoCancel(true)
             .setDefaults(Notification.DEFAULT_ALL);
            nm.notify(nid++, b.build());
        }
    }
}
