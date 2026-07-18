package com.vasundhara.atf.smartexec;

import com.vasundhara.atf.model.ApkInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the "APK Analyzer" report section directly from the already-parsed {@link ApkInfo}. */
public final class SmartApkReportBuilder {

    private SmartApkReportBuilder() {}

    public static Map<String, Object> build(ApkInfo apk) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("packageName", apk.getPackageName());
        manifest.put("versionName", apk.getVersionName());
        manifest.put("versionCode", apk.getVersionCode());
        manifest.put("minSdkVersion", apk.getMinSdkVersion());
        manifest.put("targetSdkVersion", apk.getTargetSdkVersion());
        manifest.put("mainActivity", apk.getMainActivity());
        manifest.put("apkSizeBytes", apk.getApkSizeBytes());
        out.put("manifest", manifest);
        out.put("permissions", apk.getPermissions());
        out.put("dangerousPermissions", apk.getDangerousPermissions());
        out.put("activities", apk.getActivities());
        out.put("detectedSdks", apk.getDetectedSdks());
        out.put("libraries", apk.getDetectedSdks());
        out.put("exportedComponents", apk.getExportedComponents());
        out.put("signatureSchemes", apk.getSignatureSchemes());
        out.put("supportedAbis", apk.getSupportedAbis());
        out.put("supportedLocales", apk.getSupportedLocales());
        // Deep-link extraction is not available from the current static analyzer output; reported
        // honestly rather than guessed — a Phase-2 candidate (manifest intent-filter parsing).
        out.put("deepLinks", List.of());
        out.put("deepLinksNote", "Not detected — deep-link extraction is not yet implemented.");
        return out;
    }
}
