package com.winlator;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
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

/**
 * Any Play se aane wala entry point. UI nahi dikhata, sirf loading screen.
 *
 * Intent action : com.anyplay.unityplugin.action.RUN_GAME
 * Extras (ek hi chahiye):
 *   game_dir    (String) -> game ka folder (jisme Game.exe aur Game_Data hai). Exe plugin khud dhundta hai.
 *   game_zip    (String) -> game ka .zip. Plugin use apne storage mein ek baar kholta hai (cache), phir chalata hai.
 *   game_path   (String, optional) -> agar exe ka full path pehle se pata ho
 *   game_title  (String, optional)
 *   game_args   (String, optional) -> Unity launch flags
 *   screen_size (String, optional) -> default "1280x720"
 *   box64_preset(String, optional) -> default "STABILITY"
 */
public class LaunchActivity extends AppCompatActivity {
    public static final String ACTION_LAUNCH = "com.anyplay.unityplugin.action.RUN_GAME";
    private static final String CONTAINER_NAME = "AnyPlayUnity";
    private static final String DEFAULT_SCREEN_SIZE = "1280x720";
    private static final String DEFAULT_BOX64_PRESET = "STABILITY";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String TAG = "AnyPlayUnity";

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
        gameArgs = intent.getStringExtra("game_args");
        screenSize = intent.getStringExtra("screen_size");
        box64Preset = intent.getStringExtra("box64_preset");

        String gameDir = intent.getStringExtra("game_dir");
        if (gameZip != null && gameZip.isEmpty()) gameZip = null;
        if (gameZip == null && (gamePath == null || gamePath.isEmpty()) && gameDir != null) {
            File exe = UnityZipExtractor.locateExe(new File(gameDir));
            if (exe != null) gamePath = exe.getPath();
        }

        gameDirExtra = gameDir;

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

    /** Storage permission milne ke BAAD check karte hain, warna isFile() hamesha false aata hai. */
    private boolean gameFilesOk() {
        if (gameZip == null && (gamePath == null || gamePath.isEmpty()) && gameDirExtra != null) {
            File exe = UnityZipExtractor.locateExe(new File(gameDirExtra));
            if (exe != null) gamePath = exe.getPath();
        }
        boolean zipOk = gameZip != null && (new File(gameZip)).isFile();
        boolean exeOk = gamePath != null && (new File(gamePath)).isFile();
        if (!zipOk && !exeOk) {
            Log.e(TAG, "Game file nahi mili. zip=" + gameZip + " exe=" + gamePath + " dir=" + gameDirExtra);
            Toast.makeText(this, "Game file nahi mili", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) prepare();
            else {
                Toast.makeText(this, "Storage permission zaruri hai", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void prepare() {
        if (started) return;
        started = true;

        if (!gameFilesOk()) {
            finish();
            return;
        }

        if (RootFSInstaller.isInstallNeeded(this)) {
            setStatus("Pehli baar setup ho raha hai...");
            RootFSInstaller.install(this, this::resolveGame);
        }
        else resolveGame();
    }

    /** Zip ho to pehle cache mein kholo (background thread), phir container + launch. */
    private void resolveGame() {
        if (gameZip == null) {
            prepareContainer();
            return;
        }
        setStatus("Game taiyar ho raha hai (pehli baar zip khulta hai)...");
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
                                    setStatus("Game khul raha hai... " + percent + "%");
                                }
                            });
                        }
                    });
                    final File exe = UnityZipExtractor.locateExe(root);
                    if (exe == null) throw new java.io.IOException("Zip ke andar Unity game (.exe) nahi mila");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            gamePath = exe.getPath();
                            prepareContainer();
                        }
                    });
                }
                catch (final Exception e) {
                    Log.e(TAG, "Zip extract failed: " + zip, e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(LaunchActivity.this, "Zip nahi khul paya: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            finish();
                        }
                    });
                }
            }
        }).start();
    }

    private void prepareContainer() {
        setStatus("Game taiyar ho raha hai...");
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
            data.put("graphicsDriver", GraphicsDrivers.getDefaultDriver(this));
            manager.createContainerAsync(data, this::launchGame);
        }
        catch (Exception e) {
            Toast.makeText(this, "Container nahi ban paya", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void launchGame(Container container) {
        if (container == null) {
            Toast.makeText(this, "Container nahi ban paya", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Game ka folder G: drive banake mount karte hain, taaki koi bhi folder se game chal sake.
        File gameDir = (new File(gamePath)).getParentFile();
        String drives = "G:" + gameDir.getPath() + Container.DEFAULT_DRIVES;

        container.setScreenSize(screenSize);
        container.setBox64Preset(box64Preset);
        // Adreno phone pe Turnip (Winlator ka apna default). Pehle bane container mein Vortek reh gaya ho to use bhi theek karo.
        String wantedDriver = GraphicsDrivers.getDefaultDriver(this);
        if (!wantedDriver.equals(container.getGraphicsDriver())) container.setGraphicsDriver(wantedDriver);
        container.setDrives(drives);
        container.saveData();

        Intent intent = new Intent(this, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("exec_path", gamePath);
        intent.putExtra("exec_args", gameArgs);
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
