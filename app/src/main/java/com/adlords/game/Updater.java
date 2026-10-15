package com.adlords.game;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Iterator;

/**
 * Keeps the game up to date without reinstalling.
 * The published feed (update.json, attached to the newest GitHub release) lists every game file with its SHA-1.
 * Files that differ from the copy inside the APK are downloaded into files/next/; the next start (or a restart
 * from the in-game banner) moves them to files/live/, and the asset loader serves them in place of the bundled ones.
 * If the app itself changed (Java, manifest, resources), the feed's nativeHash differs and the page is offered an APK install.
 */
class Updater {
    interface Listener { void onContent(int version); void onApk(String url, int version); void onNone(); void onError(String why); }

    private final Context ctx;
    private final AssetManager assets;
    private final File live, liveWww, next;
    private JSONObject bundled = new JSONObject(), liveMan = null;
    private volatile boolean busy;
    String apkUrl = null;

    Updater(Context c) {
        ctx = c; assets = c.getAssets();
        live = new File(c.getFilesDir(), "live"); liveWww = new File(live, "www"); next = new File(c.getFilesDir(), "next");
        try { bundled = new JSONObject(readAll(assets.open("www-manifest.json"))); } catch (Exception e) { bundled = new JSONObject(); }
        activate();
    }

    int bundledVersion() { return bundled.optInt("version", 0); }
    int liveVersion() { return liveMan == null ? 0 : liveMan.optInt("version", 0); }
    int version() { return Math.max(bundledVersion(), liveVersion()); }

    /** move a finished download into place; drop a downloaded copy that is older than the APK's own */
    synchronized void activate() {
        File nm = new File(next, "manifest.json");
        if (nm.exists()) { deleteTree(live); if (!next.renameTo(live)) deleteTree(next); }
        deleteTree(next);
        liveMan = null;
        File lm = new File(live, "manifest.json");
        if (lm.exists()) {
            try { liveMan = new JSONObject(readAll(new FileInputStream(lm))); } catch (Exception e) { liveMan = null; }
        }
        if (liveMan == null || liveMan.optInt("version", 0) <= bundledVersion()) { deleteTree(live); liveMan = null; }
    }

    /** a downloaded file to serve instead of the bundled one, or null */
    File liveFile(String path) {
        JSONObject m = liveMan; if (m == null || path.contains("..")) return null;
        JSONObject f = m.optJSONObject("changed"); if (f == null || !f.has(path)) return null;
        File x = new File(liveWww, path);
        return x.isFile() ? x : null;
    }

    void check(Listener cb) {
        if (busy) return; busy = true;
        new Thread(() -> {
            try { run(cb); } catch (Exception e) { cb.onError(String.valueOf(e.getMessage())); }
            finally { busy = false; }
        }, "updater").start();
    }

    private void run(Listener cb) throws Exception {
        JSONObject feed = new JSONObject(new String(get(BuildConfig.UPDATE_FEED), "UTF-8"));
        int remote = feed.optInt("version", 0);
        boolean any = false;
        String nh = feed.optString("nativeHash", "");
        if (!nh.isEmpty() && !nh.equals(BuildConfig.NATIVE_HASH) && feed.has("apk")) { apkUrl = feed.getString("apk"); cb.onApk(apkUrl, remote); any = true; }
        if (remote > version() && !new File(next, "manifest.json").exists()) {
            JSONObject files = feed.getJSONObject("files"), have = bundled.optJSONObject("files"), changed = new JSONObject();
            if (have == null) have = new JSONObject();
            String base = feed.getString("base");
            deleteTree(next); File nw = new File(next, "www");
            for (Iterator<String> it = files.keys(); it.hasNext(); ) {
                String path = it.next(), sha = files.getString(path);
                if (sha.equals(have.optString(path)) || path.contains("..")) continue;   // the APK already has this one
                File out = new File(nw, path); out.getParentFile().mkdirs();
                File old = liveFile(path);
                byte[] data = old != null && sha.equals(sha1(readBytes(new FileInputStream(old)))) ? readBytes(new FileInputStream(old)) : get(base + encode(path));
                if (!sha.equals(sha1(data))) throw new IOException("checksum mismatch for " + path);
                try (OutputStream o = new FileOutputStream(out)) { o.write(data); }
                changed.put(path, sha);
            }
            JSONObject man = new JSONObject(); man.put("version", remote); man.put("changed", changed);
            try (OutputStream o = new FileOutputStream(new File(next, "manifest.json"))) { o.write(man.toString().getBytes("UTF-8")); }
            cb.onContent(remote); any = true;
        } else if (new File(next, "manifest.json").exists()) { cb.onContent(remote); any = true; }
        if (!any) cb.onNone();
    }

    File downloadApk(String url, Progress p) throws Exception {
        File out = new File(new File(ctx.getCacheDir(), "upd"), "AdLords.apk"); out.getParentFile().mkdirs();
        getTo(url, out, p);
        return out;
    }

    // ---- helpers ----
    private static String encode(String path) {
        StringBuilder b = new StringBuilder();
        for (String part : path.split("/")) { if (b.length() > 0) b.append('/'); try { b.append(java.net.URLEncoder.encode(part, "UTF-8").replace("+", "%20")); } catch (Exception e) { b.append(part); } }
        return b.toString();
    }
    private static HttpURLConnection open(String url) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "AdLords-Updater"); c.setRequestProperty("Cache-Control", "no-cache");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) { String loc = c.getHeaderField("Location"); c.disconnect(); if (loc == null) break; url = new URL(new URL(url), loc).toString(); continue; }
            if (code != 200) { c.disconnect(); throw new IOException("HTTP " + code); }
            return c;
        }
        throw new IOException("too many redirects");
    }
    static byte[] get(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) { return readBytes(in); } finally { c.disconnect(); }
    }
    interface Progress { void at(int pct); }
    static void getTo(String url, File out, Progress p) throws IOException {
        HttpURLConnection c = open(url); long total = c.getContentLengthLong(), done = 0; int last = -1;
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); done += n; int pct = total > 0 ? (int) (done*100/total) : -1; if (p != null && pct != last && pct % 5 == 0) { last = pct; p.at(pct); } }
        } finally { c.disconnect(); }
    }
    static byte[] readBytes(InputStream in) throws IOException {
        try (InputStream i = in) { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] buf = new byte[65536]; int n; while ((n = i.read(buf)) > 0) o.write(buf, 0, n); return o.toByteArray(); }
    }
    static String readAll(InputStream in) throws IOException { return new String(readBytes(in), "UTF-8"); }
    static String sha1(byte[] d) {
        try { MessageDigest m = MessageDigest.getInstance("SHA-1"); StringBuilder b = new StringBuilder(); for (byte x : m.digest(d)) b.append(String.format("%02x", x)); return b.toString(); }
        catch (Exception e) { return ""; }
    }
    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] k = f.listFiles(); if (k != null) for (File x : k) deleteTree(x);
        f.delete();
    }
}
