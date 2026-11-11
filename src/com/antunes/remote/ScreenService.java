package com.antunes.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * Runs on the TV: records the screen in H.264 and sends it to the phone that connects on port 8766.
 * One phone at a time; the recording only works while a phone is watching.
 */
public class ScreenService extends Service {
    static volatile boolean running;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ServerSocket server;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (projection != null || intent == null) return START_NOT_STICKY;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("screen", "Ecrã no telemóvel", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "screen")
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("Comando TV")
                .setContentText("O telemóvel pode ver este ecrã")
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(1, n);
        try {
            MediaProjectionManager m = getSystemService(MediaProjectionManager.class);
            Intent data = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("data", Intent.class) : intent.getParcelableExtra("data");
            projection = m.getMediaProjection(intent.getIntExtra("code", 0), data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
            }, new Handler(Looper.getMainLooper()));
            int dpi = getResources().getDisplayMetrics().densityDpi;
            display = projection.createVirtualDisplay("comando-tv", LiveScreen.W, LiveScreen.H, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        new Thread(this::serve, "screen-share").start();
        new Thread(this::serveApps, "apps").start();
        return START_NOT_STICKY;
    }

    private void serve() {
        try (ServerSocket ss = new ServerSocket(LiveScreen.SHARE_PORT)) {
            server = ss;
            while (running) {
                try (Socket s = ss.accept()) {
                    s.setTcpNoDelay(true);
                    stream(s.getOutputStream());
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) {
        } finally {
            stopSelf();
        }
    }

    static final int APPS_PORT = 8767;
    private ServerSocket appsServer;

    /** Tells the phone which apps this TV has (with their pictures), so it can open any of them. */
    private void serveApps() {
        try (ServerSocket ss = new ServerSocket(APPS_PORT)) {
            appsServer = ss;
            String json = null;
            while (running) {
                try (Socket s = ss.accept()) {
                    s.setSoTimeout(3000);
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream()));
                    while (true) { String l = r.readLine(); if (l == null || l.isEmpty()) break; }
                    if (json == null) json = appsJson();
                    byte[] body = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    OutputStream o = s.getOutputStream();
                    o.write(("HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes());
                    o.write(body);
                    o.flush();
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    private String appsJson() throws Exception {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        org.json.JSONArray arr = new org.json.JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (!seen.add(pkg) || pkg.equals(getPackageName())) continue;
            org.json.JSONObject o = new org.json.JSONObject().put("pkg", pkg).put("label", String.valueOf(ri.loadLabel(pm)));
            try {
                android.graphics.drawable.Drawable d = ri.activityInfo.loadBanner(pm);
                if (d == null) d = ri.loadIcon(pm);
                int w = 320, h = 180;
                android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(bm);
                c.drawColor(0xFF1C2228);
                int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
                if (iw > 0 && ih > 0 && iw * 9 < ih * 14) { int s2 = h * 2 / 3; d.setBounds((w - s2) / 2, (h - s2) / 2, (w + s2) / 2, (h + s2) / 2); }
                else d.setBounds(0, 0, w, h);
                d.draw(c);
                java.io.ByteArrayOutputStream jpg = new java.io.ByteArrayOutputStream();
                bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, jpg);
                o.put("img", android.util.Base64.encodeToString(jpg.toByteArray(), android.util.Base64.NO_WRAP));
            } catch (Exception ignored) { }
            arr.put(o);
        }
        return arr.toString();
    }

    /** Sends the screen to one phone until it disconnects. */
    private void stream(OutputStream out) throws Exception {
        MediaCodec enc = MediaCodec.createEncoderByType("video/avc");
        Surface input = null;
        try {
            MediaFormat f = MediaFormat.createVideoFormat("video/avc", LiveScreen.W, LiveScreen.H);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000); // keeps sending when the screen is still
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = enc.createInputSurface();
            enc.start();
            display.setSurface(input);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] chunk = new byte[256 * 1024];
            while (running) {
                int i = enc.dequeueOutputBuffer(info, 100_000);
                if (i < 0) continue;
                ByteBuffer b = enc.getOutputBuffer(i);
                if (b != null && info.size > 0) {
                    if (chunk.length < info.size) chunk = new byte[info.size];
                    b.position(info.offset);
                    b.get(chunk, 0, info.size);
                    out.write(chunk, 0, info.size);
                }
                enc.releaseOutputBuffer(i, false);
            }
        } finally {
            try { display.setSurface(null); } catch (Exception ignored) { }
            try { enc.stop(); } catch (Exception ignored) { }
            enc.release();
            if (input != null) input.release();
        }
    }

    @Override public void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        try { if (appsServer != null) appsServer.close(); } catch (Exception ignored) { }
        if (display != null) display.release();
        if (projection != null) projection.stop();
        projection = null;
        super.onDestroy();
    }
}
