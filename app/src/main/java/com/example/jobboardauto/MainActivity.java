package com.example.jobboardauto;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String PREFS = "jobboard";
    private SharedPreferences prefs;
    private EditText threshold;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 40, 32, 32);
        root.setGravity(Gravity.TOP);

        TextView title = new TextView(this);
        title.setText("Job Board Auto");
        title.setTextSize(28);
        title.setPadding(0,0,0,24);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("Bu uygulama erişilebilirlik hizmetiyle ekrandaki £ tutarlarını okur, eşik aşılırsa kabul düğmesine basar ve sesli uyarı verir.");
        info.setTextSize(16);
        root.addView(info, lp());

        TextView lab = new TextView(this);
        lab.setText("Minimum ücret (£)");
        lab.setTextSize(16);
        lab.setPadding(0,28,0,8);
        root.addView(lab);

        threshold = new EditText(this);
        threshold.setInputType(8194); // decimal
        threshold.setText(String.valueOf(prefs.getFloat("threshold", 50f)));
        threshold.setSelectAllOnFocus(true);
        root.addView(threshold, lp());

        Button settings = new Button(this);
        settings.setText("1. Erişilebilirlik ayarlarını aç");
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(settings, lp());

        Button save = new Button(this);
        save.setText("2. Eşiği kaydet");
        save.setOnClickListener(v -> saveThreshold());
        root.addView(save, lp());

        Button start = new Button(this);
        start.setText("BAŞLAT");
        start.setOnClickListener(v -> { saveThreshold(); JobBoardAccessibilityService.setArmed(true); toast("Otomasyon aktif"); });
        root.addView(start, lp());

        Button stop = new Button(this);
        stop.setText("DURDUR");
        stop.setOnClickListener(v -> { JobBoardAccessibilityService.setArmed(false); toast("Otomasyon durduruldu"); });
        root.addView(stop, lp());

        TextView note = new TextView(this);
        note.setText("Not: Hedef uygulamada otomasyon açıkken bu ekrandan çıkıp iş panosunu aç. Durdur düğmesi her zaman kullanılabilir; servis ayarlarından erişilebilirliği de kapatabilirsin.");
        note.setPadding(0,28,0,0);
        note.setTextSize(14);
        root.addView(note, lp());
        setContentView(root);
    }

    private LinearLayout.LayoutParams lp() { return new LinearLayout.LayoutParams(-1, -2); }

    private void saveThreshold() {
        try {
            float f = Float.parseFloat(threshold.getText().toString().replace(',', '.'));
            if (f < 0) throw new NumberFormatException();
            prefs.edit().putFloat("threshold", f).apply();
            toast("Eşik kaydedildi: £" + f);
        } catch (Exception e) { toast("Geçerli bir sayı gir"); }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
