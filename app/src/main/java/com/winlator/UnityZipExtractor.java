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
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Wine zip ke andar se game nahi padh sakta (use asli files chahiye), isliye zip se game
 * chalane ke liye plugin zip ko apne private storage mein EK BAAR kholta hai aur cache rakhta hai.
 * Agli baar same zip se seedha chalta hai. Sirf aakhri MAX_CACHED_GAMES games cache mein rehte hain.
 *
 * Saves: zyadatar Unity games Wine prefix (AppData) mein save karte hain, wo cache hatne par bhi bacha rehta hai.
 */
public final class UnityZipExtractor {
    public interface Progress {
        void onPercent(int percent);
    }

    private static final String MARKER = ".anyplay_ok";
    private static final int MAX_CACHED_GAMES = 2;

    private UnityZipExtractor() {}

    /** Zip ko cache folder mein kholta hai (pehle se khula ho to seedha wahi folder deta hai). Background thread se bulao. */
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

        // Adhoore purane extract hata do
        File[] old = base.listFiles();
        if (old != null) for (File f : old) if (f.getName().endsWith(".tmp")) deleteRecursive(f);
        deleteRecursive(target);

        File tmp = new File(base, key + ".tmp");
        if (!tmp.mkdirs()) throw new IOException("Storage mein folder nahi ban paya");

        boolean ok = false;
        ZipFile zf = openZip(zip);
        try {
            long total = 0;
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                if (e.getMethod() != ZipEntry.STORED && e.getMethod() != ZipEntry.DEFLATED) {
                    throw new IOException("Is zip ka compression type support nahi hai. Zip ko unzip karke folder add karo.");
                }
                if (e.getSize() > 0) total += e.getSize();
            }
            if (base.getUsableSpace() < total + 300L * 1024 * 1024) {
                throw new IOException("Phone mein storage kam hai (game ke liye lagbhag " + (total / (1024 * 1024)) + " MB chahiye).");
            }

            String tmpCanon = tmp.getCanonicalPath() + File.separator;
            byte[] buf = new byte[256 * 1024];
            long done = 0;
            int lastPct = -1;

            en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName().replace('\\', '/');
                while (name.startsWith("/")) name = name.substring(1);
                if (name.isEmpty()) continue;

                File out = new File(tmp, name);
                if (!(out.getCanonicalPath() + File.separator).startsWith(tmpCanon)) {
                    throw new IOException("Zip mein galat path mila");
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
                    while ((n = in.read(buf)) > 0) {
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
            if (!tmp.renameTo(target)) throw new IOException("Game folder rename nahi ho paya");
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
     * Folder mein Unity game ka exe dhundta hai: UnityCrashHandler nahi, aur jiske naam ka "<naam>_Data"
     * folder ho. Game ek extra folder ke andar ho to (3 level tak) wo bhi mil jata hai.
     */
    public static File locateExe(File root) {
        File strict = searchStrict(root, 0);
        if (strict != null) return strict;
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

    private static ZipFile openZip(File zip) throws IOException {
        try {
            return new ZipFile(zip, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // UTF-8 mein na padhe jaane wale file naam wale zips
            return new ZipFile(zip, StandardCharsets.ISO_8859_1);
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
