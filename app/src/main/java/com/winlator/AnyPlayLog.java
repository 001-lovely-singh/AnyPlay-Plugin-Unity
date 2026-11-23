package com.winlator;

import android.os.Environment;

import com.winlator.core.Callback;
import com.winlator.core.ProcessHelper;

import java.io.File;
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

    private static final Callback<String> PROCESS_OUTPUT = new Callback<String>() {
        @Override
        public void call(String line) {
            write(line);
        }
    };

    private AnyPlayLog() {}

    /** Starts a fresh log file. Call before the game is launched. */
    public static synchronized void begin() {
        if (!ENABLED) return;
        close();
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "Download/AnyPlayUnity");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, "log.txt"), false), "UTF-8"), true);
            written = 0;
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
        writer.println(line);
    }

    private static void close() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }
}
