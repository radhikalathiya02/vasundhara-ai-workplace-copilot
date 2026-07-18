package com.vasundhara.atf.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.engine.AppIntelligenceReport;
import com.vasundhara.atf.engine.ExplorationResult;
import com.vasundhara.atf.engine.ScreenCapture;
import com.vasundhara.atf.engine.Widget;
import com.vasundhara.atf.model.ApkInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LLM-driven, application-SPECIFIC manual test-case generator. Given a concrete model of the app —
 * its real domain, its actual screens (in visit order) with the actual on-screen control labels
 * observed on each, its discovered features, declared permissions and deep links — this asks the
 * model to write end-to-end manual QA test cases the way a senior manual QA engineer would: grounded
 * in the app's real flow, logically sequenced from first screen to last, covering
 * positive/negative/boundary/validation/edge cases where they genuinely apply, deduplicated, and
 * never referencing modules the app does not have.
 *
 * <p>This is the preferred generator when AI Review is configured; it complements (does not remove)
 * the heuristic {@link com.vasundhara.atf.analysis.TestCaseGenerator}, which remains the fallback.
 * Fully opt-in via the same "AI Review" toggle + API key; returns an empty list on
 * disabled/unconfigured/failure so the caller cleanly falls back — never throws.
 */
@Component
public class LlmTestCaseGenerator {

    private static final Logger log = LoggerFactory.getLogger(LlmTestCaseGenerator.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final int MAX_SCREENS = 40;
    private static final int MAX_LABELS_PER_SCREEN = 18;

    private final AtfProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public LlmTestCaseGenerator(AtfProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public boolean isEnabled() {
        return props.isAiReviewEnabled() && props.getAiApiKey() != null && !props.getAiApiKey().isBlank();
    }

    /** One generated test case, mapped 1:1 to a sheet row by the caller. */
    public record GhCase(String module, String feature, String scenario, String steps,
                         String expected, String priority, String type) {}

    /**
     * Generates app-specific test cases from the observed app model. Empty on
     * disabled/unconfigured/failure/empty-response — never throws.
     */
    public List<GhCase> generate(ApkInfo apkInfo, AppIntelligenceReport report,
                                 List<String> deepLinks, ExplorationResult liveExploration) {
        if (!isEnabled()) return List.of();
        try {
            String model = buildModel(apkInfo, report, deepLinks, liveExploration);
            Map<String, Object> body = Map.of(
                    "model", props.getAiModel(),
                    "max_tokens", 8000,
                    "messages", List.of(Map.of("role", "user", "content", prompt(model)))
            );
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(120))
                    .header("content-type", "application/json")
                    .header("x-api-key", props.getAiApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("LLM test-case generator: HTTP {} — falling back to heuristic generation.", response.statusCode());
                return List.of();
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode contentArr = root.path("content");
            String text = contentArr.isArray() && !contentArr.isEmpty() ? contentArr.get(0).path("text").asText("") : "";
            return parseCases(text);
        } catch (Exception e) {
            log.debug("LLM test-case generation failed, falling back to heuristic: {}", e.toString());
            return List.of();
        }
    }

    /** Compact, factual model of the app — the ONLY grounding the model may write cases from. */
    private String buildModel(ApkInfo apkInfo, AppIntelligenceReport report,
                              List<String> deepLinks, ExplorationResult exp) {
        StringBuilder sb = new StringBuilder();
        sb.append("APP NAME: ").append(nz(apkInfo.getApplicationLabel(), apkInfo.getPackageName())).append('\n');
        sb.append("PACKAGE: ").append(apkInfo.getPackageName()).append('\n');
        sb.append("DOMAIN (inferred): ").append(nz(report.getAppDomain(), "unknown")).append('\n');
        if (report.getAnalysisNotes() != null && !report.getAnalysisNotes().isBlank())
            sb.append("NOTES: ").append(trim(report.getAnalysisNotes(), 400)).append('\n');

        if (!report.getFeatures().isEmpty()) {
            sb.append("\nDISCOVERED FEATURES:\n");
            for (AppIntelligenceReport.DiscoveredFeature f : report.getFeatures())
                sb.append("- ").append(f.getName())
                  .append(f.getDescription() != null && !f.getDescription().isBlank() ? " — " + trim(f.getDescription(), 120) : "")
                  .append('\n');
        }

        if (exp != null && !exp.getScreens().isEmpty()) {
            sb.append("\nSCREENS ACTUALLY VISITED (in order) with real on-screen controls — write cases ONLY around these:\n");
            int n = 0;
            for (ScreenCapture s : exp.getScreens()) {
                if (n++ >= MAX_SCREENS) break;
                sb.append(n).append(". ").append(simple(s.activity())).append(" | controls: ");
                Set<String> labels = new LinkedHashSet<>();
                for (Widget w : s.widgets()) {
                    if (labels.size() >= MAX_LABELS_PER_SCREEN) break;
                    String l = label(w);
                    if (!l.isEmpty()) labels.add(l + (w.editable() ? " [input]" : ""));
                }
                sb.append(labels.isEmpty() ? "(no labeled controls captured)" : String.join(", ", labels)).append('\n');
            }
        } else {
            sb.append("\n(No live device crawl was available — base cases on the features, permissions, and manifest facts below only.)\n");
            if (apkInfo.getMainActivity() != null)
                sb.append("MAIN/ENTRY ACTIVITY: ").append(simple(apkInfo.getMainActivity())).append('\n');
        }

        if (!apkInfo.getDangerousPermissions().isEmpty())
            sb.append("\nDANGEROUS PERMISSIONS DECLARED: ").append(String.join(", ", apkInfo.getDangerousPermissions())).append('\n');
        if (deepLinks != null && !deepLinks.isEmpty())
            sb.append("DEEP LINKS DECLARED: ").append(String.join(", ", deepLinks)).append('\n');
        if (apkInfo.isUsesCleartextTraffic()) sb.append("SECURITY: cleartext HTTP traffic is permitted.\n");
        if (apkInfo.isDebuggable()) sb.append("SECURITY: build is debuggable.\n");
        List<String> locales = apkInfo.getSupportedLocales();
        if (locales != null && locales.size() > 1)
            sb.append("LOCALES: ").append(String.join(", ", locales.subList(0, Math.min(8, locales.size())))).append('\n');
        return sb.toString();
    }

    private static String prompt(String model) {
        return "You are a senior manual QA engineer writing an end-to-end manual test case sheet for the "
                + "Android app modeled below. Write REAL, application-specific test cases — grounded strictly "
                + "in the screens, controls, features, permissions and facts given. Follow these rules:\n"
                + "- Cover the app end-to-end, logically sequenced from the first screen to the last reachable one.\n"
                + "- Use the app's REAL control labels and screens in the steps (e.g. tap the actual button names given).\n"
                + "- Include positive, negative, boundary, validation and edge-case scenarios ONLY where they genuinely "
                + "apply to a feature the app actually has.\n"
                + "- Do NOT invent modules or features not evidenced above (no generic Offline/Localization/Performance/"
                + "Accessibility sections unless the model clearly shows the app has them).\n"
                + "- Every case must be unique — no duplicate or near-duplicate scenarios.\n"
                + "- Each case: a real Module, Feature, Scenario, numbered Steps to Execute, and a concrete Expected Result.\n\n"
                + "APP MODEL:\n" + model + "\n\n"
                + "Respond with STRICT JSON ONLY — no markdown, no commentary — an array of objects with keys: "
                + "\"module\",\"feature\",\"scenario\",\"steps\" (a single string; number the steps with newlines), "
                + "\"expected\",\"priority\" (P1|P2|P3),\"type\" (Positive|Negative|Boundary|Validation|Edge Case). "
                + "Return between 15 and 120 cases depending on the app's real size. Format: {\"cases\":[ ... ]}";
    }

    private List<GhCase> parseCases(String text) {
        List<GhCase> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        try {
            String cleaned = text.trim();
            if (cleaned.startsWith("```")) {
                int nl = cleaned.indexOf('\n');
                int fence = cleaned.lastIndexOf("```");
                if (nl > 0 && fence > nl) cleaned = cleaned.substring(nl + 1, fence).trim();
            }
            JsonNode root = mapper.readTree(cleaned);
            JsonNode arr = root.has("cases") ? root.path("cases") : root;
            if (!arr.isArray()) return out;
            for (JsonNode c : arr) {
                String scenario = c.path("scenario").asText("").trim();
                if (scenario.isEmpty()) continue;
                out.add(new GhCase(
                        c.path("module").asText("General").trim(),
                        c.path("feature").asText("").trim(),
                        scenario,
                        c.path("steps").asText("").trim(),
                        c.path("expected").asText("").trim(),
                        c.path("priority").asText("P2").trim(),
                        c.path("type").asText("Positive").trim()));
            }
        } catch (Exception e) {
            log.debug("LLM test-case generator: could not parse response as JSON: {}", e.toString());
        }
        return out;
    }

    private static String label(Widget w) {
        if (w.text() != null && !w.text().isBlank()) return w.text().trim();
        if (w.contentDesc() != null && !w.contentDesc().isBlank()) return w.contentDesc().trim();
        return "";
    }

    private static String simple(String activity) {
        if (activity == null || activity.isBlank()) return "Screen";
        int idx = Math.max(activity.lastIndexOf('.'), activity.lastIndexOf('$'));
        return idx >= 0 ? activity.substring(idx + 1) : activity;
    }

    private static String nz(String v, String fb) { return v == null || v.isBlank() ? fb : v; }
    private static String trim(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }
}
