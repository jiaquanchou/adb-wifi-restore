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

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal zero-dependency HTTP reporter on {@code 0.0.0.0:18055}.
 *
 * <p>mDNS advertisement does not cross subnets or a Tailscale network, so a
 * computer on the far side cannot discover the random wireless-debugging port
 * by itself. This endpoint is the reliable discovery channel: a plain
 * {@code GET /} returns the current status JSON, and {@code GET /?trigger=1}
 * additionally asks the engine to run one restore attempt.</p>
 *
 * <p>Deliberately built on a raw {@link ServerSocket} (no http library) to keep
 * the APK tiny and to let scripts/build-standalone.sh build the very same code
 * without any dependency downloads.</p>
 */
public class StatusHttpServer {
    public static final int PORT = 18055;
    private static final String TAG = "AdbRestoreHttp";

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final RestoreEngine engine;

    public StatusHttpServer(RestoreEngine engine) {
        this.engine = engine;
    }

    public boolean isRunning() {
        return started.get();
    }

    public void start(final Context appContext) {
        if (!started.compareAndSet(false, true)) return;
        Context ctx = appContext.getApplicationContext();
        Thread t = new Thread(() -> serve(ctx), "AdbRestore-HttpServer");
        t.setDaemon(true);
        t.start();
    }

    private void serve(Context appContext) {
        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", PORT));
            Log.i(TAG, "HTTP reporter listening on 0.0.0.0:" + PORT);

            while (!Thread.interrupted()) {
                final Socket client = serverSocket.accept();
                Thread handler = new Thread(() -> handleClient(appContext, client), "AdbRestore-HttpConn");
                handler.setDaemon(true);
                handler.start();
            }
        } catch (Throwable t) {
            Log.e(TAG, "HTTP server error: " + t.getMessage());
            started.set(false);
        } finally {
            if (serverSocket != null) {
                try { serverSocket.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private void handleClient(Context context, Socket client) {
        try {
            client.setSoTimeout(3000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String reqLine = reader.readLine();

            boolean triggerRequested = reqLine != null && reqLine.contains("trigger=1");
            if (triggerRequested) {
                engine.attemptOnce(context, "http_trigger");
            }

            byte[] body = engine.buildStatusJson().getBytes(StandardCharsets.UTF_8);

            OutputStream out = client.getOutputStream();
            String header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: " + body.length + "\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            try { client.close(); } catch (Throwable ignored) {}
        }
    }
}
