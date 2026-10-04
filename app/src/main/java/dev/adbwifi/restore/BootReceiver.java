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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Entry points of the self-heal flow:
 * <ul>
 *   <li>{@code android.intent.action.BOOT_COMPLETED} / {@code LOCKED_BOOT_COMPLETED}
 *       — system boot (the receiver is directBootAware, so it also runs before
 *       first unlock);</li>
 *   <li>{@code dev.adbwifi.restore.TRIGGER} — explicit broadcast, e.g. from a
 *       Termux:Boot script as a second line of defence:
 *       {@code am broadcast -a dev.adbwifi.restore.TRIGGER}</li>
 * </ul>
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "AdbWifiRestore";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null && intent.getAction() != null ? intent.getAction() : "null";
        Log.i(TAG, "Received broadcast: " + action);

        Intent serviceIntent = new Intent(context, RestoreService.class);
        serviceIntent.putExtra("trigger", action);
        try {
            context.startService(serviceIntent);
        } catch (Throwable t) {
            Log.e(TAG, "startService failed, running engine inline: " + t.getMessage());
            RestoreEngine.getInstance().execute(context, "receiver-fallback:" + action);
        }
    }
}
