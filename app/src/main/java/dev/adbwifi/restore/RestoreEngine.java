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

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core self-heal engine. The runtime behaviour is deliberately identical to the
 * field-proven prototype (Redmi K30 5G / Android 12 / MIUI 13):
 *
 * <ol>
 *   <li>start the HTTP status reporter on {@code 0.0.0.0:}{{@code 18055}} so any
 *       LAN / Tailscale client can read the current state at any time;</li>
 *   <li>start NSD discovery for {@code _adb-tls-connect._tcp} — the system adbd
 *       publishes its freshly assigned random port there every time wireless
 *       debugging comes up;</li>
 *   <li>retry a bounded number of times: wait for Wi-Fi, then write
 *       {@code Settings.Global "adb_wifi_enabled" = 1}. The write requires
 *       WRITE_SECURE_SETTINGS, pre-granted once via
 *       {@code adb shell pm grant dev.adbwifi.restore android.permission.WRITE_SECURE_SETTINGS}.</li>
 * </ol>
 */
public class RestoreEngine {
    private static final String TAG = "AdbRestoreEngine";
    private static final RestoreEngine INSTANCE = new RestoreEngine();

    static final String PACKAGE_ID = "dev.adbwifi.restore";
    static final String NSD_SERVICE_TYPE = "_adb-tls-connect._tcp";
    static final String SETTING_KEY = "adb_wifi_enabled";
    private static final int MAX_ATTEMPTS = 6;
    private static final long RETRY_INTERVAL_MS = 4000;

    public static RestoreEngine getInstance() {
        return INSTANCE;
    }

    private final AtomicBoolean nsdStarted = new AtomicBoolean(false);
    private final StatusHttpServer httpServer = new StatusHttpServer(this);

    private volatile boolean hasSecurePermission = false;
    private volatile int currentSettingValue = -1;
    private volatile boolean putSuccess = false;
    private volatile String lastError = "";
    private volatile int detectedPort = -1;
    private volatile String detectedHost = "";
    private volatile String lastTrigger = "none";
    private volatile String lastUpdated = "never";
    private volatile boolean isWifiConnected = false;

    private RestoreEngine() {}

    /** Runs one self-heal round; safe to call from any trigger, any thread. */
    public synchronized void execute(Context context, String trigger) {
        this.lastTrigger = trigger;
        this.lastUpdated = now();
        Context appCtx = context.getApplicationContext();

        httpServer.start(appCtx);
        startNsdDiscovery(appCtx);

        new Thread(() -> {
            for (int i = 0; i < MAX_ATTEMPTS; i++) {
                boolean done = attemptOnce(appCtx, trigger + " attempt#" + (i + 1));
                if (done && detectedPort > 0) {
                    break;
                }
                try {
                    Thread.sleep(RETRY_INTERVAL_MS);
                } catch (InterruptedException ignored) {}
            }
        }, "AdbRestore-Worker").start();
    }

    /** One restore attempt: permission check → Wi-Fi wait → setting write. */
    boolean attemptOnce(Context context, String attemptTag) {
        hasSecurePermission = context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;

        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            try {
                NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
                isWifiConnected = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            } catch (Throwable t) {
                isWifiConnected = false;
            }
        }

        ContentResolver cr = context.getContentResolver();
        try {
            currentSettingValue = Settings.Global.getInt(cr, SETTING_KEY, 0);
        } catch (Throwable t) {
            currentSettingValue = -1;
            lastError = "ReadSettingError: " + t.getMessage();
        }

        if (!hasSecurePermission) {
            lastError = "PERMISSION_DENIED: WRITE_SECURE_SETTINGS not granted";
            writeStatusFile(context);
            return false;
        }

        if (!isWifiConnected) {
            lastError = "WAITING_WIFI: Wi-Fi not connected yet";
            writeStatusFile(context);
            return false;
        }

        try {
            if (currentSettingValue != 1) {
                putSuccess = Settings.Global.putInt(cr, SETTING_KEY, 1);
                lastError = "PutResult: " + putSuccess;
                currentSettingValue = Settings.Global.getInt(cr, SETTING_KEY, 0);
            } else {
                putSuccess = true;
                lastError = "Already enabled (value=1)";
            }
        } catch (SecurityException se) {
            putSuccess = false;
            lastError = "SecurityException: " + se.getMessage();
        } catch (Throwable t) {
            putSuccess = false;
            lastError = "Exception: " + t.getMessage();
        }

        this.lastUpdated = now();
        writeStatusFile(context);
        return putSuccess;
    }

    private synchronized void startNsdDiscovery(Context context) {
        if (nsdStarted.get()) return;
        try {
            NsdManager nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) return;

            NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String regType) {
                    Log.i(TAG, "NSD discovery started: " + regType);
                }

                @Override
                public void onServiceFound(NsdServiceInfo service) {
                    Log.i(TAG, "NSD service found: " + service.getServiceName()
                            + ", type: " + service.getServiceType());
                    if (service.getServiceType() != null
                            && service.getServiceType().contains("_adb-tls-connect")) {
                        try {
                            nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                                @Override
                                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                                    Log.w(TAG, "NSD resolve failed: " + errorCode);
                                }

                                @Override
                                public void onServiceResolved(NsdServiceInfo info) {
                                    detectedPort = info.getPort();
                                    if (info.getHost() != null) {
                                        detectedHost = info.getHost().getHostAddress();
                                    }
                                    Log.i(TAG, "NSD adb port resolved: "
                                            + detectedHost + ":" + detectedPort);
                                    writeStatusFile(context);
                                }
                            });
                        } catch (Throwable t) {
                            Log.w(TAG, "NSD resolve call error: " + t.getMessage());
                        }
                    }
                }

                @Override
                public void onServiceLost(NsdServiceInfo service) {
                    Log.i(TAG, "NSD service lost: " + service.getServiceName());
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                    Log.i(TAG, "NSD discovery stopped");
                }

                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    Log.e(TAG, "NSD start discovery failed: " + errorCode);
                    nsdStarted.set(false);
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                    Log.e(TAG, "NSD stop discovery failed: " + errorCode);
                }
            };

            nsdManager.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
            nsdStarted.set(true);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start NSD discovery: " + t.getMessage());
        }
    }

    // ---------------------------------------------------------------- state

    public boolean hasSecurePermission() { return hasSecurePermission; }
    public int currentSettingValue() { return currentSettingValue; }
    public int detectedPort() { return detectedPort; }
    public String detectedHost() { return detectedHost; }
    public boolean isWifiConnected() { return isWifiConnected; }
    public String lastError() { return lastError; }
    public String lastTrigger() { return lastTrigger; }
    public String lastUpdated() { return lastUpdated; }
    public boolean isHttpServerRunning() { return httpServer.isRunning(); }

    /** All non-loopback IPv4 addresses (Wi-Fi, Ethernet, Tailscale/utun, ...). */
    public static List<String> listIps() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        ips.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Throwable ignored) {}
        Collections.sort(ips);
        return ips;
    }

    public String buildStatusJson() {
        StringBuilder ips = new StringBuilder("[");
        List<String> ipList = listIps();
        for (int i = 0; i < ipList.size(); i++) {
            if (i > 0) ips.append(", ");
            ips.append('"').append(ipList.get(i)).append('"');
        }
        ips.append(']');

        return "{\n" +
                "  \"app\": \"" + PACKAGE_ID + "\",\n" +
                "  \"hasSecurePermission\": " + hasSecurePermission + ",\n" +
                "  \"isWifiConnected\": " + isWifiConnected + ",\n" +
                "  \"currentSettingValue\": " + currentSettingValue + ",\n" +
                "  \"putSuccess\": " + putSuccess + ",\n" +
                "  \"detectedPort\": " + detectedPort + ",\n" +
                "  \"detectedHost\": \"" + escapeJson(detectedHost) + "\",\n" +
                "  \"ips\": " + ips + ",\n" +
                "  \"lastError\": \"" + escapeJson(lastError) + "\",\n" +
                "  \"lastTrigger\": \"" + escapeJson(lastTrigger) + "\",\n" +
                "  \"lastUpdated\": \"" + escapeJson(lastUpdated) + "\"\n" +
                "}\n";
    }

    // ------------------------------------------------------------ persistence

    /** Best-effort copy of the status to
     * /sdcard/Android/data/&lt;pkg&gt;/files/status.json for on-device debugging. */
    private void writeStatusFile(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir != null) {
                File f = new File(dir, "status.json");
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(buildStatusJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable ignored) {}
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}
