package com.winlator;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.GraphicsDrivers;
import com.winlator.core.AppUtils;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONObject;

import java.io.File;
import java.util.Properties;

/**
 * Entry point used by Any Play. Shows only a loading screen, then starts the game directly.
 *
 * Intent action: com.anyplay.unityplugin.action.RUN_GAME
 * Extras (one of the first three is required):
 *   game_dir     (String) folder that contains the .exe and <name>_Data. The plugin finds the exe itself.
 *   game_zip     (String) a .zip of the game. The plugin unpacks it once into its own storage (cached), then plays it.
 *   game_path    (String) full path of the .exe, if already known
 *   game_title   (String, optional)
 *   game_args    (String, optional) Unity launch flags
 *   screen_size  (String, optional) default "1280x720"
 *   box64_preset (String, optional) default "STABILITY"
 */
public class LaunchActivity extends AppCompatActivity {
    public static final String ACTION_LAUNCH = "com.anyplay.unityplugin.action.RUN_GAME";
    private static final String TAG = "AnyPlayUnity";
    private static final String CONTAINER_NAME = "AnyPlayUnity";
    private static final String DEFAULT_SCREEN_SIZE = "1280x720";
    private static final String DEFAULT_BOX64_PRESET = "STABILITY";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private String gamePath;
    private String gameZip;
    private String gameDirExtra;
    private String gameArgs;
    private String screenSize;
    private String box64Preset;
    private TextView statusView;
    private boolean started = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);
        buildLoadingUI();

        Intent intent = getIntent();
        gamePath = intent.getStringExtra("game_path");
        gameZip = intent.getStringExtra("game_zip");
        gameDirExtra = intent.getStringExtra("game_dir");
        gameArgs = intent.getStringExtra("game_args");
        screenSize = intent.getStringExtra("screen_size");
        box64Preset = intent.getStringExtra("box64_preset");

        if (gameZip != null && gameZip.isEmpty()) gameZip = null;
        if (screenSize == null || !screenSize.matches("\\d{3,4}x\\d{3,4}")) screenSize = DEFAULT_SCREEN_SIZE;
        if (box64Preset == null || box64Preset.isEmpty()) box64Preset = DEFAULT_BOX64_PRESET;
        if (gameArgs == null) gameArgs = "";

        if (!hasStoragePermission()) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
                PERMISSION_REQUEST_CODE);
        }
        else prepare();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) prepare();
            else {
                Toast.makeText(this, "Storage permission is required to open the game.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Checked only after the storage permission is granted (isFile() is always false without it). */
    private boolean gameFilesOk() {
        if (gameZip == null && (gamePath == null || gamePath.isEmpty()) && gameDirExtra != null) {
            File exe = UnityZipExtractor.locateExe(new File(gameDirExtra));
            if (exe != null) gamePath = exe.getPath();
        }
        boolean zipOk = gameZip != null && (new File(gameZip)).isFile();
        boolean exeOk = gamePath != null && (new File(gamePath)).isFile();
        if (!zipOk && !exeOk) {
            Log.e(TAG, "Game file not found. zip=" + gameZip + " exe=" + gamePath + " dir=" + gameDirExtra);
            Toast.makeText(this, "Game file not found. It may have been moved or deleted.", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private String dxvkVersion = "";

    /** Wolf RPG games open in a window with a title bar; the plugin presses F4 once to switch them to full screen. */
    private boolean autoFullscreenF4 = true;

    /** Debug only: values from Download/AnyPlayUnity/launch.cfg win over the defaults. Also asks Unity for its own log file. */
    private void applyTestConfig() {
        if (!AnyPlayLog.ENABLED) return;
        Properties cfg = AnyPlayLog.readConfig();
        if (cfg.getProperty("game_args") != null) gameArgs = cfg.getProperty("game_args").trim();
        if (cfg.getProperty("screen_size") != null && cfg.getProperty("screen_size").trim().matches("\\d{3,4}x\\d{3,4}")) screenSize = cfg.getProperty("screen_size").trim();
        if (cfg.getProperty("box64_preset") != null && !cfg.getProperty("box64_preset").trim().isEmpty()) box64Preset = cfg.getProperty("box64_preset").trim();
        if (cfg.getProperty("dxvk_version") != null) dxvkVersion = cfg.getProperty("dxvk_version").trim();
        // launch.cfg: auto_f4=0 turns the automatic F4 (full screen) for Wolf RPG games off
        if ("0".equals(cfg.getProperty("auto_f4", "").trim())) autoFullscreenF4 = false;
        // Unity writes its own log into the game folder (G: is the game folder)
        if (!gameArgs.contains("-logFile")) gameArgs = (gameArgs + " -logFile G:/AnyPlay-Player.log").trim();
        AnyPlayLog.write("test config: args=" + gameArgs + " screen=" + screenSize + " box64=" + box64Preset + " dxvk=" + (dxvkVersion.isEmpty() ? "default" : dxvkVersion));
    }

    private void prepare() {
        if (started) return;
        started = true;

        if (!gameFilesOk()) {
            finish();
            return;
        }

        AnyPlayLog.begin();
        AnyPlayLog.write("AnyPlay Unity Plugin launch");
        applyTestConfig();
        AnyPlayLog.write("zip=" + gameZip + " exe=" + gamePath + " dir=" + gameDirExtra);

        if (RootFSInstaller.isInstallNeeded(this)) {
            setStatus("Setting up for the first time...");
            RootFSInstaller.install(this, this::resolveGame);
        }
        else resolveGame();
    }

    /** For a zip: unpack it into the cache first (background thread), then create the container and launch. */
    private void resolveGame() {
        if (gameZip == null) {
            prepareContainer();
            return;
        }
        setStatus("Preparing the game (unpacking the zip, first launch only)...");
        final File zip = new File(gameZip);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File root = UnityZipExtractor.prepare(LaunchActivity.this, zip, new UnityZipExtractor.Progress() {
                        @Override
                        public void onPercent(final int percent) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    setStatus("Unpacking game... " + percent + "%");
                                }
                            });
                        }
                    });
                    final File exe = UnityZipExtractor.locateExe(root);
                    if (exe == null) throw new java.io.IOException("No Unity game (.exe) found inside the zip.");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            gamePath = exe.getPath();
                            prepareContainer();
                        }
                    });
                }
                catch (final Exception e) {
                    Log.e(TAG, "Zip unpack failed: " + zip, e);
                    AnyPlayLog.write("Zip unpack failed: " + e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(LaunchActivity.this, "Couldn't unpack the zip: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            finish();
                        }
                    });
                }
            }
        }).start();
    }

    private void prepareContainer() {
        setStatus("Getting the game ready...");
        final ContainerManager manager = new ContainerManager(this);

        Container container = null;
        for (Container item : manager.getContainers()) {
            if (CONTAINER_NAME.equals(item.getName())) {
                container = item;
                break;
            }
        }

        if (container != null) {
            launchGame(container);
            return;
        }

        try {
            JSONObject data = new JSONObject();
            data.put("name", CONTAINER_NAME);
            data.put("screenSize", screenSize);
            data.put("box64Preset", box64Preset);
            // The Winlator UI sets this for new containers; creating one from JSON without it falls back to Vortek.
            data.put("graphicsDriver", GraphicsDrivers.getDefaultDriver(this));
            manager.createContainerAsync(data, this::launchGame);
        }
        catch (Exception e) {
            Log.e(TAG, "Container creation failed", e);
            Toast.makeText(this, "Couldn't create the game container.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void launchGame(Container container) {
        if (container == null) {
            Toast.makeText(this, "Couldn't create the game container.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Mount the game's folder as drive G: so a game can run from any folder.
        File gameDir = (new File(gamePath)).getParentFile();
        String drives = "G:" + gameDir.getPath() + Container.DEFAULT_DRIVES;

        container.setScreenSize(screenSize);
        container.setBox64Preset(box64Preset);
        // Turnip on Adreno phones (Winlator's own default). Also repairs a container created earlier with Vortek.
        String wantedDriver = GraphicsDrivers.getDefaultDriver(this);
        if (!wantedDriver.equals(container.getGraphicsDriver())) container.setGraphicsDriver(wantedDriver);
        container.setDrives(drives);
        // Empty = the app's default DXVK version; "1.10.3" can be tried through launch.cfg on weak GPUs.
        container.setDXWrapperConfig(dxvkVersion.isEmpty() ? "" : "version=" + dxvkVersion);
        container.saveData();

        if (AnyPlayLog.ENABLED) {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            preferences.edit().putBoolean("enable_wine_debug", true).putInt("box64_logs", 1).apply();
            AnyPlayLog.write("container=" + container.id + " driver=" + container.getGraphicsDriver()
                + " screen=" + screenSize + " box64=" + box64Preset + " drives=" + drives);
        }

        Intent intent = new Intent(this, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("exec_path", gamePath);
        intent.putExtra("exec_args", gameArgs);
        if (autoFullscreenF4 && UnityZipExtractor.isWolfGameDir(gameDir)) intent.putExtra("auto_fullscreen_f4", true);
        // The on-screen gamepad layout is saved per game: the zip, or the game's folder.
        String gameKey = gameZip != null ? gameZip : (gameDirExtra != null ? gameDirExtra : gameDir.getPath());
        intent.putExtra("game_key", gameKey);
        startActivity(intent);
        finish();
    }

    private void setStatus(String text) {
        if (statusView != null) statusView.setText(text);
    }

    private void buildLoadingUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#101114"));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);
        title.setText("AnyPlay");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        ProgressBar progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(120, 120);
        pbParams.topMargin = 40;
        box.addView(progressBar, pbParams);

        statusView = new TextView(this);
        statusView.setText("Loading...");
        statusView.setTextColor(Color.LTGRAY);
        statusView.setTextSize(14);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tvParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvParams.topMargin = 30;
        box.addView(statusView, tvParams);

        root.addView(box, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
    }
}
