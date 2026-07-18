package com.vasundhara.atf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point for the Android APK Automation Testing Framework (ATF).
 *
 * <p>The application exposes a web dashboard where a QA engineer uploads an Android
 * APK. The framework then installs it on a connected device/emulator and runs a suite
 * of automated test categories (end-to-end, functional, UI/UX, regression, exploratory,
 * negative, monkey, performance, crash, accessibility, ads, security and compatibility)
 * without the engineer hand-writing any test cases.
 */
@SpringBootApplication
@EnableAsync
public class AtfApplication {

    public static void main(String[] args) {
        SpringApplication.run(AtfApplication.class, args);
    }
}
