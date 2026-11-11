package com.antunes.remote;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * The TV screen as live video: the box records its screen in H.264 (screenrecord) and sends it through ADB;
 * the phone decodes it straight onto the picture. screenrecord stops every 3 minutes, so it restarts by itself.
 */
final class LiveScreen {
    interface Events { void onFirstFrame(); void onFailed(); }

    static final int W = 1280, H = 720;
    private final AdbClient adb;
    private final Surface surface;
    private final Events events;
    private volatile boolean running;
    private volatile InputStream in;
    private Thread thread;

    LiveScreen(AdbClient adb, Surface surface, Events events) {
        this.adb = adb;
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
        InputStream i = in;
        if (i != null) try { i.close(); } catch (Exception ignored) { }
    }

    boolean running() { return running; }

    private void loop() {
        boolean everShown = false;
        int fails = 0;
        while (running && adb.connected()) {
            boolean shown = play();
            if (shown) { everShown = true; fails = 0; }
            else if (++fails >= (everShown ? 5 : 2)) { running = false; events.onFailed(); return; }
            try { Thread.sleep(shown ? 50 : 1500); } catch (InterruptedException e) { return; }
        }
        running = false;
    }

    /** One run of screenrecord; true if at least one picture was shown. */
    private boolean play() {
        MediaCodec codec = null;
        boolean shown = false;
        try {
            in = adb.execStream("screenrecord --output-format=h264 --size " + W + "x" + H + " --bit-rate 6000000 -");
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
