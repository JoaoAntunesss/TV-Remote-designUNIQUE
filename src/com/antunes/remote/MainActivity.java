package com.antunes.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Comando TV: the phone remote for the TV-Debloat-designUNIQUE home screen. Same look as the TV
 * (dark glass, white when pressed), works in portrait and landscape.
 */
public class MainActivity extends Activity implements AtvClient.Listener {
    static final int BG = 0xFF0B0D10, GLASS = 0x1AFFFFFF, GLASS_LINE = 0x1FFFFFFF, TXT = 0xFFFFFFFF,
            MUT = 0x99FFFFFF, BLUE = 0xFF2D7FF9, GREEN = 0xFF34C759, RED = 0xFFFF5A52;
    private static final Locale PT = new Locale("pt", "PT");
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Remote client;
    /** ADB to boxes without our home screen (keys too, when the box has no Google remote service). */
    private AdbClient adb;
    /** What this TV can do: our home screen (8765), Google's remote service (6466), network ADB (5555). */
    private boolean hasLauncher, hasAtv, hasAdb;
    private TextView status, clock, date, nowTitle, nowSub;
    private View dot, nowCard;
    private ImageView nowArt, mirror;
    /** Live video of the TV screen (through ADB); the still picture is the fallback. */
    private android.view.TextureView video;
    private LiveScreen live;
    private boolean liveFailed;
    /** We already asked the TV to open Comando TV (screen sharing) this session. */
    private boolean askedTvShare;
    private TextView mirrorMsg;
    private GridLayout apps;
    private LinearLayout bgChips;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock lock;
    private boolean pairing, mirrorOn, visible;
    private String appsJson;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        mirrorOn = prefs.getBoolean("mirror", false);
        loadCaps();
        setContentView(build());
        showCachedApps();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        setContentView(build());
        if (appsJson != null) try { fillApps(appsJson); } catch (Exception ignored) { }
        refreshTv();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        String host = host();
        if (host.isEmpty()) discover();
        else connect(host);
        ui.post(tick);
        ui.post(mirrorLoop);
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        if (client != null) client.stop();
        client = null;
        stopLive();
        if (adb != null) adb.stop();
        adb = null;
        stopDiscovery();
    }

    private void loadCaps() {
        String c = prefs.getString("caps:" + host(), "L");
        hasLauncher = c.contains("L"); hasAtv = c.contains("G"); hasAdb = c.contains("A");
    }

    private boolean canMirror() { return hasLauncher || hasAdb || hasAtv; }

    private String host() { return prefs.getString("host", ""); }

    // ------------------------------------------------------------ look (same as the TV home screen)

    private boolean wide() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.5f, 0.0f);
        glow.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.9f);
        glow.setColors(new int[]{0xFF18606D, 0xFF0F2A31, BG});
        root.setBackground(new LayerDrawable(new android.graphics.drawable.Drawable[]{new android.graphics.drawable.ColorDrawable(BG), glow}));

        if (wide()) {
            LinearLayout row = hlay();
            row.setGravity(Gravity.TOP);
            row.setPadding(dp(20), dp(14), dp(20), dp(10));
            LinearLayout left = vlay();
            left.addView(header());
            left.addView(remoteBlock(), lp(-1, -2, 0, dp(12), 0, 0));
            row.addView(left, new LinearLayout.LayoutParams(0, -1, 0.42f));
            ScrollView sv = new ScrollView(this);
            sv.setVerticalScrollBarEnabled(false);
            LinearLayout right = vlay();
            right.setPadding(dp(20), 0, 0, dp(12));
            sv.addView(right);
            extras(right);
            row.addView(sv, new LinearLayout.LayoutParams(0, -1, 0.58f));
            root.addView(row, new FrameLayout.LayoutParams(-1, -1));
        } else {
            ScrollView sv = new ScrollView(this);
            sv.setVerticalScrollBarEnabled(false);
            LinearLayout col = vlay();
            col.setPadding(dp(18), dp(12), dp(18), dp(24));
            sv.addView(col);
            col.addView(header());
            if (canMirror()) col.addView(mirrorBlock(), lp(-1, -2, 0, dp(14), 0, 0));
            col.addView(remoteBlock(), lp(-1, -2, 0, dp(14), 0, 0));
            extrasNoMirror(col);
            root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        }
        return root;
    }

    private View header() {
        LinearLayout head = hlay();
        LinearLayout when = vlay();
        clock = text(30, TXT, "sans-serif-thin");
        clock.setIncludeFontPadding(false);
        date = text(10, MUT, "sans-serif-medium");
        date.setLetterSpacing(0.2f);
        when.addView(clock);
        when.addView(date);
        head.addView(when, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout st = hlay();
        st.setPadding(dp(12), 0, dp(14), 0);
        st.setBackground(glass(dp(17)));
        dot = new View(this);
        GradientDrawable dd = new GradientDrawable();
        dd.setShape(GradientDrawable.OVAL);
        dd.setColor(0xFF777777);
        dot.setBackground(dd);
        st.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        status = text(12, TXT, "sans-serif-medium");
        status.setText(prefs.getString("name", "TV"));
        status.setSingleLine(true);
        status.setMaxWidth(dp(150));
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        st.addView(status, lp(-2, -2, dp(7), 0, 0, 0));
        st.setOnClickListener(v -> chooseTv());
        head.addView(st, new LinearLayout.LayoutParams(-2, dp(34)));
        head.addView(round(Icon.POWER, RED, 40, () -> key(AtvClient.K_POWER)), lp(dp(40), dp(40), dp(8), 0, 0, 0));
        tick.run();
        return head;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (clock == null) return;
            Date d = new Date();
            clock.setText(new SimpleDateFormat("H:mm", PT).format(d));
            date.setText(new SimpleDateFormat("EEEE  d MMMM", PT).format(d).toUpperCase(PT));
            ui.removeCallbacks(this);
            if (visible) ui.postDelayed(this, 20_000);
        }
    };

    /** Touchpad + buttons. */
    private View remoteBlock() {
        LinearLayout col = vlay();
        Pad pad = new Pad(this);
        pad.arrows = prefs.getBoolean("arrows", true);
        LinearLayout modeRow = hlay();
        modeRow.setGravity(Gravity.END);
        TextView mode = chip(pad.arrows ? "Mudar para deslizar" : "Mudar para setas");
        mode.setOnClickListener(v -> {
            pad.arrows = !pad.arrows;
            prefs.edit().putBoolean("arrows", pad.arrows).apply();
            mode.setText(pad.arrows ? "Mudar para deslizar" : "Mudar para setas");
            pad.invalidate();
        });
        modeRow.addView(mode, new LinearLayout.LayoutParams(-2, dp(32)));
        col.addView(modeRow, lp(-1, -2, 0, 0, 0, dp(8)));
        col.addView(pad, new LinearLayout.LayoutParams(-1, wide() ? dp(190) : dp(270)));

        LinearLayout r1 = hlay();
        r1.setGravity(Gravity.CENTER);
        int[][] btn = {{Icon.BACK, AtvClient.K_BACK}, {Icon.HOME, AtvClient.K_HOME}, {Icon.PLAY_PAUSE, AtvClient.K_PLAY_PAUSE}, {Icon.MENU, AtvClient.K_MENU}};
        for (int[] x : btn) r1.addView(round(x[0], TXT, 50, () -> key(x[1])), lp(dp(50), dp(50), dp(7), 0, dp(7), 0));
        col.addView(r1, lp(-1, -2, 0, dp(14), 0, 0));

        LinearLayout r2 = hlay();
        r2.setGravity(Gravity.CENTER);
        LinearLayout vol = hlay();
        vol.setBackground(glass(dp(25)));
        vol.addView(flat(Icon.VOL_DOWN, () -> key(AtvClient.K_VOL_DOWN)), new LinearLayout.LayoutParams(dp(54), dp(50)));
        vol.addView(flat(Icon.MUTE, () -> key(AtvClient.K_MUTE)), new LinearLayout.LayoutParams(dp(54), dp(50)));
        vol.addView(flat(Icon.VOL_UP, () -> key(AtvClient.K_VOL_UP)), new LinearLayout.LayoutParams(dp(54), dp(50)));
        r2.addView(vol);
        r2.addView(round(Icon.SEARCH, TXT, 50, () -> key(AtvClient.K_SEARCH)), lp(dp(50), dp(50), dp(10), 0, 0, 0));
        r2.addView(round(Icon.KEYBOARD, TXT, 50, this::typeText), lp(dp(50), dp(50), dp(10), 0, 0, 0));
        col.addView(r2, lp(-1, -2, 0, dp(10), 0, 0));

        LinearLayout r3 = hlay();
        r3.setGravity(Gravity.CENTER);
        LinearLayout media = hlay();
        media.setBackground(glass(dp(21)));
        media.addView(flat(Icon.REW, () -> key(AtvClient.K_REWIND)), new LinearLayout.LayoutParams(dp(50), dp(42)));
        media.addView(flat(Icon.FWD, () -> key(AtvClient.K_FAST_FORWARD)), new LinearLayout.LayoutParams(dp(50), dp(42)));
        r3.addView(media);
        LinearLayout ch = hlay();
        ch.setBackground(glass(dp(21)));
        TextView chl = text(11, MUT, "sans-serif-medium");
        chl.setText("CANAL");
        chl.setPadding(dp(12), 0, dp(2), 0);
        ch.addView(chl);
        ch.addView(flat(Icon.DOWN, () -> key(AtvClient.K_CH_DOWN)), new LinearLayout.LayoutParams(dp(46), dp(42)));
        ch.addView(flat(Icon.UP, () -> key(AtvClient.K_CH_UP)), new LinearLayout.LayoutParams(dp(46), dp(42)));
        r3.addView(ch, lp(-2, -2, dp(10), 0, 0, 0));
        col.addView(r3, lp(-1, -2, 0, dp(10), 0, 0));
        return col;
    }

    private void extras(LinearLayout col) {
        if (canMirror()) col.addView(mirrorBlock());
        extrasNoMirror(col);
    }

    private void extrasNoMirror(LinearLayout col) {
        // now playing on the TV
        LinearLayout now = hlay();
        now.setPadding(dp(10), dp(10), dp(14), dp(10));
        now.setBackground(glass(dp(20)));
        nowArt = new ImageView(this);
        nowArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        clip(nowArt, dp(12));
        now.addView(nowArt, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout nt = vlay();
        TextView cap = text(10, MUT, "sans-serif-medium");
        cap.setText("A DAR NA TV");
        cap.setLetterSpacing(0.15f);
        nowTitle = text(15, TXT, "sans-serif-medium");
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowSub = text(12, MUT, null);
        nowSub.setSingleLine(true);
        nt.addView(cap);
        nt.addView(nowTitle);
        nt.addView(nowSub);
        now.addView(nt, new LinearLayout.LayoutParams(0, -2, 1f));
        now.setVisibility(View.GONE);
        nowCard = now;
        col.addView(now, lp(-1, -2, 0, dp(16), 0, 0));

        // TV functions
        col.addView(section("Na TV"));
        GridLayout g = new GridLayout(this);
        g.setColumnCount(wide() ? 3 : 2);
        Object[][] acts = hasLauncher ? new Object[][]{
            {Icon.FIRE, "Lareira", (Runnable) () -> tvDo("fire")},
            {Icon.CLOCK, "Relógio", (Runnable) () -> tvDo("clock")},
            {Icon.INPUT, "Fontes · HDMI", (Runnable) () -> pickInput(false)},
            {Icon.SPLIT, "Ecrã dividido", (Runnable) () -> pickInput(true)},
            {Icon.APPS, "Todas as apps", (Runnable) () -> tvDo("apps")},
            {Icon.GEAR, "Definições", (Runnable) () -> key(AtvClient.K_SETTINGS)},
        } : new Object[][]{ // any Android TV or box
            {Icon.INPUT, "Fontes · HDMI", (Runnable) () -> key(AtvClient.K_TV_INPUT)},
            {Icon.APPS, "Todas as apps", (Runnable) () -> key(K_ALL_APPS)},
            {Icon.GEAR, "Definições", (Runnable) () -> key(AtvClient.K_SETTINGS)},
            {Icon.HOME, "Ecrã inicial", (Runnable) () -> key(AtvClient.K_HOME)},
        };
        int cols = wide() ? 3 : 2;
        int cw = (contentWidth() - dp(10) * (cols - 1)) / cols;
        for (int i = 0; i < acts.length; i++) {
            View t = tile((int) acts[i][0], (String) acts[i][1], (Runnable) acts[i][2]);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = cw;
            p.height = dp(52);
            p.setMargins(i % cols == 0 ? 0 : dp(10), 0, 0, dp(10));
            g.addView(t, p);
        }
        col.addView(g);

        // background theme of the TV home screen (only with our home screen)
        bgChips = hlay();
        if (hasLauncher) col.addView(section("Fundo do ecrã inicial"));
        String[][] modes = {{"canal", "Canal"}, {"lareira", "Lareira"}, {"fotos", "Fotos"}};
        for (String[] m : modes) {
            TextView c = chip(m[1]);
            c.setTag(m[0]);
            c.setOnClickListener(v -> setBg(m[0]));
            bgChips.addView(c, lp(-2, dp(36), 0, 0, dp(8), 0));
        }
        markBg(prefs.getString("bg", ""));
        if (hasLauncher) col.addView(bgChips);

        // apps
        col.addView(section(hasLauncher || hasAdb ? "Apps" : "Apps conhecidas"));
        apps = new GridLayout(this);
        apps.setColumnCount(wide() ? 4 : 3);
        col.addView(apps, new LinearLayout.LayoutParams(-1, -2));
    }

    private int contentWidth() {
        int w = getResources().getDisplayMetrics().widthPixels;
        return wide() ? (int) ((w - dp(40)) * 0.58f) - dp(20) : w - dp(36);
    }

    private View mirrorBlock() {
        LinearLayout col = vlay();
        LinearLayout top = hlay();
        TextView t = text(15, TXT, "sans-serif-medium");
        t.setText("Ecrã da TV");
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView sw = chip(mirrorOn ? "Esconder" : "Ver aqui");
        sw.setOnClickListener(v -> {
            mirrorOn = !mirrorOn;
            prefs.edit().putBoolean("mirror", mirrorOn).apply();
            sw.setText(mirrorOn ? "Esconder" : "Ver aqui");
            ((View) mirror.getParent()).setVisibility(mirrorOn ? View.VISIBLE : View.GONE);
            if (!mirrorOn) stopLive();
            ui.post(mirrorLoop);
        });
        top.addView(sw, new LinearLayout.LayoutParams(-2, dp(32)));
        col.addView(top);
        FrameLayout box = new FrameLayout(this) {
            @Override protected void onMeasure(int w, int h) {
                int width = MeasureSpec.getSize(w);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
            }
        };
        box.setBackground(glass(dp(16)));
        clip(box, dp(16));
        stopLive();
        video = new android.view.TextureView(this);
        video.setVisibility(View.GONE);
        box.addView(video, new FrameLayout.LayoutParams(-1, -1));
        mirror = new ImageView(this);
        mirror.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(mirror, new FrameLayout.LayoutParams(-1, -1));
        mirrorMsg = text(12, MUT, null);
        mirrorMsg.setGravity(Gravity.CENTER);
        mirrorMsg.setText("A ligar ao ecrã da TV…");
        box.addView(mirrorMsg, new FrameLayout.LayoutParams(-1, -1));
        // the picture also works as a touchpad
        Pad overlay = new Pad(this);
        overlay.ghost = true;
        box.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        box.setVisibility(mirrorOn ? View.VISIBLE : View.GONE);
        col.addView(box, lp(-1, -2, 0, dp(8), 0, 0));
        return col;
    }

    // ------------------------------------------------------------ small widgets

    private LinearLayout vlay() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }

    private LinearLayout hlay() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private TextView text(float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (family != null) t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }

    private TextView section(String s) {
        TextView t = text(11, MUT, "sans-serif-medium");
        t.setText(s.toUpperCase(PT));
        t.setLetterSpacing(0.2f);
        t.setPadding(dp(2), dp(20), 0, dp(10));
        return t;
    }

    private GradientDrawable glass(int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(GLASS);
        g.setStroke(dp(1), GLASS_LINE);
        g.setCornerRadius(radius);
        return g;
    }

    private void clip(View v, int radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View x, Outline o) { o.setRoundRect(0, 0, x.getWidth(), x.getHeight(), radius); }
        });
        v.setClipToOutline(true);
    }

    /** Glass circle; white with a dark icon while pressed, like the TV's focus. */
    private ImageView round(int icon, int color, int sizeDp, Runnable act) {
        ImageView v = new ImageView(this);
        Icon ic = new Icon(icon, color);
        v.setImageDrawable(ic);
        int pad = dp(sizeDp * 0.3f);
        v.setPadding(pad, pad, pad, pad);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(GLASS);
        g.setStroke(dp(1), GLASS_LINE);
        v.setBackground(g);
        press(v, () -> { g.setColor(0xF2FFFFFF); ic.setColor(0xFF111111); }, () -> { g.setColor(GLASS); ic.setColor(color); }, act);
        return v;
    }

    private ImageView flat(int icon, Runnable act) {
        ImageView v = new ImageView(this);
        Icon ic = new Icon(icon, TXT);
        v.setImageDrawable(ic);
        v.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(dp(25));
        v.setBackground(g);
        press(v, () -> { g.setColor(0xF2FFFFFF); ic.setColor(0xFF111111); }, () -> { g.setColor(Color.TRANSPARENT); ic.setColor(TXT); }, act);
        return v;
    }

    private TextView chip(String label) {
        TextView c = text(13, TXT, "sans-serif-medium");
        c.setText(label);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(16), 0, dp(16), 0);
        c.setBackground(glass(dp(18)));
        return c;
    }

    private void markBg(String mode) {
        if (bgChips == null) return;
        for (int i = 0; i < bgChips.getChildCount(); i++) {
            TextView c = (TextView) bgChips.getChildAt(i);
            boolean on = mode.equals(c.getTag());
            GradientDrawable g = glass(dp(18));
            if (on) g.setColor(0xF2FFFFFF);
            c.setBackground(g);
            c.setTextColor(on ? 0xFF111111 : TXT);
        }
    }

    private View tile(int icon, String label, Runnable act) {
        LinearLayout t = hlay();
        t.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable bg = glass(dp(18));
        t.setBackground(bg);
        ImageView iv = new ImageView(this);
        Icon ic = new Icon(icon, TXT);
        iv.setImageDrawable(ic);
        GradientDrawable ib = new GradientDrawable();
        ib.setShape(GradientDrawable.OVAL);
        ib.setColor(0x33FFFFFF);
        iv.setBackground(ib);
        iv.setPadding(dp(7), dp(7), dp(7), dp(7));
        t.addView(iv, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView l = text(13, TXT, "sans-serif-medium");
        l.setText(label);
        l.setSingleLine(true);
        t.addView(l, lp(-2, -2, dp(10), 0, 0, 0));
        press(t, () -> { bg.setColor(0xF2FFFFFF); l.setTextColor(0xFF111111); ib.setColor(BLUE); },
                () -> { bg.setColor(GLASS); l.setTextColor(TXT); ib.setColor(0x33FFFFFF); }, act);
        return t;
    }

    private void press(View v, Runnable down, Runnable up, Runnable act) {
        v.setOnTouchListener((x, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down.run();
                    x.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80).start();
                    x.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    return true;
                case MotionEvent.ACTION_UP:
                    up.run();
                    x.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    if (e.getX() >= 0 && e.getY() >= 0 && e.getX() <= x.getWidth() && e.getY() <= x.getHeight()) act.run();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    up.run();
                    x.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    return true;
            }
            return true;
        });
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    /** Swipe area: each step of the finger sends one arrow; a tap sends OK; holding sends a long OK. */
    final class Pad extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Icon[] chev = {new Icon(Icon.UP, 0x55FFFFFF), new Icon(Icon.DOWN, 0x55FFFFFF), new Icon(Icon.LEFT, 0x55FFFFFF), new Icon(Icon.RIGHT, 0x55FFFFFF)};
        private float sx, sy, lx, ly, glow;
        private long downAt;
        private boolean moved, longSent;
        boolean ghost;
        /** Arrow buttons (tap the side to move, the middle for OK) instead of swiping. */
        boolean arrows;
        private int held = -1;
        private final Runnable repeat = new Runnable() {
            @Override public void run() { if (held < 0) return; send(held); postDelayed(this, 120); }
        };
        private final Runnable longPress = () -> {
            if (moved) return;
            longSent = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (client != null) client.key(AtvClient.K_CENTER, 1);
        };

        Pad(Context c) {
            super(c);
            fill.setColor(GLASS);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(1.5f));
        }

        @Override protected void onDraw(Canvas c) {
            if (ghost) return;
            box.set(0, 0, getWidth(), getHeight());
            fill.setColor(GLASS);
            c.drawRoundRect(box, dp(26), dp(26), fill);
            line.setColor(GLASS_LINE);
            c.drawRoundRect(box, dp(26), dp(26), line);
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = dp(36);
            if (arrows) { drawArrows(c, cx, cy); return; }
            line.setColor(glow > 0 ? 0xFFFFFFFF : 0x40FFFFFF);
            c.drawCircle(cx, cy, r, line);
            int s = dp(20);
            chev[0].setBounds((int) cx - s / 2, dp(12), (int) cx + s / 2, dp(12) + s);
            chev[1].setBounds((int) cx - s / 2, getHeight() - dp(12) - s, (int) cx + s / 2, getHeight() - dp(12));
            chev[2].setBounds(dp(12), (int) cy - s / 2, dp(12) + s, (int) cy + s / 2);
            chev[3].setBounds(getWidth() - dp(12) - s, (int) cy - s / 2, getWidth() - dp(12), (int) cy + s / 2);
            for (Icon i : chev) i.draw(c);
        }

        private final Icon[] big = {new Icon(Icon.UP, 0xFFFFFFFF), new Icon(Icon.DOWN, 0xFFFFFFFF), new Icon(Icon.LEFT, 0xFFFFFFFF), new Icon(Icon.RIGHT, 0xFFFFFFFF)};

        private float ring() { return Math.min(getWidth(), getHeight()) / 2f - dp(12); }
        private float okR() { return ring() * 0.36f; }

        private void drawArrows(Canvas c, float cx, float cy) {
            float R = ring(), r = okR();
            line.setColor(0x40FFFFFF);
            c.drawCircle(cx, cy, R, line);
            fill.setColor(held == AtvClient.K_CENTER || glow > 0 ? 0x55FFFFFF : 0x22FFFFFF);
            c.drawCircle(cx, cy, r, fill);
            c.drawCircle(cx, cy, r, line);
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(0xFFFFFFFF); t.setTextSize(dp(17)); t.setTextAlign(Paint.Align.CENTER);
            t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            c.drawText("OK", cx, cy + dp(6), t);
            int s = dp(28);
            float d = (R + r) / 2f;
            int[] codes = {AtvClient.K_UP, AtvClient.K_DOWN, AtvClient.K_LEFT, AtvClient.K_RIGHT};
            float[][] at = {{cx, cy - d}, {cx, cy + d}, {cx - d, cy}, {cx + d, cy}};
            for (int i = 0; i < 4; i++) {
                if (held == codes[i]) { fill.setColor(0x33FFFFFF); c.drawCircle(at[i][0], at[i][1], dp(26), fill); }
                big[i].setBounds((int) at[i][0] - s / 2, (int) at[i][1] - s / 2, (int) at[i][0] + s / 2, (int) at[i][1] + s / 2);
                big[i].draw(c);
            }
        }

        /** Which button a finger is on in arrow mode. */
        private int zone(float x, float y) {
            float dx = x - getWidth() / 2f, dy = y - getHeight() / 2f;
            if (Math.hypot(dx, dy) <= okR()) return AtvClient.K_CENTER;
            if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? AtvClient.K_RIGHT : AtvClient.K_LEFT;
            return dy > 0 ? AtvClient.K_DOWN : AtvClient.K_UP;
        }

        private boolean arrowTouch(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    held = zone(e.getX(), e.getY());
                    longSent = false; moved = false;
                    if (held == AtvClient.K_CENTER) postDelayed(longPress, 650);
                    else { send(held); postDelayed(repeat, 450); }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(repeat); removeCallbacks(longPress);
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (held == AtvClient.K_CENTER && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        if (longSent) { if (client != null) client.key(AtvClient.K_CENTER, 2); }
                        else send(AtvClient.K_CENTER);
                    }
                    held = -1;
                    invalidate();
                    return true;
            }
            return true;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (arrows && !ghost) return arrowTouch(e);
            float step = dp(44);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = lx = e.getX(); sy = ly = e.getY(); downAt = SystemClock.uptimeMillis(); moved = false; longSent = false;
                    postDelayed(longPress, 650);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getX() - lx, dy = e.getY() - ly;
                    if (Math.abs(dx) >= step || Math.abs(dy) >= step) {
                        if (Math.abs(dx) > Math.abs(dy)) send(dx > 0 ? AtvClient.K_RIGHT : AtvClient.K_LEFT);
                        else send(dy > 0 ? AtvClient.K_DOWN : AtvClient.K_UP);
                        lx = e.getX(); ly = e.getY(); moved = true;
                        removeCallbacks(longPress);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (longSent) { if (client != null) client.key(AtvClient.K_CENTER, 2); return true; }
                    if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) return true;
                    float tx = e.getX() - sx, ty = e.getY() - sy;
                    if (!moved) {
                        if (Math.hypot(tx, ty) > dp(18)) {
                            if (Math.abs(tx) > Math.abs(ty)) send(tx > 0 ? AtvClient.K_RIGHT : AtvClient.K_LEFT);
                            else send(ty > 0 ? AtvClient.K_DOWN : AtvClient.K_UP);
                        } else {
                            send(AtvClient.K_CENTER);
                            glow = 1; invalidate();
                            postDelayed(() -> { glow = 0; invalidate(); }, 150);
                        }
                    }
                    return true;
            }
            return super.onTouchEvent(e);
        }

        private void send(int code) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            key(code);
        }
    }

    // ------------------------------------------------------------ talking to the TV

    static final int K_ALL_APPS = 284;

    private void key(int code) {
        if (client == null || !client.connected()) { toast("A TV não está ligada"); return; }
        client.key(code);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void typeText() {
        if (client == null || !client.connected()) { toast("A TV não está ligada"); return; }
        EditText t = new EditText(this);
        t.setSingleLine(true);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Escrever na TV")
                .setMessage("Abre primeiro uma caixa de pesquisa ou de texto na TV.")
                .setView(t)
                .setPositiveButton("Enviar", (d, w) -> { client.type(t.getText().toString()); })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** HTTP to the TV home screen (port 8765). Callback gets null on failure. */
    private void http(String path, java.util.function.Consumer<byte[]> done) {
        String h = host();
        if (h.isEmpty() || !hasLauncher) { if (done != null) ui.post(() -> done.accept(null)); return; }
        new Thread(() -> {
            byte[] out = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://" + h + ":8765" + path).openConnection();
                c.setConnectTimeout(3000);
                c.setReadTimeout(15000);
                if (c.getResponseCode() == 200) try (InputStream in = c.getInputStream()) { out = readAll(in); }
            } catch (Exception ignored) { }
            byte[] r = out;
            if (done != null) ui.post(() -> done.accept(r));
        }).start();
    }

    private static String str(byte[] b) { return b == null ? null : new String(b, StandardCharsets.UTF_8); }

    /** Actions run by the TV home screen. It brings itself to the front first (Home) so it may open things. */
    private void tvDo(String action) { tvDo(action, ""); }

    private void tvDo(String action, String extra) {
        if (client != null && client.connected()) client.key(AtvClient.K_HOME);
        ui.postDelayed(() -> http("/do?a=" + action + extra, b -> {
            if (b == null) toast("A TV ainda não tem esta função (atualiza o ecrã inicial)");
        }), 700);
    }

    private void setBg(String mode) {
        http("/bg?mode=" + mode, b -> {
            if (b == null) { toast("A TV ainda não tem esta função (atualiza o ecrã inicial)"); return; }
            prefs.edit().putString("bg", mode).apply();
            markBg(mode);
        });
    }

    private void pickInput(boolean split) {
        http("/inputs", b -> {
            try {
                JSONArray a = new JSONArray(str(b));
                String[] names = new String[a.length()];
                for (int i = 0; i < a.length(); i++) names[i] = a.getJSONObject(i).getString("label");
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(split ? "Ecrã dividido com…" : "Mudar para…")
                        .setItems(names, (d, w) -> {
                            try {
                                String id = URLEncoder.encode(a.getJSONObject(w).getString("id"), "UTF-8");
                                tvDo(split ? "split" : "input", "&id=" + id);
                            } catch (Exception ignored) { }
                        })
                        .show();
            } catch (Exception e) {
                // older home screen: fall back to the TV's own source picker
                key(AtvClient.K_MENU);
            }
        });
    }

    private void refreshTv() {
        if (!hasLauncher) { refreshTvAdb(); return; }
        http("/now", b -> {
            try {
                JSONObject o = new JSONObject(str(b));
                String t = o.optString("title", "");
                nowCard.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
                nowTitle.setText(t);
                nowSub.setText(o.optString("sub", ""));
                byte[] img = Base64.decode(o.optString("img", ""), Base64.DEFAULT);
                Bitmap bm = BitmapFactory.decodeByteArray(img, 0, img.length);
                nowArt.setImageBitmap(bm);
                String pkg = o.optString("pkg", "");
                nowCard.setOnClickListener(pkg.isEmpty() ? null : v -> { if (client != null) client.open(pkg); });
                String bg = o.optString("bg", "");
                if (!bg.isEmpty()) { prefs.edit().putString("bg", bg).apply(); markBg(bg); }
            } catch (Exception e) {
                if (nowCard != null) nowCard.setVisibility(View.GONE);
            }
        });
    }

    /** "A dar na TV" read from the box's media sessions over ADB. */
    private void refreshTvAdb() {
        AdbClient a = adb;
        if (a == null || !a.connected() || nowCard == null) { if (nowCard != null) nowCard.setVisibility(View.GONE); return; }
        new Thread(() -> {
            String[] n = null;
            try { n = a.nowPlaying(); } catch (Exception ignored) { }
            String[] r = n;
            ui.post(() -> {
                if (nowCard == null) return;
                nowCard.setVisibility(r == null ? View.GONE : View.VISIBLE);
                if (r == null) return;
                nowTitle.setText(r[0]);
                nowSub.setText(r[1].isEmpty() ? appName(r[2]) : r[1]);
                nowArt.setImageBitmap(letterTile(appName(r[2])));
                nowCard.setOnClickListener(v -> { if (client != null) client.open(r[2]); });
            });
        }).start();
    }

    private final Runnable nowLoop = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            refreshTv();
            ui.postDelayed(this, 10_000);
        }
    };

    /** Picture of the TV screen, refreshed a few times per second while shown. */
    private boolean mirrorBusy;
    private final Runnable mirrorLoop = new Runnable() {
        @Override public void run() {
            ui.removeCallbacks(this);
            if (!visible || !mirrorOn || mirror == null || mirrorBusy) return;
            AdbClient a = adb;
            if (a != null && a.connected() && !liveFailed) { startLive(LiveScreen.adb(a), true); return; }
            if (live != null && live.running()) return;
            if (!hasLauncher && !hasAdb) { startLive(LiveScreen.tv(host()), false); return; } // Comando TV on the TV shares its screen
            mirrorBusy = true;
            if (!hasLauncher) { mirrorAdb(); return; }
            http("/shot.jpg", b -> {
                mirrorBusy = false;
                Bitmap bm = b == null ? null : BitmapFactory.decodeByteArray(b, 0, b.length);
                if (bm != null) { mirror.setImageBitmap(bm); mirrorMsg.setVisibility(View.GONE); }
                else { mirrorMsg.setVisibility(View.VISIBLE); mirrorMsg.setText("O ecrã da TV ainda não está disponível.\nAtualiza o ecrã inicial da TV."); }
                ui.postDelayed(mirrorLoop, bm != null ? 200 : 3000);
            });
        }
    };

    /** Starts the live video of the TV screen (about 30 pictures per second). */
    private void startLive(LiveScreen.Opener source, boolean viaAdb) {
        if (live != null && live.running()) return;
        android.graphics.SurfaceTexture st = video.getSurfaceTexture();
        video.setVisibility(View.VISIBLE);
        if (st == null) { // the video surface is created once it is on screen
            video.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture t, int w, int h) { ui.post(mirrorLoop); }
                @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture t, int w, int h) { }
                @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture t) { stopLive(); return true; }
                @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture t) { }
            });
            return;
        }
        st.setDefaultBufferSize(LiveScreen.W, LiveScreen.H);
        mirrorMsg.setVisibility(View.VISIBLE);
        mirrorMsg.setText("A ligar ao ecrã da TV…");
        live = new LiveScreen(source, new android.view.Surface(st), new LiveScreen.Events() {
            @Override public void onFirstFrame() { ui.post(() -> {
                if (!viaAdb) loadApps(); // Comando TV on the TV can now list every app
                if (mirror == null) return;
                mirror.setVisibility(View.GONE); mirrorMsg.setVisibility(View.GONE);
            }); }
            @Override public void onFailed() { ui.post(() -> {
                if (!viaAdb) { // Comando TV is not sharing yet: open it on the TV and keep trying
                    if (mirrorMsg == null) return;
                    mirrorMsg.setVisibility(View.VISIBLE);
                    mirrorMsg.setText("Para veres o ecrã aqui, instala o Comando TV também na TV.\nDepois abre-o na TV e escolhe «Começar agora».");
                    if (!askedTvShare && client != null && client.connected()) { askedTvShare = true; client.open("com.antunes.remote"); }
                    ui.postDelayed(mirrorLoop, 4000);
                    return;
                }
                // this box cannot record its screen: go back to pictures
                liveFailed = true;
                if (video != null) video.setVisibility(View.GONE);
                if (mirror != null) mirror.setVisibility(View.VISIBLE);
                ui.post(mirrorLoop);
            }); }
        });
        live.start();
    }

    private void stopLive() {
        if (live != null) live.stop();
        live = null;
    }

    /** Screenshot through ADB: slower than our home screen, about one picture per second. */
    private void mirrorAdb() {
        AdbClient a = adb;
        new Thread(() -> {
            Bitmap bm = null;
            if (a != null && a.connected()) try {
                byte[] png = a.screenshot();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                bm = BitmapFactory.decodeByteArray(png, 0, png.length, o);
            } catch (Exception ignored) { }
            Bitmap r = bm;
            ui.post(() -> {
                mirrorBusy = false;
                if (mirror == null) return;
                if (r != null) { mirror.setImageBitmap(r); mirrorMsg.setVisibility(View.GONE); }
                else { mirrorMsg.setVisibility(View.VISIBLE); mirrorMsg.setText("A ligar à box…\nNa TV, aceita «Permitir depuração» se aparecer."); }
                ui.postDelayed(mirrorLoop, r != null ? 300 : 3000);
            });
        }).start();
    }

    // ------------------------------------------------------------ connection

    private static boolean portOpen(String host, int port, int ms) {
        try (java.net.Socket s = new java.net.Socket()) { s.connect(new java.net.InetSocketAddress(host, port), ms); return true; }
        catch (Exception e) { return false; }
    }

    private static boolean launcherAt(String host) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":8765/now").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(3000);
            return c.getResponseCode() == 200;
        } catch (Exception e) { return false; }
    }

    /** Finds out what this TV or box can do, then talks to it the best way it allows. */
    private void connect(String host) {
        if (client != null) client.stop();
        if (adb != null) adb.stop();
        client = null; adb = null;
        status.setText("A ligar…");
        new Thread(() -> {
            boolean g = portOpen(host, 6466, 2000), l = launcherAt(host), a = portOpen(host, AdbClient.PORT, 1500);
            ui.post(() -> {
                if (!host.equals(host()) || !visible) return;
                boolean changed = g != hasAtv || l != hasLauncher || a != hasAdb;
                hasAtv = g; hasLauncher = l; hasAdb = a;
                if (g || l || a) prefs.edit().putString("caps:" + host, (l ? "L" : "") + (g ? "G" : "") + (a ? "A" : "")).apply();
                if (changed) { setContentView(build()); showCachedApps(); }
                start(host);
            });
        }).start();
    }

    private void start(String host) {
        if (hasAtv || !hasAdb) {
            AtvClient c = new AtvClient(host, this);
            client = c;
            new Thread(c::run).start();
        }
        liveFailed = false;
        if (hasAdb) { // ADB gives keys (if needed), apps, live picture and what is playing
            boolean primary = client == null;
            AdbClient a = new AdbClient(host, primary ? this : (state, ok) -> {
                if ("ALLOW".equals(state)) ui.post(this::allowDebugging);
                if (ok) ui.post(() -> { loadApps(); refreshTv(); ui.post(mirrorLoop); });
            });
            adb = a;
            if (primary) client = a;
            new Thread(a::run).start();
        }
        loadApps();
        ui.removeCallbacks(nowLoop);
        ui.post(nowLoop);
        ui.post(mirrorLoop);
    }

    private boolean allowShown;

    private void allowDebugging() {
        if (allowShown) return;
        allowShown = true;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Aceita na TV")
                .setMessage("Na TV aparece «Permitir depuração?». Marca «Permitir sempre a partir deste computador» e carrega em OK. Só é preciso uma vez.")
                .setPositiveButton("OK", (d, w) -> allowShown = false)
                .show();
    }

    @Override
    public void onState(String state, boolean connected) {
        ui.post(() -> {
            if ("PAIR".equals(state)) { pair(); return; }
            if ("ALLOW".equals(state)) { allowDebugging(); status.setText("Aceita na TV…"); return; }
            if (connected && client == adb && adb != null) { loadApps(); refreshTv(); ui.post(mirrorLoop); }
            status.setText(connected ? prefs.getString("name", "TV") : state);
            ((GradientDrawable) dot.getBackground()).setColor(connected ? GREEN : 0xFF777777);
        });
    }

    private void pair() {
        if (pairing) return;
        pairing = true;
        status.setText("A pedir código…");
        String host = host();
        AtvClient p = new AtvClient(host, this);
        new Thread(() -> {
            try {
                p.startPairing();
                ui.post(() -> askCode(p, host));
            } catch (Exception e) {
                pairing = false;
                ui.post(() -> status.setText("Sem emparelhar"));
            }
        }).start();
    }

    private void askCode(AtvClient p, String host) {
        EditText code = new EditText(this);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6), new InputFilter.AllCaps()});
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        code.setGravity(Gravity.CENTER);
        code.setLetterSpacing(0.3f);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Código na TV")
                .setMessage("Escreve o código de 6 caracteres que apareceu na TV.")
                .setView(code)
                .setCancelable(false)
                .setPositiveButton("Emparelhar", (d, w) -> new Thread(() -> {
                    try {
                        p.finishPairing(code.getText().toString());
                        pairing = false;
                        ui.post(() -> { toast("Emparelhado"); connect(host); });
                    } catch (Exception e) {
                        p.stop();
                        pairing = false;
                        ui.post(() -> { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); connect(host); });
                    }
                }).start())
                .setNegativeButton("Cancelar", (d, w) -> { p.stop(); pairing = false; status.setText("Sem emparelhar"); })
                .show();
    }

    private void discover() {
        stopDiscovery();
        status.setText("A procurar a TV…");
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        lock = wm.createMulticastLock("comando");
        lock.setReferenceCounted(false);
        lock.acquire();
        nsd = (NsdManager) getSystemService(NSD_SERVICE);
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceLost(NsdServiceInfo s) { }
            @Override public void onServiceFound(NsdServiceInfo s) {
                nsd.resolveService(s, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo s, int e) { }
                    @Override public void onServiceResolved(NsdServiceInfo s) {
                        ui.post(() -> {
                            if (!host().isEmpty()) return;
                            String host = s.getHost().getHostAddress();
                            prefs.edit().putString("host", host).putString("name", s.getServiceName()).apply();
                            stopDiscovery();
                            connect(host);
                        });
                    }
                });
            }
        };
        nsd.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
        // boxes without Google's service do not announce themselves: look for them on the network
        ui.postDelayed(() -> { if (host().isEmpty() && visible) scanNetwork(); }, 4000);
    }

    /** Tries every address on the Wi-Fi for Google's remote service (6466) and network ADB (5555). */
    private void scanNetwork() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        @SuppressWarnings("deprecation") int ip = wm.getConnectionInfo().getIpAddress();
        if (ip == 0) { status.setText("Liga o telemóvel ao Wi-Fi da TV"); return; }
        String base = (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + ".";
        String me = base + ((ip >> 24) & 0xFF);
        status.setText("A procurar na rede…");
        new Thread(() -> {
            java.util.List<String[]> found = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(48);
            for (int i = 1; i < 255; i++) {
                String h = base + i;
                if (h.equals(me)) continue;
                ex.execute(() -> {
                    boolean g = portOpen(h, 6466, 400), a = !g && portOpen(h, AdbClient.PORT, 400);
                    if (g || a) found.add(new String[]{h, g ? "Android TV / Google TV" : "Box Android (ADB)"});
                });
            }
            ex.shutdown();
            try { ex.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            ui.post(() -> {
                if (!host().isEmpty() || !visible) return;
                if (found.isEmpty()) { status.setText("TV não encontrada · toca aqui"); return; }
                if (found.size() == 1) { useTv(found.get(0)[0], found.get(0)[1]); return; }
                String[] items = new String[found.size()];
                for (int i = 0; i < items.length; i++) items[i] = found.get(i)[1] + "  ·  " + found.get(i)[0];
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Qual é a TV?")
                        .setItems(items, (d, w) -> useTv(found.get(w)[0], found.get(w)[1]))
                        .show();
            });
        }).start();
    }

    private void useTv(String host, String name) {
        prefs.edit().putString("host", host).putString("name", name.startsWith("Box") ? "Box Android" : "TV").apply();
        stopDiscovery();
        loadCaps();
        connect(host);
    }

    private void stopDiscovery() {
        try { if (nsd != null && discovery != null) nsd.stopServiceDiscovery(discovery); } catch (RuntimeException ignored) { }
        discovery = null;
        if (lock != null && lock.isHeld()) lock.release();
    }

    private void chooseTv() {
        EditText ip = new EditText(this);
        ip.setInputType(InputType.TYPE_CLASS_TEXT);
        ip.setText(host());
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("TV")
                .setMessage("Endereço IP da TV ou da box (na TV: Definições › Rede). Deixa vazio para procurar.\n\n"
                        + "Box Android que não aparece? Na box: Definições › Acerca › carrega 7 vezes em «Compilação»; "
                        + "depois em Opções de programador liga «Depuração USB» e «Depuração por rede» (ADB por Wi-Fi).")
                .setView(ip)
                .setPositiveButton("OK", (d, w) -> {
                    String h = ip.getText().toString().trim();
                    prefs.edit().putString("host", h).apply();
                    loadCaps();
                    if (h.isEmpty()) discover(); else connect(h);
                })
                .setNeutralButton(hasAtv || !hasAdb ? "Emparelhar de novo" : "Pedir autorização", (d, w) -> { if (hasAtv || !hasAdb) pair(); else connect(host()); })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ------------------------------------------------------------ apps (list served by the TV home screen)

    private File cache() { return new File(getFilesDir(), "apps.json"); }

    private void showCachedApps() {
        if (!hasLauncher) { loadApps(); return; }
        try (InputStream in = new FileInputStream(cache())) { fillApps(new String(readAll(in), StandardCharsets.UTF_8)); }
        catch (Exception ignored) { }
    }

    private void loadApps() {
        if (!hasLauncher) { loadAppsNoLauncher(); return; }
        http("/apps", b -> {
            if (b == null) return;
            String json = str(b);
            try (FileOutputStream o = new FileOutputStream(cache())) { o.write(b); } catch (Exception ignored) { }
            try { fillApps(json); } catch (Exception ignored) { }
        });
    }

    /** Common apps on Android TV; opened by package (only works if the app is installed on the TV). */
    private static final String[][] KNOWN = {
        {"com.netflix.ninja", "Netflix"}, {"com.google.android.youtube.tv", "YouTube"},
        {"com.amazon.amazonvideo.livingroom", "Prime Video"}, {"com.disney.disneyplus", "Disney+"},
        {"com.wbd.stream", "Max"}, {"com.spotify.tv.android", "Spotify"}, {"com.plexapp.android", "Plex"},
        {"org.xbmc.kodi", "Kodi"}, {"com.google.android.youtube.tvmusic", "YouTube Music"},
        {"com.apple.atve.androidtv.appletv", "Apple TV"}, {"com.antunes.player", "AntunesPlayer"},
        {"com.android.vending", "Play Store"}, {"com.google.android.videos", "Google TV"},
    };

    static String appName(String pkg) {
        for (String[] k : KNOWN) if (k[0].equals(pkg)) return k[1];
        String[] parts = pkg.split("\\.");
        String last = parts[parts.length - 1];
        if (parts.length > 1 && (last.equals("tv") || last.equals("android") || last.equals("app") || last.equals("ninja") || last.equals("livingroom")))
            last = parts[parts.length - 2];
        return last.isEmpty() ? pkg : Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }

    private void loadAppsNoLauncher() {
        AdbClient a = adb;
        if (a == null || !a.connected()) {
            if (hasAdb) return; // waits for ADB to connect
            String h = host();
            new Thread(() -> { // Comando TV on the TV knows every app it has; otherwise the common ones
                String json = null;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL("http://" + h + ":8767/apps").openConnection();
                    c.setConnectTimeout(1500);
                    c.setReadTimeout(8000);
                    if (c.getResponseCode() == 200) json = str(readAll(c.getInputStream()));
                } catch (Exception ignored) { }
                if (json == null) {
                    JSONArray arr = new JSONArray();
                    try { for (String[] k : KNOWN) arr.put(new JSONObject().put("pkg", k[0]).put("label", k[1])); } catch (Exception ignored) { }
                    json = arr.toString();
                }
                String j = json;
                ui.post(() -> { try { fillApps(j); } catch (Exception ignored) { } });
            }).start();
            return;
        }
        new Thread(() -> {
            try {
                java.util.List<String> list = a.apps();
                JSONArray arr = new JSONArray();
                for (String pkg : list) {
                    if (pkg.startsWith("com.android.") && !pkg.equals("com.android.vending")) continue;
                    arr.put(new JSONObject().put("pkg", pkg).put("label", appName(pkg)));
                }
                String json = arr.toString();
                ui.post(() -> { try { fillApps(json); } catch (Exception ignored) { } });
            } catch (Exception ignored) { }
        }).start();
    }

    /** Coloured square with the first letter, for apps without a picture. */
    private Bitmap letterTile(String label) {
        int w = 320, h = 180;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int[] colors = {0xFF1F6F8B, 0xFF6C3FB5, 0xFFB5523F, 0xFF2E8B57, 0xFF8B6B1F, 0xFF3F5BB5};
        c.drawColor(colors[Math.abs(label.hashCode()) % colors.length]);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(64);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        p.setTextAlign(Paint.Align.CENTER);
        String t = label.isEmpty() ? "?" : label.length() > 10 ? label.substring(0, 1).toUpperCase(PT) : label;
        if (p.measureText(t) > w - 30) p.setTextSize(64f * (w - 30) / p.measureText(t));
        c.drawText(t, w / 2f, h / 2f - (p.descent() + p.ascent()) / 2, p);
        return b;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private void fillApps(String json) throws Exception {
        JSONArray a = new JSONArray(json);
        appsJson = json;
        apps.removeAllViews();
        int cols = apps.getColumnCount();
        int w = (contentWidth() - dp(10) * (cols - 1)) / cols;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            String pkg = o.getString("pkg");
            LinearLayout cell = vlay();
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            byte[] jpg = Base64.decode(o.optString("img", ""), Base64.DEFAULT);
            Bitmap bm = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
            img.setImageBitmap(bm != null ? bm : letterTile(o.optString("label", pkg)));
            img.setBackground(glass(dp(12)));
            clip(img, dp(12));
            cell.addView(img, new LinearLayout.LayoutParams(w, w * 9 / 16));
            TextView t = text(11, MUT, null);
            t.setText(o.optString("label", pkg));
            t.setSingleLine(true);
            t.setPadding(dp(2), dp(5), 0, 0);
            cell.addView(t, new LinearLayout.LayoutParams(w, -2));
            press(cell, () -> { }, () -> { }, () -> {
                if (client == null || !client.connected()) { toast("A TV não está ligada"); return; }
                client.open(pkg);
            });
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
            gp.width = w;
            gp.setMargins(i % cols == 0 ? 0 : dp(10), 0, 0, dp(14));
            apps.addView(cell, gp);
        }
    }
}
