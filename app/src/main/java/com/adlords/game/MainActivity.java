package com.adlords.game;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.provider.Settings;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileInputStream;
import java.util.Locale;

/**
 * Ad Lords: the web game bundled in assets/www, served from https://appassets.androidplatform.net so
 * fetch() of the models works offline. Full screen, screen kept on, and the volume buttons are triggers:
 * during play volume up = hold to fire, volume down = Hellfire / missile; in menus they change the volume.
 */
public class MainActivity extends Activity implements SensorEventListener {
    private static final String START = "https://appassets.androidplatform.net/assets/www/index.html";
    private WebView web;
    private AudioManager audio;
    private Updater upd;
    private SensorManager sensors;
    private Sensor gyro, accel;
    private final Object mLock = new Object();
    private float rx, ry, rz, gx, gy, gz = 9.81f;
    private long gyroT;
    private boolean hasGyro, hasAccel;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        upd = new Updater(this);
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensors != null) { gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE); accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER); }
        final WebViewAssetLoader.AssetsPathHandler bundledAssets = new WebViewAssetLoader.AssetsPathHandler(this);
        // downloaded game files win over the copies inside the APK
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", path -> {
                    if (path.startsWith("www/")) {
                        File f = upd.liveFile(path.substring(4));
                        if (f != null) { try { return new WebResourceResponse(mime(path), null, new FileInputStream(f)); } catch (Exception ignored) { } }
                    }
                    return bundledAssets.handle(path);
                })
                .build();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                    // saves (localStorage)
        s.setMediaPlaybackRequiresUserGesture(false);    // sound starts without an extra tap
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        web.setBackgroundColor(0xFF0B2A3C);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
        });
        web.addJavascriptInterface(new Bridge(), "AdLordsNative");
        setContentView(web);
        hideBars();

        if (state != null) web.restoreState(state); else web.loadUrl(START);
        web.postDelayed(() -> checkUpdates(false), 4000);
    }

    private static String mime(String p) {
        String l = p.toLowerCase(Locale.US);
        if (l.endsWith(".html")) return "text/html"; if (l.endsWith(".js")) return "text/javascript"; if (l.endsWith(".json")) return "application/json";
        if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg"; if (l.endsWith(".png")) return "image/png"; if (l.endsWith(".webp")) return "image/webp";
        if (l.endsWith(".mp3")) return "audio/mpeg"; if (l.endsWith(".css")) return "text/css"; if (l.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private void js(String code) { runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); }); }
    private static String q(String s) { return "'" + String.valueOf(s).replace("\\", "\\\\").replace("'", "\\'") + "'"; }

    private void checkUpdates(boolean manual) {
        upd.check(new Updater.Listener() {
            public void onContent(int v) { js("window.adUpdate&&adUpdate({type:'content',version:" + v + "})"); }
            public void onApk(String url, int v) { js("window.adUpdate&&adUpdate({type:'apk',version:" + v + "})"); }
            public void onNone() { if (manual) js("window.adUpdate&&adUpdate({type:'none'})"); }
            public void onError(String why) { if (manual) js("window.adUpdate&&adUpdate({type:'error',why:" + q(why) + "})"); }
        });
    }

    private void installApk() {
        final String url = upd.apkUrl; if (url == null) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            js("window.adUpdate&&adUpdate({type:'allow'})");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        new Thread(() -> {
            try {
                File apk = upd.downloadApk(url, pct -> js("window.adUpdate&&adUpdate({type:'progress',pct:" + pct + "})"));
                Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", apk);
                Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                runOnUiThread(() -> startActivity(i));
            } catch (Exception e) { js("window.adUpdate&&adUpdate({type:'error',why:" + q(String.valueOf(e.getMessage())) + "})"); }
        }, "apk").start();
    }

    // ---- motion sensors for gyro aiming and tilt flying (the page polls motion() every frame) ----
    @Override public void onSensorChanged(SensorEvent e) {
        synchronized (mLock) {
            if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                if (gyroT != 0) { float dt = Math.min(0.1f, (e.timestamp - gyroT)*1e-9f), k = dt*57.29578f; rx += e.values[0]*k; ry += e.values[1]*k; rz += e.values[2]*k; }
                gyroT = e.timestamp; hasGyro = true;
            } else if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                float a = hasAccel ? 0.25f : 1f; gx += (e.values[0] - gx)*a; gy += (e.values[1] - gy)*a; gz += (e.values[2] - gz)*a; hasAccel = true;
            }
        }
    }
    @Override public void onAccuracyChanged(Sensor s, int a) { }

    /** What the page can ask of the app. */
    private class Bridge {
        /** volume key arrived outside of play: behave like a normal volume button */
        @JavascriptInterface public void volume(String dir) {
            runOnUiThread(() -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    "up".equals(dir) ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI));
        }
        /** 'landscape' when you take a seat, 'auto' when you leave it */
        @JavascriptInterface public void orientation(String mode) {
            runOnUiThread(() -> setRequestedOrientation("landscape".equals(mode)
                    ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_FULL_USER));
        }
        @JavascriptInterface public void vibrate(int ms) {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(Math.max(1, ms), VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        }
        @JavascriptInterface public boolean isApp() { return true; }
        /** degrees turned about x, y, z since the last call, then gravity x, y, z, then flags (1 gyro, 2 accelerometer) */
        @JavascriptInterface public String motion() {
            synchronized (mLock) {
                String s = String.format(Locale.US, "%.4f,%.4f,%.4f,%.3f,%.3f,%.3f,%d", rx, ry, rz, gx, gy, gz, (hasGyro ? 1 : 0) | (hasAccel ? 2 : 0));
                rx = ry = rz = 0; return s;
            }
        }
        @JavascriptInterface public String version() { return BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") · content " + upd.version(); }
        @JavascriptInterface public void checkUpdate() { checkUpdates(true); }
        @JavascriptInterface public void installUpdate() { runOnUiThread(MainActivity.this::installApk); }
        /** restart the game on the newest downloaded files */
        @JavascriptInterface public void reload() { runOnUiThread(() -> { upd.activate(); web.clearCache(false); web.loadUrl(START); }); }
    }

    // ---- volume buttons -> the game ----
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() > 0) return true;   // held: one press only
            String key = code == KeyEvent.KEYCODE_VOLUME_UP ? "AudioVolumeUp" : "AudioVolumeDown";
            String type = e.getAction() == KeyEvent.ACTION_DOWN ? "keydown" : "keyup";
            web.evaluateJavascript("window.dispatchEvent(new KeyboardEvent('" + type + "',{key:'" + key + "',code:'" + key + "'}))", null);
            return true;    // the page decides: trigger in play, normal volume change otherwise
        }
        return super.dispatchKeyEvent(e);
    }

    // back button = the game's Esc (pause / leave a seat)
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(function(){var o={key:'Escape',code:'Escape',bubbles:true};window.dispatchEvent(new KeyboardEvent('keydown',o));window.dispatchEvent(new KeyboardEvent('keyup',o));})()", null);
    }

    private void hideBars() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideBars(); }
    @Override protected void onPause() { super.onPause(); if (sensors != null) sensors.unregisterListener(this); web.onPause(); web.evaluateJavascript("document.dispatchEvent(new Event('visibilitychange'))", null); }
    @Override protected void onResume() {
        super.onResume(); web.onResume(); hideBars();
        synchronized (mLock) { gyroT = 0; rx = ry = rz = 0; }
        if (sensors != null) { if (gyro != null) sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME); if (accel != null) sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME); }
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }
    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }
}
