package com.antunes.remote;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;

/**
 * Talks to Android TV boxes over network ADB (port 5555), for boxes without Google's remote service
 * or without our home screen. The first time, the box asks "Allow debugging?" on the TV.
 * Keys, text, apps, opening apps, a picture of the screen and what is playing all go through the shell.
 */
final class AdbClient implements Remote {
    static final int PORT = 5555;
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257, VERSION = 0x01000001, MAXDATA = 256 * 1024;

    private final String host;
    private final AtvClient.Listener listener;
    private volatile Socket sock;
    private volatile OutputStream out;
    private volatile boolean running, ready;
    private final AtomicInteger ids = new AtomicInteger(1);
    private final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
    private Stream shell; // long-lived shell for keys and text
    private int maxData = 4096;

    AdbClient(String host, AtvClient.Listener l) {
        this.host = host;
        this.listener = l;
    }

    // ------------------------------------------------------------ the phone's ADB key (kept in the app files)

    private static PrivateKey priv;
    private static RSAPublicKey pub;

    private static synchronized void keys() throws Exception {
        if (priv != null) return;
        File f = new File(App.files(), "adbkey");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (f.exists()) {
            priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(f.toPath())));
        } else {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            priv = kp.getPrivate();
            Files.write(f.toPath(), priv.getEncoded());
        }
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) priv;
        pub = (RSAPublicKey) kf.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
    }

    /** ADB signs the 20-byte token as if it were a SHA-1 digest (PKCS#1 v1.5 with the SHA-1 DigestInfo). */
    private static byte[] sign(byte[] token) throws Exception {
        byte[] prefix = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        byte[] msg = new byte[prefix.length + token.length];
        System.arraycopy(prefix, 0, msg, 0, prefix.length);
        System.arraycopy(token, 0, msg, prefix.length, token.length);
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, priv);
        return c.doFinal(msg);
    }

    /** The public key in Android's own format (what goes into adb_keys on the box). */
    static byte[] androidPublicKey(RSAPublicKey k, String name) {
        int words = 64; // 2048 bits
        BigInteger n = k.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).mod(r32).intValue());
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).mod(r32).intValue());
        b.putInt(k.getPublicExponent().intValue());
        String s = java.util.Base64.getEncoder().encodeToString(b.array()) + " " + name + "\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ wire

    private synchronized void write(int cmd, int a0, int a1, byte[] data) throws IOException {
        OutputStream o = out;
        if (o == null) throw new IOException("closed");
        int len = data == null ? 0 : data.length, sum = 0;
        if (data != null) for (byte x : data) sum += x & 0xFF;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum).putInt(~cmd);
        o.write(h.array());
        if (len > 0) o.write(data);
        o.flush();
    }

    private static final class Msg { int cmd, a0, a1; byte[] data; }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt(); m.a0 = b.getInt(); m.a1 = b.getInt();
        int len = b.getInt();
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    /** One ADB stream (a shell, a command, a file). */
    private final class Stream {
        final int local = ids.getAndIncrement();
        volatile int remote;
        final LinkedBlockingQueue<byte[]> in = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<Boolean> okays = new LinkedBlockingQueue<>();
        volatile boolean closed;
        final ByteArrayOutputStream all = new ByteArrayOutputStream();

        void send(byte[] data) throws IOException, InterruptedException {
            for (int off = 0; off < data.length; off += maxData) {
                int n = Math.min(maxData, data.length - off);
                byte[] part = new byte[n];
                System.arraycopy(data, off, part, 0, n);
                write(WRTE, local, remote, part);
                if (okays.poll(5, TimeUnit.SECONDS) == null) throw new IOException("no reply");
            }
        }
    }

    private Stream open(String service, long timeoutMs) throws Exception {
        Stream s = new Stream();
        streams.put(s.local, s);
        write(OPEN, s.local, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
        if (s.okays.poll(timeoutMs, TimeUnit.MILLISECONDS) == null || s.closed) {
            streams.remove(s.local);
            throw new IOException("the box refused " + service);
        }
        return s;
    }

    /** Runs a command and returns everything it printed (binary-safe, no terminal). */
    byte[] exec(String cmd, long timeoutMs) throws Exception {
        if (!ready) throw new IOException("not connected");
        Stream s = open("exec:" + cmd, 5000);
        long end = System.currentTimeMillis() + timeoutMs;
        while (!s.closed || !s.in.isEmpty()) {
            byte[] b = s.in.poll(200, TimeUnit.MILLISECONDS);
            if (b != null) s.all.write(b);
            if (System.currentTimeMillis() > end) { write(CLSE, s.local, s.remote, null); break; }
        }
        streams.remove(s.local);
        return s.all.toByteArray();
    }

    /** Starts a command and gives its output as it arrives (for the live video of the screen). */
    java.io.InputStream execStream(String cmd) throws Exception {
        if (!ready) throw new IOException("not connected");
        return new ExecIn(open("exec:" + cmd, 5000));
    }

    /** Output of a running command, read as it arrives. */
    private final class ExecIn extends java.io.InputStream {
        private final Stream st;
        private byte[] cur;
        private int pos;
        private boolean done;

        ExecIn(Stream st) { this.st = st; }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            while (cur == null || pos >= cur.length) {
                if (done || (st.closed && st.in.isEmpty()) || !ready) return -1;
                try { cur = st.in.poll(500, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { throw new IOException(e); }
                pos = 0;
            }
            int n = Math.min(len, cur.length - pos);
            System.arraycopy(cur, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override public void close() {
            if (done) return;
            done = true;
            try { if (!st.closed) write(CLSE, st.local, st.remote, null); } catch (IOException ignored) { }
            streams.remove(st.local);
        }
    }

    String sh(String cmd) throws Exception { return new String(exec(cmd, 15_000), StandardCharsets.UTF_8); }

    // ------------------------------------------------------------ connection

    @Override public void run() {
        running = true;
        int fails = 0;
        while (running) {
            try {
                listener.onState("A ligar à box…", false);
                keys();
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, PORT), 5000);
                s.setTcpNoDelay(true);
                sock = s;
                out = s.getOutputStream();
                DataInputStream in = new DataInputStream(s.getInputStream());
                write(CNXN, VERSION, MAXDATA, "host::features=shell_v2,cmd\0".getBytes(StandardCharsets.UTF_8));
                boolean sentSig = false, sentKey = false;
                while (running) {
                    Msg m = read(in);
                    if (m.cmd == AUTH && m.a0 == 1) { // TOKEN
                        if (!sentSig) { write(AUTH, 2, 0, sign(m.data)); sentSig = true; }
                        else if (!sentKey) {
                            write(AUTH, 3, 0, androidPublicKey(pub, "comando-tv@" + android.os.Build.MODEL.replace(' ', '_')));
                            sentKey = true;
                            listener.onState("ALLOW", false); // the TV now asks "Allow debugging?"
                        }
                    } else if (m.cmd == CNXN) {
                        maxData = Math.max(1024, Math.min(m.a1, MAXDATA));
                        ready = true;
                        fails = 0;
                        listener.onState("Ligado", true);
                        new Thread(this::openShell).start();
                    } else if (m.cmd == OKAY) {
                        Stream st = streams.get(m.a1);
                        if (st != null) { st.remote = m.a0; st.okays.offer(true); }
                    } else if (m.cmd == WRTE) {
                        Stream st = streams.get(m.a1);
                        write(OKAY, m.a1, m.a0, null);
                        if (st != null) st.in.offer(m.data);
                    } else if (m.cmd == CLSE) {
                        Stream st = streams.get(m.a1);
                        if (st != null) {
                            st.closed = true;
                            st.okays.offer(false);
                            if (st.remote != 0 || m.a0 != 0) write(CLSE, m.a1, m.a0, null); // close our side too
                        }
                        if (st == shell) shell = null;
                    }
                }
            } catch (Exception e) {
                ready = false;
                out = null;
                shell = null;
                for (Stream st : streams.values()) { st.closed = true; st.okays.offer(false); }
                streams.clear();
                if (!running) return;
                fails++;
                listener.onState(fails > 2 ? "Box desligada ou sem depuração por rede" : "A ligar à box…", false);
                try { Thread.sleep(Math.min(10_000, 1500L * fails)); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private void openShell() {
        try { shell = open("shell:", 5000); } catch (Exception ignored) { }
    }

    @Override public void stop() {
        running = false;
        ready = false;
        try { if (sock != null) sock.close(); } catch (IOException ignored) { }
    }

    @Override public boolean connected() { return ready; }

    /** Sends one shell line, without waiting for the result (keys must feel instant). */
    private void line(String cmd) {
        new Thread(() -> {
            try {
                Stream s = shell;
                if (s == null || s.closed) { openShell(); s = shell; }
                if (s != null) { s.send((cmd + "\n").getBytes(StandardCharsets.UTF_8)); return; }
                exec(cmd, 5000);
            } catch (Exception ignored) { }
        }).start();
    }

    @Override public void key(int code, int direction) {
        if (direction == 2) return; // a long press is sent whole on "press"
        line("input keyevent " + (direction == 1 ? "--longpress " : "") + code);
    }

    @Override public void type(String text) {
        StringBuilder b = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == ' ') b.append("%s");
            else if (c == '\'') b.append("'\\''");
            else b.append(c);
        }
        line("input text '" + b + "'");
    }

    @Override public void open(String pkgOrLink) {
        if (pkgOrLink.contains("://")) { line("am start -a android.intent.action.VIEW -d '" + pkgOrLink.replace("'", "") + "'"); return; }
        String p = pkgOrLink.replaceAll("[^\\w.]", "");
        line("monkey -p " + p + " -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 || monkey -p " + p + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1");
    }

    // ------------------------------------------------------------ extras for boxes without our home screen

    /** Packages that show up on the TV launcher (TV apps first). */
    List<String> apps() throws Exception {
        List<String> out = new ArrayList<>();
        for (String cat : new String[]{"android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER"}) {
            String r = sh("cmd package query-activities --brief -a android.intent.action.MAIN -c " + cat + " 2>/dev/null");
            for (String l : r.split("\n")) {
                l = l.trim();
                int slash = l.indexOf('/');
                if (slash <= 0 || l.startsWith("priority")) continue;
                String pkg = l.substring(0, slash);
                if (!out.contains(pkg)) out.add(pkg);
            }
            if (!out.isEmpty()) break;
        }
        if (out.isEmpty()) for (String l : sh("pm list packages -3").split("\n")) if (l.startsWith("package:")) out.add(l.substring(8).trim());
        return out;
    }

    /** PNG of the TV screen. */
    byte[] screenshot() throws Exception { return exec("screencap -p", 8000); }

    /** {title, subtitle, package} of what is playing, or null. */
    String[] nowPlaying() throws Exception {
        String r = sh("dumpsys media_session");
        String pkg = null, title = null;
        boolean playing = false;
        for (String l : r.split("\n")) {
            l = l.trim();
            if (l.startsWith("package=")) { pkg = l.substring(8); playing = false; title = null; }
            else if (l.startsWith("state=PlaybackState {state=3")) playing = true;
            else if (l.startsWith("metadata:") && l.contains("description=")) title = l.substring(l.indexOf("description=") + 12);
            if (playing && title != null && pkg != null) {
                String[] parts = title.split(", ");
                String t = parts[0].trim(), sub = parts.length > 1 && !"null".equals(parts[1].trim()) ? parts[1].trim() : "";
                if (!t.isEmpty() && !"null".equals(t)) return new String[]{t, sub, pkg};
            }
        }
        return null;
    }
}
