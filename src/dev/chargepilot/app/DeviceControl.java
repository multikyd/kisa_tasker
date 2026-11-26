package dev.chargepilot.app;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The Shizuku process exposes only the operations needed for the charging routine. */
public final class DeviceControl extends Binder {
    private static final String DESCRIPTOR = "dev.chargepilot.app.DeviceControl";
    private static final int CALL = IBinder.FIRST_CALL_TRANSACTION;
    enum Operation {
        PING, WAKE, NORMAL, SAVER, ONLINE, AIRPLANE, LOCATION_ON, LOCATION_OFF,
        HOTSPOT_ON, HOTSPOT_OFF, LAUNCH_TMAP, LAUNCH_NAVER, RUNNING_TMAP, RUNNING_NAVER,
        STOP_TMAP, STOP_NAVER
    }
    private final Context context;
    private final int appUid;
    private final int user;

    public DeviceControl(Context appContext) throws Exception {
        appUid = appContext.getApplicationInfo().uid;
        user = appUid / 100000;
        context = appContext.createPackageContext("com.android.shell", 0);
    }

    static String call(IBinder binder, Operation operation) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(operation.ordinal());
            if (!binder.transact(CALL, data, reply, 0)) throw new IllegalStateException("Shizuku 연결 끊김");
            reply.readException();
            String result = reply.readString();
            if (result == null || result.startsWith("!")) {
                throw new IllegalStateException(result == null ? "응답 없음" : result.substring(1));
            }
            return result;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws android.os.RemoteException {
        if (code != CALL && code != 16777114) return super.onTransact(code, data, reply, flags);
        int caller = Binder.getCallingUid();
        if (caller != appUid && (code == CALL || (caller != 0 && caller != 2000))) {
            throw new SecurityException("Unknown caller");
        }
        if (code == 16777114) {
            android.os.Process.killProcess(android.os.Process.myPid());
            return true;
        }
        data.enforceInterface(DESCRIPTOR);
        int operation = data.readInt();
        long identity = Binder.clearCallingIdentity();
        String result;
        try {
            result = execute(Operation.values()[operation]);
        } catch (Exception error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            result = "!" + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        reply.writeNoException();
        reply.writeString(result);
        return true;
    }

    private String execute(Operation operation) throws Exception {
        String target = operation.name().endsWith("NAVER") ? State.NAVER : State.TMAP;
        switch (operation) {
            case PING: return Integer.toString(android.os.Process.myUid());
            case WAKE: return command("input", "keyevent", "KEYCODE_WAKEUP");
            case NORMAL: return command("cmd", "power", "set-mode", "0");
            case SAVER: return command("cmd", "power", "set-mode", "1");
            case ONLINE: return command("cmd", "connectivity", "airplane-mode", "disable");
            case AIRPLANE: return command("cmd", "connectivity", "airplane-mode", "enable");
            case LOCATION_ON:
            case LOCATION_OFF:
                return command("cmd", "location", "set-location-enabled",
                        operation == Operation.LOCATION_ON ? "true" : "false", "--user", "" + user);
            case HOTSPOT_ON:
            case HOTSPOT_OFF:
                return hotspot(operation == Operation.HOTSPOT_ON);
            case LAUNCH_TMAP:
            case LAUNCH_NAVER:
                return command("am", "start", "--user", "" + user, "-a", "android.intent.action.MAIN",
                        "-c", "android.intent.category.LAUNCHER", "-p", target, "-f", "0x10200000");
            case RUNNING_TMAP:
            case RUNNING_NAVER:
                return commandResult("pidof", target).code == 0 ? "yes" : "no";
            case STOP_TMAP:
            case STOP_NAVER:
                return command("am", "force-stop", "--user", "" + user, target);
        }
        throw new IllegalArgumentException("Unknown operation");
    }

    private int hotspotState() throws Exception {
        WifiManager wifi = context.getSystemService(WifiManager.class);
        return (Integer) WifiManager.class.getMethod("getWifiApState").invoke(wifi);
    }

    private String hotspot(boolean enable) throws Exception {
        int state = hotspotState();
        if (enable && (state == 13 || state == 12)) return "already";
        if (!enable && (state == 11 || state == 10 || state == 14)) return "already";
        Class<?> managerClass = Class.forName("android.net.TetheringManager");
        Object manager = context.getSystemService("tethering");
        if (manager == null) throw new IllegalStateException("테더링 서비스를 찾을 수 없음");
        if (enable) {
            Class<?> callbackType = Class.forName("android.net.TetheringManager$StartTetheringCallback");
            CountDownLatch finished = new CountDownLatch(1);
            AtomicInteger result = new AtomicInteger(-1);
            Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(),
                    new Class<?>[]{callbackType}, (self, method, args) -> {
                        switch (method.getName()) {
                            case "onTetheringStarted": result.set(0); finished.countDown(); return null;
                            case "onTetheringFailed": result.set((Integer) args[0]); finished.countDown(); return null;
                            case "hashCode": return System.identityHashCode(self);
                            case "equals": return self == args[0];
                            case "toString": return "ChargePilot tether callback";
                            default: return null;
                        }
                    });
            Method start = managerClass.getMethod("startTethering", int.class, Executor.class, callbackType);
            start.invoke(manager, 0, (Executor) Runnable::run, callback);
            if (!finished.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("핫스팟 응답 시간 초과");
            if (result.get() != 0) throw new IllegalStateException("핫스팟 오류 " + result.get());
        } else {
            managerClass.getMethod("stopTethering", int.class).invoke(manager, 0);
        }
        long deadline = SystemClock.elapsedRealtime() + 4_000;
        do {
            state = hotspotState();
            if (state == (enable ? 13 : 11)) return "changed";
            if (state == 14) break;
            SystemClock.sleep(150);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new IllegalStateException("핫스팟 상태 " + state);
    }

    private static String command(String... args) throws Exception {
        CommandResult result = commandResult(args);
        if (result.code != 0 || result.output.contains("Error:")
                || result.output.contains("Exception") || result.output.contains("Permission denied")) {
            String message = result.output.trim();
            throw new IllegalStateException(message.isEmpty() ? "종료 코드 " + result.code
                    : message.split("\\R", 2)[0]);
        }
        return result.output.trim();
    }

    private static CommandResult commandResult(String... args) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList(args));
        command.set(0, "/system/bin/" + command.get(0));
        java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[1024];
                for (int size; (size = stream.read(buffer)) != -1;) {
                    if (output.size() < 4096) output.write(buffer, 0, size);
                }
            } catch (java.io.IOException ignored) {}
        }, "charge-command-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(4, TimeUnit.SECONDS)) throw new IllegalStateException("명령 응답 시간 초과");
            reader.join(400);
            return new CommandResult(process.exitValue(), new String(output.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            process.destroy();
        }
    }

    private static final class CommandResult {
        final int code;
        final String output;
        CommandResult(int code, String output) { this.code = code; this.output = output; }
    }
}
