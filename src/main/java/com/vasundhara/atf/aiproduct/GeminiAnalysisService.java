package com.vasundhara.atf.aiproduct;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vasundhara.atf.vlegal.service.GeminiClient;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class GeminiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(GeminiAnalysisService.class);

    private final GeminiClient gemini;
    private final ObjectMapper mapper;

    public GeminiAnalysisService(GeminiClient gemini, ObjectMapper mapper) {
        this.gemini = gemini;
        this.mapper = mapper;
    }

    public GeminiSpec analyzeReferenceUrl(String url) {
        GeminiSpec spec = new GeminiSpec();
        if (!StringUtils.hasText(url)) return spec;

        String pageContent = fetchPage(url);
        if (pageContent == null || pageContent.isBlank()) {
            log.warn("Could not fetch reference URL: {}", url);
            return spec;
        }

        String prompt = buildAnalysisPrompt(url, pageContent);
        try {
            String json = gemini.generate(prompt);
            spec = parseSpec(json);
            spec.setRawJson(json);
        } catch (Exception ex) {
            log.error("Gemini analysis failed for {}: {}", url, ex.getMessage());
        }
        return spec;
    }

    private String fetchPage(String url) {
        try {
            return Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; VasundharaBot/1.0)")
                    .timeout(10_000)
                    .followRedirects(true)
                    .get()
                    .body()
                    .text();
        } catch (Exception ex) {
            log.warn("Page fetch failed for {}: {}", url, ex.getMessage());
            return null;
        }
    }

    private String buildAnalysisPrompt(String url, String content) {
        String truncated = content.length() > 6000 ? content.substring(0, 6000) + "…" : content;
        return """
You are an Android app analyst. Analyze the following web page (likely an app's landing page or store listing) and extract structured information to help build an Android replica.

URL: %s
PAGE CONTENT:
---
%s
---

Return ONLY valid JSON, no markdown:
{
  "appName": "<detected app name>",
  "description": "<one-sentence app description>",
  "suggestedPackageName": "<com.example.appname format>",
  "primaryColor": "<hex color like #1565C0, detected from branding or best guess>",
  "screens": ["<screen1>", "<screen2>", ...],
  "features": ["<feature1>", "<feature2>", ...],
  "navigationPattern": "<BottomNav|DrawerNav|TabLayout|Single>"
}

For screens, list the main app screens (Home, Profile, Settings, Login, etc.) based on what you can infer from the page.
""".formatted(url, truncated);
    }

    private GeminiSpec parseSpec(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        GeminiSpec spec = new GeminiSpec();
        spec.setAppName(root.path("appName").asText(""));
        spec.setDescription(root.path("description").asText(""));
        spec.setSuggestedPackageName(root.path("suggestedPackageName").asText(""));
        spec.setPrimaryColor(root.path("primaryColor").asText("#1565C0"));
        spec.setNavigationPattern(root.path("navigationPattern").asText("BottomNav"));

        List<String> screens = new ArrayList<>();
        root.path("screens").forEach(n -> screens.add(n.asText()));
        spec.setScreens(screens);

        List<String> features = new ArrayList<>();
        root.path("features").forEach(n -> features.add(n.asText()));
        spec.setFeatures(features);

        return spec;
    }
}
