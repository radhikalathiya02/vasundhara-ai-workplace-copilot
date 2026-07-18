package com.vasundhara.atf.aiproduct;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class DebugKeystoreManager {

    private static final Logger log = LoggerFactory.getLogger(DebugKeystoreManager.class);

    private static final String KEYSTORE_PATH = System.getProperty("user.home") + "/.android/debug.keystore";

    @PostConstruct
    public void ensureKeystoreExists() {
        try {
            Path ks = Paths.get(KEYSTORE_PATH);
            if (Files.exists(ks)) {
                log.info("Debug keystore found at {}", KEYSTORE_PATH);
                return;
            }

            // Create ~/.android dir if needed
            Files.createDirectories(ks.getParent());

            log.info("Creating debug keystore at {}", KEYSTORE_PATH);
            ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkey", "-v",
                "-keystore", KEYSTORE_PATH,
                "-alias", "androiddebugkey",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "10000",
                "-storepass", "android",
                "-keypass", "android",
                "-dname", "CN=Android Debug,O=Android,C=US"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exit = p.waitFor();
            if (exit == 0) {
                log.info("Debug keystore created successfully");
            } else {
                log.warn("keytool exited with code {} — APK signing may fail", exit);
            }
        } catch (Exception ex) {
            log.warn("Could not ensure debug keystore: {} — builds will rely on Gradle debug signing", ex.getMessage());
        }
    }

    public String getKeystorePath() { return KEYSTORE_PATH; }

    public boolean exists() { return new File(KEYSTORE_PATH).exists(); }
}
