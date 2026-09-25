package org.telegram.messenger;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

/** Personal-device credentials: never packaged in the APK or included in Android backups. */
final class PrivateAiCredentials {
    private static final Properties credentials = load();

    private PrivateAiCredentials() {}

    static String get(String name) {
        return credentials.getProperty(name, "").trim();
    }

    private static Properties load() {
        Properties credentials = new Properties();
        File file = new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "ai.properties");
        try (FileInputStream input = new FileInputStream(file)) {
            credentials.load(input);
        } catch (Exception ignored) {
            // Unprovisioned installs can use the proxy instead.
        }
        return credentials;
    }
}
