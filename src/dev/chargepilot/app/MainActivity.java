package dev.chargepilot.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int BG = 0xfff1f7f5;
    private static final int DARK = 0xff142d29;
    private static final int MUTED = 0xff425f58;
    private static final int ACCENT = 0xff006e61;
    private SharedPreferences prefs;
    private TextView headline, shizuku, routes, log;
    private Button toggle, shizukuAction, updateAction;
    private AppUpdater updater;
    private final SharedPreferences.OnSharedPreferenceChangeListener observer = (p, k) -> render();
    private final Shizuku.OnBinderReceivedListener arrived = this::render;
    private final Shizuku.OnBinderDeadListener gone = this::render;
    private final Shizuku.OnRequestPermissionResultListener permission = (request, result) -> render();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = State.prefs(this);
        updater = new AppUpdater(this);
        View page = buildPage();
        setContentView(page);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        prefs.registerOnSharedPreferenceChangeListener(observer);
        Shizuku.addBinderReceivedListener(arrived);
        Shizuku.addBinderDeadListener(gone);
        Shizuku.addRequestPermissionResultListener(permission);
        if (prefs.getBoolean(State.ENABLED, false) && !MonitorService.running) MonitorService.start(this);
        if (savedInstanceState == null) updater.check(false);
    }

    private View buildPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(22), dp(22), dp(28));
        scroll.addView(page);

        page.addView(text("차량 충전 자동화", 30, DARK, true));
        page.addView(text("유선 전원으로 내비와 운전 중 설정을 제어합니다.", 14, MUTED, false));

        LinearLayout status = card(page);
        headline = text("", 23, Color.WHITE, true);
        status.setBackground(shape(ACCENT, 20));
        status.addView(headline);
        status.addView(text("전원이 끊겨도 10초 안에 돌아오면 주행을 유지합니다.", 14, 0xffe2f8f1, false));
        toggle = button("감시 시작", false);
        toggle.setOnClickListener(v -> toggle());
        status.addView(toggle);

        LinearLayout timing = card(page);
        timing.addView(text("충전 자동화 설정", 20, DARK, true));
        int seconds = prefs.getInt(State.DELAY, 3);
        TextView delay = text("연결 후 대기  " + seconds + "초", 16, DARK, false);
        timing.addView(delay);
        SeekBar slider = new SeekBar(this);
        slider.setMin(1);
        slider.setMax(30);
        slider.setProgress(seconds);
        slider.setContentDescription("연결 후 대기 시간");
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                delay.setText("연결 후 대기  " + value + "초");
                if (user) prefs.edit().putInt(State.DELAY, value).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        timing.addView(slider);
        timing.addView(text("전원 차단 후 종료 유예  10초", 14, MUTED, false));
        addSwitch(timing, "주행 중 Wi-Fi 핫스팟", State.HOTSPOT);
        addSwitch(timing, "종료 시 비행기 모드", State.AIRPLANE);
        addSwitch(timing, "앱 종료 전 경로 취소 시도", State.CANCEL);

        LinearLayout permissions = card(page);
        permissions.addView(text("권한과 연결", 20, DARK, true));
        shizuku = text("", 15, MUTED, false);
        permissions.addView(shizuku);
        shizukuAction = button("Shizuku 연결", true);
        shizukuAction.setOnClickListener(view -> requestShizuku());
        permissions.addView(shizukuAction);
        routes = text("", 15, MUTED, false);
        permissions.addView(routes);
        Button access = button("접근성 설정 열기", true);
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        permissions.addView(access);
        permissions.addView(text("접근성을 켜면 종료 시 두 내비 앱의 안내 종료 버튼을 확인합니다. 잠금은 직접 해제해야 합니다.", 13, MUTED, false));

        LinearLayout recent = card(page);
        recent.addView(text("최근 동작", 20, DARK, true));
        log = text("", 13, MUTED, false);
        log.setLineSpacing(dp(3), 1);
        recent.addView(log);

        LinearLayout update = card(page);
        update.addView(text("앱 업데이트", 20, DARK, true));
        update.addView(text("설치된 버전 " + updater.versionName(), 14, MUTED, false));
        updateAction = button("업데이트 확인", true);
        updateAction.setOnClickListener(v -> updater.check(true));
        update.addView(updateAction);
        return scroll;
    }

    private void addSwitch(LinearLayout layout, String title, String key) {
        Switch item = new Switch(this);
        item.setText(title);
        item.setTextSize(15);
        item.setTextColor(DARK);
        item.setMinHeight(dp(46));
        item.setChecked(prefs.getBoolean(key, true));
        item.setOnCheckedChangeListener((button, on) -> prefs.edit().putBoolean(key, on).apply());
        layout.addView(item);
    }

    private void render() {
        if (headline == null) return;
        boolean armed = prefs.getBoolean(State.ENABLED, false);
        headline.setText(armed ? (!MonitorService.running ? "감시 시작 대기"
                : prefs.getString(State.STATUS, "전원 상태 확인 중")) : "감시 일시정지");
        toggle.setText(armed ? "감시 일시정지" : "감시 시작");
        boolean available = Shizuku.pingBinder();
        boolean allowed = available && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        shizuku.setText(allowed ? "Shizuku 권한 허용됨"
                : available ? "Shizuku 권한이 필요합니다" : "Shizuku 실행 대기");
        shizukuAction.setText(available && !allowed ? "Shizuku 권한 요청" : "Shizuku 열기");
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        routes.setText(RouteAccess.instance != null || (enabled != null && enabled.contains(getPackageName()))
                ? "경로 취소 접근성 켜짐" : "경로 취소 접근성 꺼짐");
        String names = prefs.getString(State.LOG, "");
        log.setText(names.isEmpty() ? "기록된 동작이 없습니다." : names);
    }

    private void toggle() {
        if (prefs.getBoolean(State.ENABLED, false)) {
            prefs.edit().putBoolean(State.ENABLED, false).remove(State.ACTIVE).remove(State.CUTOFF)
                    .putString(State.STATUS, "일시정지").apply();
            stopService(new Intent(this, MonitorService.class));
            State.log(this, "사용자가 감시를 일시정지함");
            render();
            return;
        }
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            requestShizuku();
            return;
        }
        prefs.edit().putBoolean(State.ENABLED, true).putString(State.STATUS, "전원 상태 확인 중").apply();
        MonitorService.start(this);
        render();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    private void requestShizuku() {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            try { Shizuku.requestPermission(1); }
            catch (RuntimeException error) { message("Shizuku에서 앱 권한을 확인하세요."); }
            return;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (launch != null) {
            startActivity(launch);
            return;
        }
        new AlertDialog.Builder(this).setTitle("Shizuku 설치")
                .setMessage("비행기 모드, 위치, 절전 모드와 핫스팟을 자동 전환하려면 Shizuku를 설치하고 실행하세요.")
                .setPositiveButton("설치 안내", (dialog, which) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/"))); }
                    catch (RuntimeException error) { message("shizuku.rikka.app에서 설치할 수 있습니다."); }
                }).setNegativeButton("닫기", null).show();
    }

    void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    void updateBusy(boolean busy) {
        if (updateAction == null) return;
        updateAction.setEnabled(!busy);
        updateAction.setText(busy ? "업데이트 처리 중" : "업데이트 확인");
    }

    @Override protected void onResume() {
        super.onResume();
        render();
        updater.onResume();
    }

    @Override protected void onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(observer);
        Shizuku.removeBinderReceivedListener(arrived);
        Shizuku.removeBinderDeadListener(gone);
        Shizuku.removeRequestPermissionResultListener(permission);
        super.onDestroy();
    }

    @Override protected void onPause() {
        updater.onPause();
        super.onPause();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == AppUpdater.INSTALL_REQUEST) updater.installReturned();
    }

    private int dp(int pixels) { return Math.round(pixels * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color, boolean heading) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setPadding(0, 0, 0, dp(8));
        if (heading) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }
    private LinearLayout card(LinearLayout page) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(17), dp(17), dp(17), dp(16));
        card.setBackground(shape(Color.WHITE, 20));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
        page.addView(card, params);
        return card;
    }
    private Button button(String title, boolean secondary) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextColor(ACCENT);
        button.setBackgroundTintList(ColorStateList.valueOf(secondary ? 0xffe4f3ef : Color.WHITE));
        return button;
    }
}
