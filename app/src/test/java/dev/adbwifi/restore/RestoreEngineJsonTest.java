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

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure-JVM tests for the HTTP reporter contract. The JSON field names are a
 * public interface (scripts and runbooks depend on them) — these tests pin
 * them down without needing an emulator or any Android runtime.
 */
public class RestoreEngineJsonTest {

    @Test
    public void statusJson_carriesTheStableContractKeys() {
        String json = RestoreEngine.getInstance().buildStatusJson();

        String[] expectedKeys = {
                "\"app\": \"dev.adbwifi.restore\"",
                "\"hasSecurePermission\": false",
                "\"isWifiConnected\": false",
                "\"currentSettingValue\": -1",
                "\"putSuccess\": false",
                "\"detectedPort\": -1",
                "\"detectedHost\": \"\"",
                "\"ips\": [",
                "\"lastError\": \"\"",
                "\"lastTrigger\": \"none\"",
                "\"lastUpdated\": \"never\""
        };
        for (String key : expectedKeys) {
            assertTrue("missing contract key: " + key + "\njson was:\n" + json,
                    json.contains(key));
        }
    }

    @Test
    public void ips_enumeratesOnlyDottedQuadStrings() {
        for (String ip : RestoreEngine.listIps()) {
            assertTrue("not an IPv4 literal: " + ip,
                    ip.matches("\\d{1,3}(\\.\\d{1,3}){3}"));
        }
    }
}
