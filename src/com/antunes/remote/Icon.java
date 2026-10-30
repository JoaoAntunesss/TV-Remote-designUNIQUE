package com.antunes.remote;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Line icons on a 24x24 grid, the same set and style as the TV home screen. */
final class Icon extends Drawable {
    static final int BACK = 0, HOME = 1, PLAY_PAUSE = 2, MENU = 3, VOL_DOWN = 4, VOL_UP = 5, MUTE = 6, POWER = 7,
            GEAR = 8, SCREEN = 9, UP = 10, DOWN = 11, LEFT = 12, RIGHT = 13, SEARCH = 14, FIRE = 15, INPUT = 16,
            IMAGE = 17, CLOCK = 18, KEYBOARD = 19, REW = 20, FWD = 21, SPLIT = 22, APPS = 23;
    private final int type;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    Icon(int type, int color) {
        this.type = type;
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    void setColor(int c) { p.setColor(c); invalidateSelf(); }

    @Override public int getIntrinsicWidth() { return -1; }
    @Override public int getIntrinsicHeight() { return -1; }

    private void fill() { p.setStyle(Paint.Style.FILL); }
    private void stroke(float w) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w); }
    private void poly(float... xy) {
        path.reset();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
    }
    private void rr(Canvas c, float l, float t, float rt, float b, float rad) { r.set(l, t, rt, b); c.drawRoundRect(r, rad, rad, p); }
    private void arc(Canvas c, float cx, float cy, float rad, float start, float sweep) {
        r.set(cx - rad, cy - rad, cx + rad, cy + rad);
        c.drawArc(r, start, sweep, false, p);
    }
    private void speaker(Canvas c) {
        fill();
        poly(3, 9, 7, 9, 12, 4.5f, 12, 19.5f, 7, 15, 3, 15);
        path.close();
        c.drawPath(path, p);
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        c.save();
        c.translate(b.exactCenterX() - s / 2f, b.exactCenterY() - s / 2f);
        c.scale(s / 24f, s / 24f);
        switch (type) {
            case BACK: stroke(2.4f); poly(14.5f, 5, 8, 12, 14.5f, 19); c.drawPath(path, p); break;
            case HOME: stroke(2f); poly(4, 11, 12, 4, 20, 11); c.drawPath(path, p); poly(6, 9.5f, 6, 20, 18, 20, 18, 9.5f); c.drawPath(path, p); break;
            case PLAY_PAUSE:
                fill(); poly(3, 6, 11, 12, 3, 18); path.close(); c.drawPath(path, p);
                rr(c, 14, 6, 16.5f, 18, 1); rr(c, 19, 6, 21.5f, 18, 1); break;
            case MENU: stroke(2.2f); c.drawLine(5, 7, 19, 7, p); c.drawLine(5, 12, 19, 12, p); c.drawLine(5, 17, 19, 17, p); break;
            case VOL_DOWN: speaker(c); stroke(2.2f); c.drawLine(15, 12, 21, 12, p); break;
            case VOL_UP: speaker(c); stroke(2.2f); c.drawLine(15, 12, 21, 12, p); c.drawLine(18, 9, 18, 15, p); break;
            case MUTE: speaker(c); stroke(2.2f); c.drawLine(15, 9, 21, 15, p); c.drawLine(21, 9, 15, 15, p); break;
            case POWER: stroke(2.2f); arc(c, 12, 13, 7.5f, -60, 300); c.drawLine(12, 3.5f, 12, 11, p); break;
            case GEAR:
                stroke(2f); c.drawCircle(12, 12, 3, p); c.drawCircle(12, 12, 6.8f, p);
                stroke(3.2f);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    c.drawLine(12 + (float) Math.cos(a) * 7.6f, 12 + (float) Math.sin(a) * 7.6f,
                            12 + (float) Math.cos(a) * 9.6f, 12 + (float) Math.sin(a) * 9.6f, p);
                }
                break;
            case SCREEN: stroke(2f); rr(c, 3, 5, 21, 16, 2); c.drawLine(8, 20, 16, 20, p); break;
            case UP: stroke(2.4f); poly(6, 15, 12, 9, 18, 15); c.drawPath(path, p); break;
            case DOWN: stroke(2.4f); poly(6, 9, 12, 15, 18, 9); c.drawPath(path, p); break;
            case LEFT: stroke(2.4f); poly(15, 6, 9, 12, 15, 18); c.drawPath(path, p); break;
            case RIGHT: stroke(2.4f); poly(9, 6, 15, 12, 9, 18); c.drawPath(path, p); break;
            case SEARCH: stroke(2.2f); c.drawCircle(10.5f, 10.5f, 6, p); c.drawLine(15, 15, 20, 20, p); break;
            case FIRE:
                stroke(2f);
                path.reset();
                path.moveTo(12, 2.5f);
                path.cubicTo(14.5f, 7, 18.5f, 9.5f, 18.5f, 14.5f);
                path.cubicTo(18.5f, 18.5f, 15.5f, 21.5f, 12, 21.5f);
                path.cubicTo(8.5f, 21.5f, 5.5f, 18.5f, 5.5f, 15);
                path.cubicTo(5.5f, 12, 7.5f, 10.5f, 8.5f, 8.5f);
                path.cubicTo(9, 10.5f, 10, 11.5f, 11.2f, 12);
                path.cubicTo(10.6f, 8.5f, 10.8f, 5.5f, 12, 2.5f);
                path.close();
                c.drawPath(path, p);
                break;
            case INPUT: stroke(2f); rr(c, 3, 5, 21, 19, 2.5f); c.drawLine(8, 12, 16, 12, p); poly(13, 8.5f, 16.5f, 12, 13, 15.5f); c.drawPath(path, p); break;
            case IMAGE:
                stroke(2f); rr(c, 3, 5, 21, 19, 2.5f); poly(5.5f, 16.5f, 10, 11.5f, 13.5f, 15, 15.5f, 13, 18.5f, 16.5f); c.drawPath(path, p);
                fill(); c.drawCircle(16, 9, 1.6f, p); break;
            case CLOCK: stroke(2f); c.drawCircle(12, 12, 8.5f, p); c.drawLine(12, 7, 12, 12, p); c.drawLine(12, 12, 15.5f, 14, p); break;
            case KEYBOARD:
                stroke(2f); rr(c, 2.5f, 6, 21.5f, 18, 2.5f);
                fill();
                for (int i = 0; i < 5; i++) { c.drawCircle(6 + i * 3, 10, 0.9f, p); }
                rr(c, 8, 13.5f, 16, 15, 0.7f); break;
            case REW: fill(); poly(12, 6, 3, 12, 12, 18); path.close(); c.drawPath(path, p); poly(21, 6, 12, 12, 21, 18); path.close(); c.drawPath(path, p); break;
            case FWD: fill(); poly(12, 6, 21, 12, 12, 18); path.close(); c.drawPath(path, p); poly(3, 6, 12, 12, 3, 18); path.close(); c.drawPath(path, p); break;
            case SPLIT: stroke(2f); rr(c, 2.5f, 5, 21.5f, 19, 2.5f); c.drawLine(12, 5, 12, 19, p); break;
            case APPS:
                fill();
                for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) rr(c, 4 + i * 6, 4 + j * 6, 8 + i * 6, 8 + j * 6, 1.2f);
                break;
        }
        c.restore();
    }

    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
