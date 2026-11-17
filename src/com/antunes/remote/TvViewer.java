package com.antunes.remote;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs on the TV or box (Comando TV installed there too). Opened by the phone with
 * comandotv://play?s=<share address> to show photos, videos and music straight from the phone,
 * or comandotv://save?s=... to keep the files in the TV's Downloads ("Transferências › ComandoTV").
 * The phone's own remote drives it: OK pauses, left/right change photo or skip 10 s in a video,
 * channel up/down (or next/previous) change item, Back closes.
 */
public class TvViewer extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String[]> items = new ArrayList<>(); // {url, mime, name, size}
    private FrameLayout root;
    private ImageView image;
    private VideoView video;
    private TextView info, center;
    private LinearLayout audioCard;
    private TextView audioName;
    private int index;
    private int loadSeq;
    private final Runnable hideInfo = () -> info.animate().alpha(0f).setDuration(400).start();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i);
    }

    @Override protected void onPause() {
        super.onPause();
        if (video != null && video.isPlaying()) video.pause();
    }

    private void handle(Intent intent) {
        Uri d = intent.getData();
        String base = d == null ? null : d.getQueryParameter("s");
        if (base == null || !base.startsWith("http://")) { finish(); return; }
        boolean save = "save".equals(d.getHost());
        int start = 0;
        try { start = Integer.parseInt(d.getQueryParameter("i")); } catch (Exception ignored) { }
        int first = start;
        root.removeAllViews();
        video = null; image = null; audioCard = null;
        message("A ligar ao telemóvel…", true);
        int seq = ++loadSeq;
        new Thread(() -> {
            try {
                String json = new String(get(base + "/list"), "UTF-8");
                JSONArray a = new JSONArray(json);
                URL u = new URL(base);
                String origin = u.getProtocol() + "://" + u.getHost() + ":" + u.getPort();
                List<String[]> l = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    l.add(new String[]{origin + o.getString("u"), o.optString("t"), o.optString("n"), String.valueOf(o.optLong("s", -1))});
                }
                ui.post(() -> {
                    if (seq != loadSeq) return;
                    items.clear();
                    items.addAll(l);
                    if (items.isEmpty()) { message("O telemóvel já não está a partilhar nada.", false); return; }
                    if (save) saveAll(); else { buildPlayer(); show(Math.max(0, Math.min(first, items.size() - 1))); }
                });
            } catch (Exception e) {
                ui.post(() -> { if (seq == loadSeq) message("Não deu para ligar ao telemóvel.\nConfirma que estão os dois na mesma rede Wi-Fi.", false); });
            }
        }).start();
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    // ------------------------------------------------------------ showing

    private TextView label(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private void message(String s, boolean busy) {
        root.removeAllViews();
        video = null; image = null; audioCard = null;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        if (busy) col.addView(new ProgressBar(this), new LinearLayout.LayoutParams(96, 96));
        TextView t = label(22, 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setText(s);
        t.setPadding(0, 30, 0, 0);
        col.addView(t);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
    }

    private void buildPlayer() {
        root.removeAllViews();
        video = new VideoView(this);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        root.addView(video, vp);
        video.setOnCompletionListener(m -> { if (index < items.size() - 1) show(index + 1); });
        video.setOnErrorListener((m, w, e) -> { center("Este vídeo não abre nesta TV.\nCarrega ▶ para o seguinte."); return true; });

        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));

        audioCard = new LinearLayout(this);
        audioCard.setOrientation(LinearLayout.VERTICAL);
        audioCard.setGravity(Gravity.CENTER);
        TextView note = label(96, 0xFF35C8E0);
        note.setText("♪");
        note.setGravity(Gravity.CENTER);
        audioName = label(30, 0xFFFFFFFF);
        audioName.setGravity(Gravity.CENTER);
        audioName.setPadding(80, 10, 80, 0);
        TextView from = label(18, 0x99FFFFFF);
        from.setText("A tocar a partir do telemóvel");
        from.setGravity(Gravity.CENTER);
        audioCard.addView(note);
        audioCard.addView(audioName);
        audioCard.addView(from);
        root.addView(audioCard, new FrameLayout.LayoutParams(-1, -1));

        center = label(22, 0xFFFFFFFF);
        center.setGravity(Gravity.CENTER);
        root.addView(center, new FrameLayout.LayoutParams(-1, -1));

        info = label(18, 0xFFFFFFFF);
        info.setPadding(36, 18, 36, 18);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xB3000000);
        g.setCornerRadius(40);
        info.setBackground(g);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        ip.setMargins(60, 0, 60, 50);
        root.addView(info, ip);
    }

    private void center(String s) {
        if (center == null) return;
        center.setText(s);
        center.setVisibility(s == null ? View.GONE : View.VISIBLE);
    }

    private boolean isVideo() { return video != null && video.getVisibility() == View.VISIBLE && !items.isEmpty() && !items.get(index)[1].startsWith("image/"); }

    private void show(int i) {
        index = i;
        String[] it = items.get(i);
        String mime = it[1];
        int seq = ++loadSeq;
        center(null);
        video.stopPlayback();
        image.setImageDrawable(null);
        boolean img = mime.startsWith("image/"), av = mime.startsWith("video/") || mime.startsWith("audio/");
        image.setVisibility(img ? View.VISIBLE : View.GONE);
        video.setVisibility(av ? View.VISIBLE : View.GONE);
        audioCard.setVisibility(mime.startsWith("audio/") ? View.VISIBLE : View.GONE);
        if (mime.startsWith("audio/")) audioName.setText(it[2]);
        if (av) {
            video.setVideoURI(Uri.parse(it[0]));
            video.setOnPreparedListener(m -> video.start());
        } else if (img) {
            center("A carregar…");
            new Thread(() -> {
                Bitmap bm = null;
                try { bm = decode(get(it[0])); } catch (Exception ignored) { }
                Bitmap r = bm;
                ui.post(() -> {
                    if (seq != loadSeq) return;
                    if (r == null) { center("Não deu para abrir esta imagem."); return; }
                    center(null);
                    image.setAlpha(0f);
                    image.setImageBitmap(r);
                    image.animate().alpha(1f).setDuration(300).start();
                });
            }).start();
        } else {
            center(it[2] + "\n\nEste ficheiro não se abre aqui.\nCarrega OK para o guardar na TV.");
        }
        showInfo();
    }

    /** Big photos are scaled down to about the screen size so they open fast and never run out of memory. */
    private Bitmap decode(byte[] b) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        int max = Math.max(getResources().getDisplayMetrics().widthPixels, 1920), s = 1;
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        return BitmapFactory.decodeByteArray(b, 0, b.length, o);
    }

    private void showInfo() {
        if (info == null || items.isEmpty()) return;
        String[] it = items.get(index);
        StringBuilder s = new StringBuilder(it[2]);
        if (items.size() > 1) s.append("   ·   ").append(index + 1).append(" de ").append(items.size());
        s.append(isVideo() ? "   ·   OK pausa · ◀ ▶ 10 s" : items.size() > 1 ? "   ·   ◀ ▶ para mudar" : "");
        info.setText(s);
        info.setAlpha(1f);
        ui.removeCallbacks(hideInfo);
        ui.postDelayed(hideInfo, 4000);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (items.isEmpty() || video == null) return super.onKeyDown(code, e);
        boolean vid = isVideo();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (vid) { if (video.isPlaying()) video.pause(); else video.start(); }
                else if (!items.get(index)[1].startsWith("image/")) saveOne(index);
                showInfo();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (vid) video.seekTo(Math.max(0, video.getCurrentPosition() - 10_000));
                else if (index > 0) show(index - 1);
                showInfo();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (vid) video.seekTo(video.getCurrentPosition() + 10_000);
                else if (index < items.size() - 1) show(index + 1);
                showInfo();
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (index < items.size() - 1) show(index + 1);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_DPAD_UP:
                if (index > 0) show(index - 1);
                return true;
        }
        return super.onKeyDown(code, e);
    }

    // ------------------------------------------------------------ keeping files on the TV

    private ProgressBar bar;
    private TextView saveName, saveState;

    private void saveOne(int i) {
        List<String[]> one = new ArrayList<>();
        one.add(items.get(i));
        items.clear();
        items.addAll(one);
        saveAll();
    }

    private void saveAll() {
        if (video != null) video.stopPlayback();
        root.removeAllViews();
        video = null;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(200, 0, 200, 0);
        TextView t = label(30, 0xFFFFFFFF);
        t.setText("A receber do telemóvel");
        t.setGravity(Gravity.CENTER);
        saveName = label(18, 0x99FFFFFF);
        saveName.setGravity(Gravity.CENTER);
        saveName.setPadding(0, 16, 0, 30);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        saveState = label(18, 0xFFFFFFFF);
        saveState.setGravity(Gravity.CENTER);
        saveState.setPadding(0, 24, 0, 0);
        col.addView(t);
        col.addView(saveName);
        col.addView(bar, new LinearLayout.LayoutParams(-1, 18));
        col.addView(saveState);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        List<String[]> l = new ArrayList<>(items);
        new Thread(() -> {
            long total = 0, done = 0;
            for (String[] it : l) total += Math.max(0, Long.parseLong(it[3]));
            int ok = 0;
            for (int k = 0; k < l.size(); k++) {
                String[] it = l.get(k);
                int kk = k;
                ui.post(() -> saveName.setText(it[2] + (l.size() > 1 ? "   (" + (kk + 1) + " de " + l.size() + ")" : "")));
                try {
                    done = download(it, done, total);
                    ok++;
                } catch (Exception e) {
                    ui.post(() -> saveState.setText("Falhou: " + it[2]));
                }
            }
            int saved = ok;
            ui.post(() -> {
                bar.setProgress(1000);
                saveName.setText(saved == 1 ? l.get(0)[2] : saved + " ficheiros");
                saveState.setText(saved > 0 ? "Guardado em Transferências › ComandoTV\n\nCarrega em Voltar para sair." : "Não deu para guardar.");
            });
        }).start();
    }

    private long download(String[] it, long done, long total) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(it[0]).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(30000);
        OutputStream out;
        Uri pending = null;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, it[2]);
            v.put(MediaStore.MediaColumns.MIME_TYPE, it[1]);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ComandoTV");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            pending = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (pending == null) throw new Exception("no storage");
            out = getContentResolver().openOutputStream(pending);
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ComandoTV");
            dir.mkdirs();
            out = new FileOutputStream(new File(dir, it[2]));
        }
        try (InputStream in = c.getInputStream(); OutputStream o = out) {
            byte[] buf = new byte[64 * 1024];
            int n;
            long last = 0;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - last > 200 && total > 0) {
                    last = now;
                    int p = (int) (done * 1000 / total);
                    String s = mb(done) + " de " + mb(total);
                    ui.post(() -> { bar.setProgress(p); saveState.setText(s); });
                }
            }
        }
        if (pending != null) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(pending, v, null, null);
        }
        return done;
    }

    static String mb(long b) {
        if (b < 1024 * 1024) return Math.max(1, b / 1024) + " KB";
        return String.format(java.util.Locale.ROOT, "%.1f MB", b / 1048576.0).replace('.', ',');
    }
}
