package com.winlator;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import android.util.Log;
import java.util.List;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/**
 * Wine cannot read a game from inside a zip (it needs real files), so to play a game from a zip
 * the plugin unpacks the zip ONCE into its own private storage and keeps it as a cache.
 * The next launch of the same zip starts straight from the cache. Only the last
 * MAX_CACHED_GAMES games are kept.
 *
 * Saves: most Unity games save into the Wine prefix (AppData), which survives cache clean-up.
 */
public final class UnityZipExtractor {
    public interface Progress {
        void onPercent(int percent);
    }

    private static final String MARKER = ".anyplay_ok";
    private static final int MAX_CACHED_GAMES = 2;

    private UnityZipExtractor() {}

    /** Unpacks the zip into the cache (returns the cached folder straight away if it is already unpacked). Call from a background thread. */
    public static File prepare(Context context, File zip, Progress progress) throws IOException {
        File base = new File(context.getFilesDir(), "unity_games");
        base.mkdirs();

        String key = keyFor(zip);
        File target = new File(base, key);
        File marker = new File(target, MARKER);
        String stamp = zip.length() + ":" + zip.lastModified();

        if (marker.isFile() && stamp.equals(readText(marker))) {
            marker.setLastModified(System.currentTimeMillis());
            return target;
        }

        // Remove half-finished extractions from earlier runs
        File[] old = base.listFiles();
        if (old != null) for (File f : old) if (f.getName().endsWith(".tmp")) deleteRecursive(f);
        deleteRecursive(target);

        File tmp = new File(base, key + ".tmp");
        if (!tmp.mkdirs()) throw new IOException("Couldn't create a folder in the plugin storage.");

        boolean ok = false;
        ZipFile zf = openZip(zip);
        try {
            long total = 0;
            Enumeration<ZipArchiveEntry> en = zf.getEntries();
            while (en.hasMoreElements()) {
                ZipArchiveEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                if (e.getGeneralPurposeBit().usesEncryption()) {
                    throw new IOException("This zip is password-protected. Please unzip it first and add the unzipped folder.");
                }
                if (!zf.canReadEntryData(e)) {
                    throw new IOException("This zip uses a compression type that isn't supported (" + e.getName() + "). Please unzip it first and add the unzipped folder.");
                }
                if (e.getSize() > 0) total += e.getSize();
            }
            if (base.getUsableSpace() < total + 300L * 1024 * 1024) {
                throw new IOException("Not enough free storage (the game needs about " + (total / (1024 * 1024)) + " MB).");
            }

            String tmpCanon = tmp.getCanonicalPath() + File.separator;
            byte[] buf = new byte[256 * 1024];
            long done = 0;
            int lastPct = -1;

            en = zf.getEntries();
            while (en.hasMoreElements()) {
                ZipArchiveEntry e = en.nextElement();
                String name = e.getName().replace('\\', '/');
                while (name.startsWith("/")) name = name.substring(1);
                if (name.isEmpty()) continue;

                File out = new File(tmp, name);
                if (!(out.getCanonicalPath() + File.separator).startsWith(tmpCanon)) {
                    throw new IOException("The zip contains an unsafe file path.");
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();

                InputStream in = zf.getInputStream(e);
                OutputStream os = new FileOutputStream(out);
                try {
                    int n;
                    while ((n = readSafe(in, buf, e)) > 0) {
                        os.write(buf, 0, n);
                        done += n;
                        if (progress != null && total > 0) {
                            int pct = (int) Math.min(100, done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                progress.onPercent(pct);
                            }
                        }
                    }
                } finally {
                    os.close();
                    in.close();
                }
                if (e.getTime() > 0) out.setLastModified(e.getTime());
            }

            writeText(new File(tmp, MARKER), stamp);
            if (!tmp.renameTo(target)) throw new IOException("Couldn't finish unpacking the game.");
            new File(target, MARKER).setLastModified(System.currentTimeMillis());
            ok = true;
        } finally {
            try { zf.close(); } catch (IOException ignored) {}
            if (!ok) deleteRecursive(tmp);
        }

        evictOld(base, target);
        return target;
    }

    /**
     * Finds the game's exe inside a folder: for Unity, not UnityCrashHandler and with a matching
     * "<name>_Data" folder next to it; for Wolf RPG, the game exe next to the "Data" folder.
     * Also works when the game sits inside extra folders (up to 3 levels).
     */
    public static File locateExe(File root) {
        File strict = searchStrict(root, 0);
        if (strict != null) return strict;
        // Wolf RPG Editor games (Game.exe / GamePro.exe + Config.exe + Data folder) have no "<name>_Data" folder.
        File wolf = searchWolf(root, 0);
        if (wolf != null) return wolf;
        File fallback = anyExe(root);
        if (fallback != null) return fallback;
        File[] subs = root.listFiles();
        if (subs != null) {
            for (File d : subs) {
                if (d.isDirectory()) {
                    fallback = anyExe(d);
                    if (fallback != null) return fallback;
                }
            }
        }
        return null;
    }

    private static File searchStrict(File dir, int depth) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (!isGameExe(f)) continue;
            String name = f.getName().toLowerCase();
            String dataName = name.substring(0, name.length() - 4) + "_data";
            for (File d : files) {
                if (d.isDirectory() && d.getName().toLowerCase().equals(dataName)) return f;
            }
        }
        if (depth >= 3) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                File r = searchStrict(f, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /**
     * Wolf RPG Editor game: a folder with a "Data" folder (holding "BasicData" or .wolf files) next to the game exe.
     * The folder also has Config.exe (the settings tool), so the exe is the biggest one that is not a config/setup tool.
     * Works for renamed exes too. Looks up to 3 levels deep.
     */
    private static File searchWolf(File dir, int depth) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        File data = null;
        for (File f : files) {
            if (f.isDirectory() && f.getName().equalsIgnoreCase("data")) { data = f; break; }
        }
        if (data != null && isWolfData(data)) {
            File best = null;
            for (File f : files) {
                if (!isGameExe(f)) continue;
                String n = f.getName().toLowerCase();
                if (n.startsWith("config") || n.contains("uninst") || n.contains("setup")) continue;
                if (best == null || f.length() > best.length()) best = f;
            }
            if (best != null) return best;
        }
        if (depth >= 3) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                File r = searchWolf(f, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean isWolfData(File data) {
        File[] items = data.listFiles();
        if (items == null) return false;
        for (File f : items) {
            String n = f.getName().toLowerCase();
            if (f.isDirectory() && n.equals("basicdata")) return true;
            if (f.isFile() && n.endsWith(".wolf")) return true;
        }
        return false;
    }

    private static File anyExe(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) if (isGameExe(f)) return f;
        return null;
    }

    private static boolean isGameExe(File f) {
        String n = f.getName().toLowerCase();
        return f.isFile() && n.endsWith(".exe") && !n.startsWith("unitycrashhandler");
    }

    private static int readSafe(InputStream in, byte[] buf, ZipArchiveEntry entry) throws IOException {
        try {
            return in.read(buf);
        } catch (IOException e) {
            String info = "entry=" + entry.getName() + " method=" + entry.getMethod() + " size=" + entry.getSize()
                + " compressed=" + entry.getCompressedSize();
            Log.e("AnyPlayUnity", "Zip read failed: " + info, e);
            AnyPlayLog.write("Zip read failed: " + info + " : " + e);
            throw new IOException("The zip looks damaged or incomplete (" + entry.getName() + "). Please download it again, or unzip it first and add the unzipped folder.", e);
        }
    }

    private static ZipFile openZip(File zip) throws IOException {
        try {
            return new ZipFile(zip, "UTF-8");
        } catch (IOException e) {
            // Fall back for zips with odd file-name encodings
            return new ZipFile(zip, "ISO-8859-1");
        }
    }

    private static void evictOld(File base, final File keep) {
        File[] dirs = base.listFiles();
        if (dirs == null) return;
        List<File> games = new ArrayList<>();
        for (File d : dirs) {
            if (d.isDirectory() && !d.getName().endsWith(".tmp") && new File(d, MARKER).isFile()) games.add(d);
        }
        Collections.sort(games, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.equals(keep)) return -1;
                if (b.equals(keep)) return 1;
                return Long.compare(new File(b, MARKER).lastModified(), new File(a, MARKER).lastModified());
            }
        });
        for (int i = MAX_CACHED_GAMES; i < games.size(); i++) deleteRecursive(games.get(i));
    }

    private static String keyFor(File zip) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(zip.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static String readText(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[(int) Math.min(f.length(), 1024)];
                int n = in.read(b);
                return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8) : "";
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeText(File f, String text) throws IOException {
        FileOutputStream os = new FileOutputStream(f);
        try {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            os.close();
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }
}
