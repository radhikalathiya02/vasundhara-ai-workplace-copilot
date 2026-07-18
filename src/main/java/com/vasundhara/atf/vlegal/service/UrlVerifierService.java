package com.vasundhara.atf.vlegal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vasundhara.atf.vlegal.config.VLegalProperties;
import com.vasundhara.atf.vlegal.dto.MissingClause;
import com.vasundhara.atf.vlegal.dto.VerifyUrlRequest;
import com.vasundhara.atf.vlegal.dto.VerifyUrlResponse;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class UrlVerifierService {

    private static final Logger log = LoggerFactory.getLogger(UrlVerifierService.class);

    private final GeminiClient gemini;
    private final PromptBuilder promptBuilder;
    private final ObjectMapper mapper;
    private final VLegalProperties props;

    public UrlVerifierService(GeminiClient gemini, PromptBuilder promptBuilder,
                              ObjectMapper mapper, VLegalProperties props) {
        this.gemini        = gemini;
        this.promptBuilder = promptBuilder;
        this.mapper        = mapper;
        this.props         = props;
    }

    public VerifyUrlResponse verify(VerifyUrlRequest req) {
        VerifyUrlResponse response = new VerifyUrlResponse();
        String content;
        String sourceDesc;

        // Try URL first, fall back to rawText
        if (StringUtils.hasText(req.getUrl())) {
            try {
                content    = fetchUrl(req.getUrl());
                sourceDesc = "URL: " + req.getUrl();
                response.setAnalysisSource("URL");
            } catch (Exception ex) {
                log.warn("URL fetch failed for {}: {} — falling back to rawText", req.getUrl(), ex.getMessage());
                if (StringUtils.hasText(req.getRawText())) {
                    content    = req.getRawText();
                    sourceDesc = "RAW_TEXT (URL fetch failed: " + ex.getMessage() + ")";
                    response.setAnalysisSource("RAW_TEXT");
                } else {
                    response.setError("Failed to fetch URL and no raw text provided: " + ex.getMessage());
                    return response;
                }
            }
        } else if (StringUtils.hasText(req.getRawText())) {
            content    = req.getRawText();
            sourceDesc = "RAW_TEXT";
            response.setAnalysisSource("RAW_TEXT");
        } else {
            response.setError("Provide either a URL or paste the document text.");
            return response;
        }

        try {
            String prompt = promptBuilder.buildVerifyPrompt(content, sourceDesc);
            String json   = gemini.generate(prompt);
            JsonNode root = mapper.readTree(json);

            response.setComplianceScore(root.path("complianceScore").asInt(0));
            response.setRiskLevel(root.path("riskLevel").asText("UNKNOWN"));
            response.setDocumentType(root.path("documentType").asText("Unknown"));
            response.setPresentClauses(parseStringList(root.path("presentClauses")));
            response.setMissingClauses(parseMissingClauses(root.path("missingClauses")));
            response.setRecommendedFixes(parseStringList(root.path("recommendedFixes")));

        } catch (Exception ex) {
            log.error("Verification failed: {}", ex.getMessage());
            response.setError("Verification failed: " + ex.getMessage());
        }

        return response;
    }

    private String fetchUrl(String url) throws IOException {
        VLegalProperties.UrlFetch cfg = props.getUrlFetch();
        Document doc = Jsoup.connect(url)
                .userAgent(cfg.getUserAgent())
                .timeout(cfg.getTimeoutSeconds() * 1000)
                .followRedirects(true)
                .get();
        return doc.body().text();
    }

    private List<String> parseStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> list.add(n.asText()));
        }
        return list;
    }

    private List<MissingClause> parseMissingClauses(JsonNode node) {
        List<MissingClause> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) {
                MissingClause mc = new MissingClause(
                    n.path("clause").asText(""),
                    n.path("severity").asText("MEDIUM"),
                    n.path("description").asText("")
                );
                list.add(mc);
            }
        }
        return list;
    }
}
