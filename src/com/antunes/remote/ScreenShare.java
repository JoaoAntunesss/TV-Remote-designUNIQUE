package com.antunes.remote;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Opened on the TV or box (it is the TV home-screen entry of Comando TV). Asks Android once for
 * permission to share the screen, then hands over to ScreenService and gets out of the way.
 * This is how the phone gets live video on boxes where the operator locked developer options.
 */
public class ScreenShare extends Activity {
    private static final int ASK = 1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(22);
        t.setTextColor(Color.WHITE);
        t.setBackgroundColor(0xFF101418);
        t.setPadding(80, 80, 80, 80);
        setContentView(t);
        if (ScreenService.running) {
            t.setText("O ecrã já está a ser mostrado no telemóvel.\n\nPara parar, carrega em OK.");
            t.setFocusable(true);
            t.setOnClickListener(v -> { stopService(new Intent(this, ScreenService.class)); finish(); });
            return;
        }
        t.setText("Comando TV\n\nPara veres este ecrã no telemóvel, escolhe «Começar agora».");
        MediaProjectionManager m = getSystemService(MediaProjectionManager.class);
        try {
            startActivityForResult(m.createScreenCaptureIntent(), ASK);
        } catch (Exception e) {
            t.setText("Esta box não deixa partilhar o ecrã.\n\nO Comando TV continua a funcionar como comando.");
        }
    }

    @Override protected void onActivityResult(int req, int code, Intent data) {
        if (req != ASK) return;
        if (code == RESULT_OK && data != null) {
            Intent i = new Intent(this, ScreenService.class).putExtra("code", code).putExtra("data", data);
            startForegroundService(i);
            Toast.makeText(this, "Já podes ver este ecrã no telemóvel", Toast.LENGTH_LONG).show();
            moveTaskToBack(true);
        }
        finish();
    }
}
