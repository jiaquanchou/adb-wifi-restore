/*
 * Copyright 2026 adb-wifi-restore contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.adbwifi.restore;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Status dashboard: permission check (with copyable setup commands when the
 * grant is missing), wireless-debugging state, the detected dynamic port with a
 * copyable {@code adb connect} command, device IPv4 addresses, the HTTP
 * reporter state and the raw status JSON.
 */
public class MainActivity extends Activity {

    private static final String GRANT_CMD =
            "adb shell pm grant " + RestoreEngine.PACKAGE_ID
                    + " android.permission.WRITE_SECURE_SETTINGS";
    private static final String WHITELIST_CMD =
            "adb shell dumpsys deviceidle whitelist +" + RestoreEngine.PACKAGE_ID;

    /** The page is for one-off status checks only; close it after this idle
     * time so a headless phone left on a shelf never sits on this screen. The
     * app process (HTTP reporter, NSD, engine threads) keeps running. */
    private static final long AUTO_CLOSE_IDLE_MS = 30_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            updateUi();
            handler.postDelayed(this, 1500);
        }
    };
    private final Runnable autoCloseRunnable = new Runnable() {
        @Override
        public void run() {
            Toast.makeText(MainActivity.this, R.string.auto_closed_toast, Toast.LENGTH_SHORT).show();
            finish();
        }
    };

    private TextView permissionState;
    private TextView grantCommand;
    private Button btnCopyGrant;
    private TextView whitelistCommand;
    private Button btnCopyWhitelist;
    private TextView adbState;
    private TextView wifiState;
    private TextView adbError;
    private TextView connectCommand;
    private Button btnCopyConnect;
    private TextView ipsText;
    private TextView httpState;
    private TextView curlExample;
    private Button btnCopyCurl;
    private TextView detailJson;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        permissionState = findViewById(R.id.permission_state);
        grantCommand = findViewById(R.id.grant_cmd);
        btnCopyGrant = findViewById(R.id.btn_copy_grant);
        whitelistCommand = findViewById(R.id.whitelist_cmd);
        btnCopyWhitelist = findViewById(R.id.btn_copy_whitelist);
        adbState = findViewById(R.id.adb_state);
        wifiState = findViewById(R.id.wifi_state);
        adbError = findViewById(R.id.adb_error);
        connectCommand = findViewById(R.id.connect_cmd);
        btnCopyConnect = findViewById(R.id.btn_copy_connect);
        ipsText = findViewById(R.id.ips_text);
        httpState = findViewById(R.id.http_state);
        curlExample = findViewById(R.id.curl_example);
        btnCopyCurl = findViewById(R.id.btn_copy_curl);
        detailJson = findViewById(R.id.detail_json);

        grantCommand.setText(GRANT_CMD);
        whitelistCommand.setText(WHITELIST_CMD);

        findViewById(R.id.btn_restore_now).setOnClickListener(v -> {
            RestoreEngine.getInstance().execute(MainActivity.this, "manual_ui");
            updateUi();
        });
        btnCopyGrant.setOnClickListener(v -> copy(GRANT_CMD));
        btnCopyWhitelist.setOnClickListener(v -> copy(WHITELIST_CMD));
        btnCopyConnect.setOnClickListener(v -> copy(connectCommand.getText().toString()));
        btnCopyCurl.setOnClickListener(v -> copy(curlExample.getText().toString()));

        RestoreEngine.getInstance().execute(this, "activity_create");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUi();
        handler.post(refreshRunnable);
        scheduleAutoClose();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshRunnable);
        handler.removeCallbacks(autoCloseRunnable);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        scheduleAutoClose();
    }

    private void scheduleAutoClose() {
        handler.removeCallbacks(autoCloseRunnable);
        handler.postDelayed(autoCloseRunnable, AUTO_CLOSE_IDLE_MS);
    }

    private void updateUi() {
        RestoreEngine engine = RestoreEngine.getInstance();

        boolean granted = engine.hasSecurePermission();
        permissionState.setText(granted ? R.string.permission_ok : R.string.permission_missing);
        permissionState.setTextColor(getColor(granted ? R.color.ok : R.color.bad));
        int setupVisibility = granted ? View.GONE : View.VISIBLE;
        grantCommand.setVisibility(setupVisibility);
        btnCopyGrant.setVisibility(setupVisibility);
        whitelistCommand.setVisibility(setupVisibility);
        btnCopyWhitelist.setVisibility(setupVisibility);

        int setting = engine.currentSettingValue();
        if (setting == 1) {
            adbState.setText(R.string.adb_on);
            adbState.setTextColor(getColor(R.color.ok));
        } else if (setting == 0) {
            adbState.setText(R.string.adb_off);
            adbState.setTextColor(getColor(R.color.bad));
        } else {
            adbState.setText(R.string.adb_unknown);
            adbState.setTextColor(getColor(R.color.text_secondary));
        }
        wifiState.setText(engine.isWifiConnected() ? R.string.wifi_ok : R.string.wifi_waiting);

        String error = engine.lastError();
        adbError.setText(error);
        adbError.setVisibility(error.isEmpty() ? View.GONE : View.VISIBLE);

        List<String> ips = RestoreEngine.listIps();
        String primaryIp = ips.isEmpty() ? "<device-ip>" : ips.get(0);
        int port = engine.detectedPort();
        String host = engine.detectedHost().isEmpty() ? primaryIp : engine.detectedHost();
        if (port > 0) {
            connectCommand.setText(getString(R.string.connect_cmd_fmt, host, port));
            btnCopyConnect.setVisibility(View.VISIBLE);
        } else {
            connectCommand.setText(R.string.connect_waiting);
            btnCopyConnect.setVisibility(View.GONE);
        }

        ipsText.setText(ips.isEmpty() ? getString(R.string.ips_none) : joinLines(ips));

        boolean httpRunning = engine.isHttpServerRunning();
        httpState.setText(httpRunning
                ? getString(R.string.http_running, StatusHttpServer.PORT)
                : getString(R.string.http_stopped));
        httpState.setTextColor(getColor(httpRunning ? R.color.ok : R.color.bad));
        curlExample.setText(getString(R.string.curl_cmd_fmt, primaryIp, StatusHttpServer.PORT));

        detailJson.setText(engine.buildStatusJson());
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("adb-wifi-restore", text));
            Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private static String joinLines(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
