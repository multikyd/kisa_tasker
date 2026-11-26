package dev.chargepilot.app;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import rikka.shizuku.Shizuku;
import static dev.chargepilot.app.DeviceControl.Operation.*;

public final class MonitorService extends Service {
    static final String PAUSE = "dev.chargepilot.app.PAUSE";
    static volatile boolean running;
    private static final String CHANNEL = "charger_monitor";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable clock = () -> tick();
    private final Runnable reconnect = () -> {
        if (this.bridge != null && !this.bridge.isBinderAlive()) {
            bridge = null;
            binding = false;
        }
        connectShizuku();
    };
    private final Runnable shizukuChanged = this::connectShizuku;
    private final Shizuku.OnBinderReceivedListener binderReceived = shizukuChanged::run;
    private final Shizuku.OnBinderDeadListener binderDead = () -> {
        bridge = null;
        binding = false;
        tick();
    };
    private final Shizuku.OnRequestPermissionResultListener permissionReply = (request, result) -> connectShizuku();
    private SharedPreferences prefs;
    private PowerCycle cycle;
    private PowerManager power;
    private PowerManager.WakeLock wake;
    private Shizuku.UserServiceArgs args;
    private volatile IBinder bridge;
    private boolean binding;
    private volatile int generation;
    private boolean waitingUnlock;
    private boolean receivedBattery;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            if (!running) return;
            bridge = service;
            State.log(MonitorService.this, "Shizuku 제어 연결됨");
            tick();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bridge = null;
            binding = false;
            main.postDelayed(shizukuChanged, 2_000);
            tick();
        }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent event) {
            if (Intent.ACTION_USER_PRESENT.equals(event.getAction())) {
                if (waitingUnlock && cycle != null && cycle.isActive() && cycle.isWired()) {
                    waitingUnlock = false;
                    int token = generation;
                    worker.execute(() -> {
                        if (current(token)) {
                            launchSelectedNav(token);
                            main.post(MonitorService.this::tick);
                        }
                    });
                }
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(event.getAction())) {
                powerEvent(event);
            } else {
                tick();
            }
        }
    };

    static void start(Context context) {
        context.startForegroundService(new Intent(context, MonitorService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        prefs = State.prefs(this);
        power = getSystemService(PowerManager.class);
        wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kisa_tasker:charge_transition");
        wake.setReferenceCounted(false);
        args = new Shizuku.UserServiceArgs(new ComponentName(this, DeviceControl.class))
                .daemon(false).processNameSuffix("device_control").version(4);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                "충전 연결 감시", NotificationManager.IMPORTANCE_LOW));
        running = true;
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        Intent battery = Build.VERSION.SDK_INT >= 33
                ? registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                : registerReceiver(receiver, filter);
        if (battery != null) powerEvent(battery);
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionReply);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        startForeground(14, notification(prefs.getString(State.STATUS, "전원 상태 확인 중")));
        if (intent != null && PAUSE.equals(intent.getAction())) {
            prefs.edit().putBoolean(State.ENABLED, false).remove(State.ACTIVE)
                    .remove(State.CUTOFF).putString(State.STATUS, "일시정지").apply();
            State.log(this, "사용자가 감시를 일시정지함");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!prefs.getBoolean(State.ENABLED, false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        connectShizuku();
        tick();
        return START_STICKY;
    }

    private boolean wired(Intent battery) {
        int bits = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        return (bits & (BatteryManager.BATTERY_PLUGGED_AC | BatteryManager.BATTERY_PLUGGED_USB)) != 0;
    }

    private long delay() { return prefs.getInt(State.DELAY, 3) * 1000L; }

    private void powerEvent(Intent battery) {
        long now = SystemClock.elapsedRealtime();
        boolean supplied = wired(battery);
        if (!receivedBattery) {
            int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            boolean sameBoot = boot >= 0 && boot == prefs.getInt(State.BOOT, -2);
            boolean wasActive = sameBoot && prefs.getBoolean(State.ACTIVE, false);
            cycle = new PowerCycle(wasActive, supplied, now, delay(), sameBoot
                    ? prefs.getLong(State.CUTOFF, PowerCycle.NONE) : PowerCycle.NONE);
            receivedBattery = true;
            prefs.edit().putInt(State.BOOT, boot).putBoolean(State.ACTIVE, wasActive)
                    .putLong(State.CUTOFF, wasActive && !supplied && cycle.deadline() >= 0
                            ? cycle.deadline() - PowerCycle.DISCONNECT_MS : PowerCycle.NONE).apply();
        } else {
            observe(supplied, now);
        }
        tick();
    }

    private void observe(boolean supplied, long now) {
        boolean before = cycle.isWired();
        cycle.update(supplied, now, delay());
        if (before == supplied) return;
        prefs.edit().putLong(State.CUTOFF,
                !supplied && cycle.isActive() ? now : PowerCycle.NONE).apply();
        if (supplied && cycle.isActive()) {
            State.log(this, "10초 이내 전원 복구 · 주행 유지");
        } else {
            State.log(this, supplied ? "유선 전원 연결" : "유선 전원 차단");
        }
    }

    private void connectShizuku() {
        if (!running) return;
        if (binding || bridge != null) {
            tick();
            return;
        }
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                binding = true;
                Shizuku.bindUserService(args, connection);
                main.postDelayed(() -> {
                    if (bridge == null && running) {
                        binding = false;
                        tick();
                    }
                }, 7_000);
            }
        } catch (RuntimeException error) {
            binding = false;
            State.log(this, "Shizuku 연결 실패 · " + error.getClass().getSimpleName());
        }
        tick();
    }

    private void tick() {
        main.removeCallbacks(clock);
        if (!running || cycle == null || !prefs.getBoolean(State.ENABLED, false)) return;
        long now = SystemClock.elapsedRealtime();
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) observe(wired(battery), now);
        boolean connected = bridge != null && bridge.isBinderAlive();
        PowerCycle.Action action = cycle.ready(now, connected);
        if (action != PowerCycle.Action.NONE) {
            waitingUnlock = false;
            int token = ++generation;
            prefs.edit().putBoolean(State.ACTIVE, cycle.isActive()).remove(State.CUTOFF).apply();
            if (wake.isHeld()) wake.release();
            keepAwake(50_000);
            worker.execute(() -> {
                if (action == PowerCycle.Action.START) beginDrive(token);
                else endDrive(token);
                main.post(this::tick);
            });
        }
        if (!connected) {
            status("Shizuku 연결 또는 권한 대기");
            main.removeCallbacks(reconnect);
            main.postDelayed(reconnect, 3_000);
        } else if (cycle.deadline() >= 0) {
            long remaining = Math.max(1, cycle.deadline() - now);
            keepAwake(45_000);
            status((cycle.isWired() ? "주행 준비" : "종료 유예") + " · " + (remaining + 999) / 1000 + "초");
            main.postDelayed(clock, Math.min(1_000, remaining));
        } else if (waitingUnlock) {
            status("잠금 해제 후 " + State.navName(State.startNav(prefs)) + " 실행");
        } else {
            status(cycle.isActive() ? "주행 모드" : "유선 전원 연결 대기");
        }
    }

    private void keepAwake(long time) {
        if (!wake.isHeld()) wake.acquire(time);
    }

    private boolean current(int token) {
        return running && token == generation && prefs.getBoolean(State.ENABLED, false);
    }

    private String operate(int token, DeviceControl.Operation action, String label) {
        if (!current(token)) return null;
        IBinder handle = bridge;
        try {
            if (handle == null) throw new IllegalStateException("Shizuku 연결 없음");
            String result = DeviceControl.call(handle, action);
            if (label != null) State.log(this, label + (result.equals("already") ? " · 이미 설정됨" : " 완료"));
            return result;
        } catch (Exception error) {
            if (label != null) {
                String message = String.valueOf(error.getMessage());
                State.log(this, label + " 실패 · " + message.substring(0, Math.min(140, message.length())));
            }
            return null;
        }
    }

    private void checkSetting(int token, DeviceControl.Operation operation, String label, BooleanSupplier matches) {
        if (operate(token, operation, null) == null || !current(token)) {
            if (current(token)) State.log(this, label + " 실패 · 기기 설정 제어 불가");
            return;
        }
        long end = SystemClock.elapsedRealtime() + 1_500;
        do {
            if (!current(token)) return;
            if (matches.getAsBoolean()) {
                State.log(this, label + " 완료");
                return;
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < end);
        State.log(this, label + " 실패 · 변경된 상태를 확인하지 못함");
    }

    private void beginDrive(int token) {
        if (!current(token)) return;
        State.log(this, "주행 준비 시작");
        checkSetting(token, WAKE, "화면 켜기", power::isInteractive);
        checkSetting(token, NORMAL, "일반 전원 모드", () -> !power.isPowerSaveMode());
        checkSetting(token, ONLINE, "비행기 모드 끄기", () -> !airplane());
        checkSetting(token, LOCATION_ON, "위치 켜기", this::location);
        if (prefs.getBoolean(State.HOTSPOT, true)) {
            String result = operate(token, HOTSPOT_ON, "Wi-Fi 핫스팟 켜기");
            if ("changed".equals(result)) prefs.edit().putBoolean(State.OWNED_HOTSPOT, true).apply();
        }
        if (!current(token)) return;
        if (!getSystemService(KeyguardManager.class).isDeviceLocked()) {
            launchSelectedNav(token);
        } else {
            main.post(() -> {
                if (!current(token)) return;
                waitingUnlock = true;
                if (!getSystemService(KeyguardManager.class).isDeviceLocked()) {
                    receiver.onReceive(this, new Intent(Intent.ACTION_USER_PRESENT));
                }
                tick();
            });
        }
    }

    private void launchSelectedNav(int token) {
        String pkg = State.startNav(prefs);
        operate(token, State.NAVER.equals(pkg) ? LAUNCH_NAVER : LAUNCH_TMAP, State.navName(pkg) + " 실행");
    }

    private void endDrive(int token) {
        if (!current(token)) return;
        State.log(this, "전원 차단 10초 경과 · 종료 시작");
        finishNav(token, State.NAVER, "네이버지도", NAV_ACTIVE_NAVER, LAUNCH_NAVER, STOP_NAVER);
        finishNav(token, State.TMAP, "티맵", NAV_ACTIVE_TMAP, LAUNCH_TMAP, STOP_TMAP);
        if (prefs.getBoolean(State.OWNED_HOTSPOT, false)
                && operate(token, HOTSPOT_OFF, "Wi-Fi 핫스팟 끄기") != null) {
            prefs.edit().remove(State.OWNED_HOTSPOT).apply();
        }
        if (prefs.getBoolean(State.AIRPLANE, true)) {
            checkSetting(token, AIRPLANE, "비행기 모드 켜기", this::airplane);
        }
        checkSetting(token, LOCATION_OFF, "위치 끄기", () -> !location());
        checkSetting(token, SAVER, "절전 모드", power::isPowerSaveMode);
        ensureStopped(token, State.NAVER, "네이버지도", NAV_ACTIVE_NAVER, STOP_NAVER);
        ensureStopped(token, State.TMAP, "티맵", NAV_ACTIVE_TMAP, STOP_TMAP);
    }

    private void finishNav(int token, String pkg, String label, DeviceControl.Operation runningOp,
                           DeviceControl.Operation launchOp, DeviceControl.Operation stopOp) {
        if (!current(token) || getPackageManager().getLaunchIntentForPackage(pkg) == null) return;
        String process = operate(token, runningOp, null);
        if ("no".equals(process)) return;
        if (prefs.getBoolean(State.CANCEL, true)) {
            boolean ended = false;
            if (RouteAccess.instance != null && !getSystemService(KeyguardManager.class).isDeviceLocked()) {
                if (!power.isInteractive()) operate(token, WAKE, null);
                if (operate(token, launchOp, null) != null) {
                    try {
                        ended = RouteAccess.end(pkg, () -> current(token));
                    } catch (Exception ignored) {}
                }
            }
            if (current(token)) State.log(this, label + (ended ? " · 안내 종료 버튼 처리"
                    : " · 안내 종료 버튼 확인 안 됨"));
        }
        operate(token, stopOp, label + " 종료");
    }

    private void ensureStopped(int token, String pkg, String label, DeviceControl.Operation runningOp,
                               DeviceControl.Operation stopOp) {
        if (!current(token) || getPackageManager().getLaunchIntentForPackage(pkg) == null) return;
        for (int attempt = 0; attempt < 3 && current(token); attempt++) {
            SystemClock.sleep(400);
            String runningApp = operate(token, runningOp, null);
            if ("no".equals(runningApp)) {
                State.log(this, label + " · 종료 상태 확인");
                return;
            }
            if (!"yes".equals(runningApp) || operate(token, stopOp, label + " 재종료") == null) break;
        }
        if (current(token)) {
            boolean stopped = "no".equals(operate(token, runningOp, null));
            State.log(this, label + (stopped ? " · 종료 상태 확인" : " · 종료 상태 확인 실패"));
        }
    }

    private boolean location() { return getSystemService(LocationManager.class).isLocationEnabled(); }
    private boolean airplane() {
        return Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
    }

    private void status(String text) {
        if (text.equals(prefs.getString(State.STATUS, ""))) return;
        prefs.edit().putString(State.STATUS, text).apply();
        getSystemService(NotificationManager.class).notify(14, notification(text));
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getService(this, 2,
                new Intent(this, MonitorService.class).setAction(PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_status)
                .setContentTitle(getString(R.string.app_name)).setContentText(text).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null, "일시정지", pause).build()).build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        running = false;
        generation++;
        main.removeCallbacksAndMessages(null);
        unregisterReceiver(receiver);
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permissionReply);
        if (binding) {
            try { Shizuku.unbindUserService(args, connection, false); } catch (RuntimeException ignored) {}
        }
        worker.shutdownNow();
        if (wake.isHeld()) wake.release();
        super.onDestroy();
    }
}
