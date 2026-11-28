package com.winlator;

import android.os.Environment;

import com.winlator.core.Callback;
import com.winlator.core.ProcessHelper;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

/**
 * Temporary debug log. Writes the plugin's own notes plus the Wine/Box64 output to
 *   /storage/emulated/0/Download/AnyPlayUnity/log.txt
 * (the file is overwritten on every launch). Set ENABLED to false once games run fine.
 */
public final class AnyPlayLog {
    public static final boolean ENABLED = true;

    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static PrintWriter writer;
    private static long written = 0;
    private static long startTime = 0;

    private static final Callback<String> PROCESS_OUTPUT = new Callback<String>() {
        @Override
        public void call(String line) {
            write(line);
        }
    };

    private AnyPlayLog() {}

    /**
     * Optional test settings, read from /storage/emulated/0/Download/AnyPlayUnity/launch.cfg (one "key=value" per line,
     * use / instead of \\ in paths). Supported keys: game_args, dxvk_version, box64_preset, screen_size.
     * Only used while ENABLED is true.
     */
    public static Properties readConfig() {
        Properties props = new Properties();
        if (!ENABLED) return props;
        File file = new File(Environment.getExternalStorageDirectory(), "Download/AnyPlayUnity/launch.cfg");
        if (!file.isFile()) return props;
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                props.load(in);
            } finally {
                in.close();
            }
        } catch (IOException ignored) {}
        return props;
    }

    /** Starts a fresh log file. Call before the game is launched. */
    public static synchronized void begin() {
        if (!ENABLED) return;
        close();
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "Download/AnyPlayUnity");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, "log.txt"), false), "UTF-8"), true);
            written = 0;
            startTime = System.currentTimeMillis();
        } catch (IOException e) {
            writer = null;
        }
    }

    /** Sends the Wine/Box64 output to the log file. Call after ProcessHelper.removeAllDebugCallbacks(). */
    public static void attachToProcessOutput() {
        if (!ENABLED) return;
        ProcessHelper.addDebugCallback(PROCESS_OUTPUT);
    }

    public static synchronized void write(String line) {
        if (writer == null || line == null) return;
        written += line.length() + 1;
        if (written > MAX_BYTES) return;
        // Seconds since the launch, so slow loading phases show up in the log
        writer.println(String.format(java.util.Locale.US, "[%7.1fs] ", (System.currentTimeMillis() - startTime) / 1000.0) + line);
    }

    private static void close() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }
}
