package dev.chargepilot.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

final class State {
    static final String ENABLED = "enabled";
    static final String ACTIVE = "active";
    static final String CUTOFF = "cutoff";
    static final String BOOT = "boot";
    static final String DELAY = "delay";
    static final String START_NAV = "startNav";
    static final String HOTSPOT = "hotspot";
    static final String OWNED_HOTSPOT = "ownedHotspot";
    static final String CANCEL = "cancel";
    static final String AIRPLANE = "airplane";
    static final String STATUS = "status";
    static final String LOG = "log";
    static final String TMAP = "com.skt.tmap.ku";
    static final String NAVER = "com.nhn.android.nmap";

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("vehicle", Context.MODE_PRIVATE);
    }

    static String startNav(SharedPreferences prefs) {
        return NAVER.equals(prefs.getString(START_NAV, TMAP)) ? NAVER : TMAP;
    }

    static String navName(String pkg) {
        return NAVER.equals(pkg) ? "네이버지도" : "티맵";
    }

    static synchronized void log(Context context, String line) {
        SharedPreferences prefs = prefs(context);
        String timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        StringBuilder text = new StringBuilder(timestamp).append("  ").append(line);
        String old = prefs.getString(LOG, "");
        String[] lines = old.split("\n", 31);
        for (int i = 0; !old.isEmpty() && i < Math.min(29, lines.length); i++) {
            text.append('\n').append(lines[i]);
        }
        prefs.edit().putString(LOG, text.toString()).apply();
    }

    private State() {}
}
