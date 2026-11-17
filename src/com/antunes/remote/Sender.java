package com.antunes.remote;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * "Enviar para a TV": a tiny web server on the phone. The TV (TvViewer, the same app installed there)
 * streams photos, videos and music straight from it, or downloads files to keep. Nothing leaves the
 * home network, and each share gets a fresh random path so other devices cannot guess it.
 */
final class Sender {
    static final int PORT = 8790;

    static final class Item {
        final Uri uri; final String name, mime; final long size;
        volatile long sent;
        Item(Uri u, String n, String m, long s) { uri = u; name = n; mime = m; size = s; }
        boolean media() { return mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/"); }
    }

    private static ServerSocket server;
    private static volatile List<Item> items = Collections.emptyList();
    private static volatile String token = "";
    /** When the TV last asked for the list (proof that the TV side opened). */
    static volatile long helloAt;

    static List<Item> items() { return items; }

    /** Describes what the user picked (name, type, size) and starts serving it. Returns false if nothing usable. */
    static synchronized boolean share(List<Uri> uris) {
        ContentResolver cr = App.ctx().getContentResolver();
        List<Item> l = new ArrayList<>();
        for (Uri u : uris) {
            String name = null; long size = -1;
            try (Cursor c = cr.query(u, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), s = c.getColumnIndex(OpenableColumns.SIZE);
                    if (n >= 0) name = c.getString(n);
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s);
                }
            } catch (Exception ignored) { }
            if (name == null) name = u.getLastPathSegment() == null ? "ficheiro" : u.getLastPathSegment();
            String mime = cr.getType(u);
            if (mime == null || mime.equals("application/octet-stream")) {
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
                String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                mime = m != null ? m : "application/octet-stream";
            }
            l.add(new Item(u, name.replaceAll("[\\\\/:*?\"<>|]", "_"), mime, size));
        }
        if (l.isEmpty()) return false;
        byte[] r = new byte[6];
        new SecureRandom().nextBytes(r);
        StringBuilder t = new StringBuilder();
        for (byte b : r) t.append(String.format("%02x", b));
        token = t.toString();
        items = l;
        helloAt = 0;
        start();
        return true;
    }

    /** Bytes the TV has fetched so far and the total (for the progress bar on the phone). */
    static long[] progress() {
        long sent = 0, total = 0;
        for (Item i : items) { sent += Math.min(i.sent, Math.max(i.size, 0)); total += Math.max(i.size, 0); }
        return new long[]{sent, total};
    }

    /** Address of this share as the TV must see it: the phone's Wi-Fi address on the TV's network. */
    static String base(String tvHost) { return "http://" + myIp(tvHost) + ":" + PORT + "/" + token; }

    /** Where the TV can download the app itself (typed into Downloader on the TV, first time only). */
    static String apkUrl(String tvHost) { start(); return "http://" + myIp(tvHost) + ":" + PORT + "/comandotv.apk"; }

    static String myIp(String tvHost) {
        String best = null;
        byte[] tv = null;
        try { tv = InetAddress.getByName(tvHost).getAddress(); } catch (Exception ignored) { }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    byte[] b = a.getAddress();
                    if (tv != null && tv.length == 4 && b[0] == tv[0] && b[1] == tv[1] && b[2] == tv[2]) return a.getHostAddress();
                    if (best == null && a.isSiteLocalAddress()) best = a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return best != null ? best : "0.0.0.0";
    }

    // ------------------------------------------------------------ the server

    private static synchronized void start() {
        if (server != null && !server.isClosed()) return;
        try {
            server = new ServerSocket(PORT);
        } catch (Exception e) {
            return;
        }
        ServerSocket ss = server;
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    new Thread(() -> serve(s)).start();
                } catch (Exception ignored) { }
            }
        }, "sender");
        t.setDaemon(true);
        t.start();
    }

    private static void serve(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1));
            String req = in.readLine();
            if (req == null) return;
            String range = null, h;
            while ((h = in.readLine()) != null && !h.isEmpty()) {
                if (h.toLowerCase(Locale.ROOT).startsWith("range:")) range = h.substring(6).trim();
            }
            String[] p = req.split(" ");
            if (p.length < 2) return;
            boolean head = p[0].equals("HEAD");
            String path = p[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            OutputStream out = sock.getOutputStream();

            if (path.equals("/comandotv.apk")) {
                File apk = new File(App.ctx().getApplicationInfo().sourceDir);
                try (InputStream f = new FileInputStream(apk)) {
                    send(out, f, apk.length(), "application/vnd.android.package-archive", "ComandoTV.apk", range, head, null);
                }
                return;
            }
            String[] seg = path.split("/");
            // /<token>/list  or  /<token>/<index>/<name>
            if (seg.length < 3 || token.isEmpty() || !seg[1].equals(token)) { status(out, "404 Not Found"); return; }
            List<Item> l = items;
            if (seg[2].equals("list")) {
                helloAt = System.currentTimeMillis();
                JSONArray a = new JSONArray();
                for (int i = 0; i < l.size(); i++) {
                    Item it = l.get(i);
                    a.put(new JSONObject().put("u", "/" + token + "/" + i + "/" + URLEncoder.encode(it.name, "UTF-8").replace("+", "%20"))
                            .put("t", it.mime).put("n", it.name).put("s", it.size));
                }
                byte[] b = a.toString().getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + b.length
                        + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                if (!head) out.write(b);
                out.flush();
                return;
            }
            int idx;
            try { idx = Integer.parseInt(seg[2]); } catch (NumberFormatException e) { status(out, "404 Not Found"); return; }
            if (idx < 0 || idx >= l.size()) { status(out, "404 Not Found"); return; }
            Item it = l.get(idx);
            try (InputStream f = App.ctx().getContentResolver().openInputStream(it.uri)) {
                if (f == null) { status(out, "404 Not Found"); return; }
                send(out, f, it.size, it.mime, it.name, range, head, it);
            }
        } catch (Exception ignored) {
            // the TV closed the connection (seeking in a video does this all the time)
        }
    }

    private static void status(OutputStream out, String st) throws Exception {
        out.write(("HTTP/1.1 " + st + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /** Sends a file, honouring "Range: bytes=a-b" so the TV can seek in videos. */
    private static void send(OutputStream out, InputStream f, long size, String mime, String name, String range,
                             boolean head, Item it) throws Exception {
        long from = 0, to = size - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=") && size > 0) {
            String r = range.substring(6).split(",")[0].trim();
            int d = r.indexOf('-');
            try {
                if (d == 0) { from = Math.max(0, size - Long.parseLong(r.substring(1))); }
                else {
                    from = Long.parseLong(r.substring(0, d));
                    if (d < r.length() - 1) to = Math.min(size - 1, Long.parseLong(r.substring(d + 1)));
                }
                partial = true;
            } catch (NumberFormatException ignored) { from = 0; to = size - 1; }
            if (from > to) { status(out, "416 Range Not Satisfiable"); return; }
        }
        StringBuilder hd = new StringBuilder(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        hd.append("Content-Type: ").append(mime).append("\r\n");
        hd.append("Accept-Ranges: bytes\r\n");
        hd.append("Content-Disposition: inline; filename*=UTF-8''").append(URLEncoder.encode(name, "UTF-8").replace("+", "%20")).append("\r\n");
        if (size >= 0) hd.append("Content-Length: ").append(to - from + 1).append("\r\n");
        if (partial) hd.append("Content-Range: bytes ").append(from).append('-').append(to).append('/').append(size).append("\r\n");
        hd.append("Connection: close\r\n\r\n");
        out.write(hd.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (head) { out.flush(); return; }
        long skip = from;
        while (skip > 0) { long n = f.skip(skip); if (n <= 0) break; skip -= n; }
        long left = size >= 0 ? to - from + 1 : Long.MAX_VALUE, pos = from;
        byte[] buf = new byte[64 * 1024];
        int n;
        while (left > 0 && (n = f.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
            out.write(buf, 0, n);
            left -= n;
            pos += n;
            if (it != null && pos > it.sent) it.sent = pos;
        }
        out.flush();
    }
}
