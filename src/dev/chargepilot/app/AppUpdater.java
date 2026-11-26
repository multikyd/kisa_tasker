package dev.chargepilot.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

final class AppUpdater {
    static final int INSTALL_REQUEST = 40;
    private static final String ROOT = "https://raw.githubusercontent.com/multikyd/kisa_tasker/main/";
    private static final String APK_URL = ROOT + "kisa_tasker.apk";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private final MainActivity activity;
    private final SharedPreferences prefs;
    private JSONObject offer;
    private boolean visible;
    private boolean installing;

    AppUpdater(MainActivity activity) {
        this.activity = activity;
        prefs = activity.getSharedPreferences("updates", Context.MODE_PRIVATE);
    }

    static File cachedApk(Context context) { return new File(context.getCacheDir(), "kisa_tasker.apk"); }

    String versionName() {
        try { return installed(0).versionName; }
        catch (PackageManager.NameNotFoundException error) { return ""; }
    }

    void check(boolean manual) {
        if (!BUSY.compareAndSet(false, true)) {
            if (manual) activity.message("다른 업데이트 작업이 진행 중입니다.");
            return;
        }
        if (manual) activity.updateBusy(true);
        IO.execute(() -> {
            try {
                JSONObject update = metadata();
                boolean newer = update.getLong("versionCode") > installed(0).getLongVersionCode();
                onMain(() -> {
                    if (newer) {
                        offer = update;
                        showOffer();
                    } else if (manual) {
                        activity.message("최신 버전입니다.");
                    }
                });
            } catch (Exception error) {
                android.util.Log.w("KisaTaskerUpdate", "Could not check the update", error);
                if (manual) onMain(() -> activity.message("업데이트 확인 실패 · 인터넷 연결을 확인하세요."));
            } finally {
                BUSY.set(false);
                onMain(() -> activity.updateBusy(false));
            }
        });
    }

    private JSONObject metadata() throws Exception {
        HttpURLConnection connection = connect(ROOT + "kisa_tasker_latest.json");
        try (InputStream stream = connection.getInputStream();
             ByteArrayOutputStream body = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int size; (size = stream.read(buffer)) != -1;) {
                if (body.size() + size > 64_000) throw new IOException("Metadata too large");
                body.write(buffer, 0, size);
            }
            JSONObject update = new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8));
            if (!APK_URL.equals(update.getString("apkUrl")) || update.getLong("versionCode") < 1
                    || !update.getString("sha256").matches("(?i)[0-9a-f]{64}")) {
                throw new IOException("Invalid metadata");
            }
            return update;
        } finally {
            connection.disconnect();
        }
    }

    void onResume() {
        visible = true;
        if (prefs.getLong("pendingVersion", -1) >= 0
                && activity.getPackageManager().canRequestPackageInstalls()) {
            String hash = prefs.getString("pendingHash", "");
            long version = prefs.getLong("pendingVersion", -1);
            prepareInstall(version, hash, null);
        } else {
            showOffer();
        }
    }

    void onPause() { visible = false; }
    void installReturned() { installing = false; }

    private void showOffer() {
        if (!visible || offer == null || activity.isFinishing()) return;
        JSONObject update = offer;
        offer = null;
        JSONObject notes = update.optJSONObject("releaseNotes");
        new AlertDialog.Builder(activity).setTitle("새 버전 " + update.optString("versionName"))
                .setMessage(notes == null ? "업데이트를 내려받습니다." : notes.optString("ko"))
                .setPositiveButton("다운로드", (dialog, which) -> prepareInstall(
                        update.optLong("versionCode"), update.optString("sha256"), update))
                .setNegativeButton("나중에", null).show();
    }

    private void prepareInstall(long version, String hash, JSONObject download) {
        if (installing || !BUSY.compareAndSet(false, true)) return;
        activity.updateBusy(true);
        IO.execute(() -> {
            File file = cachedApk(activity);
            File temp = null;
            try {
                if (version <= installed(0).getLongVersionCode()) {
                    prefs.edit().clear().apply();
                    return;
                }
                if (download != null) {
                    temp = File.createTempFile("kisa_update_", ".apk", activity.getCacheDir());
                    fetchApk(temp);
                    verify(temp, version, hash);
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    verify(file, version, hash);
                }
                prefs.edit().putLong("pendingVersion", version).putString("pendingHash", hash).apply();
                onMain(this::openInstaller);
            } catch (Exception error) {
                android.util.Log.w("KisaTaskerUpdate", "Could not validate the APK", error);
                prefs.edit().clear().apply();
                onMain(() -> activity.message("업데이트 파일을 확인하지 못했습니다. 다시 확인하세요."));
            } finally {
                if (temp != null && temp.exists()) temp.delete();
                BUSY.set(false);
                onMain(() -> activity.updateBusy(false));
            }
        });
    }

    private void openInstaller() {
        if (!visible || !cachedApk(activity).isFile()) return;
        try {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(activity).setTitle("업데이트 설치 권한")
                        .setMessage("다운로드가 완료되었습니다. 다음 화면에서 이 앱의 설치 권한을 허용하면 업데이트 설치를 이어갑니다.")
                        .setPositiveButton("설정 열기", (d, w) -> activity.startActivity(
                                new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + activity.getPackageName()))))
                        .setNegativeButton("나중에", null).show();
                return;
            }
            installing = true;
            Intent install = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(UpdateProvider.URI, UpdateProvider.MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.setClipData(ClipData.newRawUri("kisa_tasker.apk", UpdateProvider.URI));
            activity.startActivityForResult(install, INSTALL_REQUEST);
            prefs.edit().clear().apply();
        } catch (RuntimeException error) {
            installing = false;
            activity.message("안드로이드 설치 화면을 열 수 없습니다.");
        }
    }

    private void fetchApk(File output) throws Exception {
        HttpURLConnection connection = connect(APK_URL);
        try (InputStream stream = connection.getInputStream(); FileOutputStream file = new FileOutputStream(output)) {
            byte[] buffer = new byte[16_384];
            long count = 0;
            for (int size; (size = stream.read(buffer)) != -1;) {
                count += size;
                if (count > 32 * 1024 * 1024) throw new IOException("APK too large");
                file.write(buffer, 0, size);
            }
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection connect(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address + "?t="
                + System.currentTimeMillis()).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setUseCaches(false);
        if (connection.getResponseCode() != 200) {
            connection.disconnect();
            throw new IOException("Update endpoint unavailable");
        }
        return connection;
    }

    private PackageInfo installed(int flags) throws PackageManager.NameNotFoundException {
        return activity.getPackageManager().getPackageInfo(activity.getPackageName(), flags);
    }

    private void verify(File file, long version, String expectedHash) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[16_384];
            for (int size; (size = stream.read(buffer)) != -1;) digest.update(buffer, 0, size);
        }
        StringBuilder hash = new StringBuilder(64);
        for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
        if (!expectedHash.equalsIgnoreCase(hash.toString())) throw new IOException("APK SHA-256 mismatch");
        PackageInfo current = installed(PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo received = activity.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (received == null || received.signingInfo == null || current.signingInfo == null
                || !activity.getPackageName().equals(received.packageName)
                || received.getLongVersionCode() != version || version <= current.getLongVersionCode()
                || !Arrays.equals(current.signingInfo.getApkContentsSigners(), received.signingInfo.getApkContentsSigners())) {
            throw new IOException("APK identity, version or signature mismatch");
        }
    }

    private void onMain(Runnable task) {
        activity.runOnUiThread(() -> {
            if (!activity.isDestroyed() && !activity.isFinishing()) task.run();
        });
    }
}
