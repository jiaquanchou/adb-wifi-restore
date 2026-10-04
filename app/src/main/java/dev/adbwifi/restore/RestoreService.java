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

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Thin one-shot service: runs the engine for the triggering broadcast and exits.
 * The HTTP reporter and the NSD discovery it starts live on their own process
 * threads and keep running after {@link #stopSelf(int)}.
 */
public class RestoreService extends Service {
    private static final String TAG = "AdbRestoreService";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String trigger = intent != null && intent.hasExtra("trigger")
                ? intent.getStringExtra("trigger") : "service_direct";
        Log.i(TAG, "onStartCommand, trigger: " + trigger);

        RestoreEngine.getInstance().execute(this, trigger);
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
