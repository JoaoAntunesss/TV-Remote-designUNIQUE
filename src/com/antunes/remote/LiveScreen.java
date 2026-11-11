package com.antunes.remote;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * The TV screen as live video in H.264, decoded straight onto the picture. It comes either from
 * screenrecord through ADB (stops every 3 minutes, so it restarts by itself) or from Comando TV
 * running on the TV itself and sharing its screen on port 8766 (boxes where ADB is locked).
 */
final class LiveScreen {
    interface Events { void onFirstFrame(); void onFailed(); }
    /** A running H.264 stream. */
    interface Source { int read(byte[] b) throws java.io.IOException; void close(); }
    /** Opens a new stream for each run. */
    interface Opener { Source open() throws Exception; boolean alive(); }

    static final int W = 1280, H = 720;
    static final int SHARE_PORT = 8766;

    static Opener adb(AdbClient a) {
        return new Opener() {
            @Override public Source open() throws Exception {
                return a.execStream("screenrecord --output-format=h264 --size " + W + "x" + H + " --bit-rate 6000000 -");
            }
            @Override public boolean alive() { return a.connected(); }
        };
    }

    static Opener tv(String host) {
        return new Opener() {
            @Override public Source open() throws Exception {
                java.net.Socket s = new java.net.Socket();
                s.connect(new java.net.InetSocketAddress(host, SHARE_PORT), 2000);
                s.setTcpNoDelay(true);
                java.io.InputStream in = s.getInputStream();
                return new Source() {
                    @Override public int read(byte[] b) throws java.io.IOException { return in.read(b); }
                    @Override public void close() { try { s.close(); } catch (Exception ignored) { } }
                };
            }
            @Override public boolean alive() { return true; }
        };
    }

    private final Opener opener;
    private final Surface surface;
    private final Events events;
    private volatile boolean running;
    private volatile Source in;
    private Thread thread;

    LiveScreen(Opener opener, Surface surface, Events events) {
        this.opener = opener;
        this.surface = surface;
        this.events = events;
    }

    void start() {
        running = true;
        thread = new Thread(this::loop, "live-screen");
        thread.start();
    }

    void stop() {
        running = false;
        Source i = in;
        if (i != null) try { i.close(); } catch (Exception ignored) { }
    }

    boolean running() { return running; }

    private void loop() {
        boolean everShown = false;
        int fails = 0;
        while (running && opener.alive()) {
            boolean shown = play();
            if (shown) { everShown = true; fails = 0; }
            else if (++fails >= (everShown ? 5 : 2)) { running = false; events.onFailed(); return; }
            try { Thread.sleep(shown ? 50 : 1500); } catch (InterruptedException e) { return; }
        }
        running = false;
    }

    /** One run of the stream; true if at least one picture was shown. */
    private boolean play() {
        MediaCodec codec = null;
        boolean shown = false;
        try {
            in = opener.open();
            codec = MediaCodec.createDecoderByType("video/avc");
            MediaFormat f = MediaFormat.createVideoFormat("video/avc", W, H);
            f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            codec.configure(f, surface, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] buf = new byte[1 << 20];
            int len = 0;
            byte[] chunk = new byte[65536];
            long firstData = System.currentTimeMillis();
            while (running) {
                int n = in.read(chunk);
                if (n < 0) break;
                if (len + n > buf.length) { byte[] bigger = new byte[Math.max(buf.length * 2, len + n)]; System.arraycopy(buf, 0, bigger, 0, len); buf = bigger; }
                System.arraycopy(chunk, 0, buf, len, n);
                len += n;
                // cut the H.264 stream into NAL units (each starts with 00 00 01) and feed them to the decoder
                int start = startCode(buf, 0, len);
                while (start >= 0) {
                    int next = startCode(buf, start + 3, len);
                    if (next < 0) break;
                    feed(codec, buf, start, next - start);
                    start = next;
                }
                if (start > 0) { System.arraycopy(buf, start, buf, 0, len - start); len -= start; }
                int out;
                while ((out = codec.dequeueOutputBuffer(info, 0)) >= 0) {
                    codec.releaseOutputBuffer(out, true);
                    if (!shown) { shown = true; events.onFirstFrame(); }
                }
                if (!shown && System.currentTimeMillis() - firstData > 6000) break; // nothing decodable
            }
        } catch (Exception ignored) {
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) { } codec.release(); }
        }
        return shown;
    }

    /** Index of the next 00 00 01 (the 4-byte form 00 00 00 01 is found one byte earlier). */
    private static int startCode(byte[] b, int from, int len) {
        for (int i = Math.max(0, from); i + 3 <= len; i++) {
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) return i > 0 && b[i - 1] == 0 && i - 1 >= from ? i - 1 : i;
        }
        return -1;
    }

    private static void feed(MediaCodec c, byte[] b, int off, int n) {
        int idx = c.dequeueInputBuffer(20_000);
        if (idx < 0) return;
        ByteBuffer ib = c.getInputBuffer(idx);
        if (ib == null) return;
        ib.clear();
        if (n > ib.capacity()) n = ib.capacity();
        ib.put(b, off, n);
        c.queueInputBuffer(idx, 0, n, System.nanoTime() / 1000, 0);
    }
}
