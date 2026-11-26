package dev.chargepilot.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (State.prefs(context).getBoolean(State.ENABLED, false)) {
            try {
                MonitorService.start(context);
            } catch (RuntimeException error) {
                State.log(context, "감시 시작 실패 · 앱을 열어 다시 시작하세요.");
            }
        }
    }
}
