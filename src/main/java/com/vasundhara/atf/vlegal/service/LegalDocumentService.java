package com.vasundhara.atf.vlegal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vasundhara.atf.vlegal.dto.DocumentRequest;
import com.vasundhara.atf.vlegal.dto.DocumentResponse;
import com.vasundhara.atf.vlegal.dto.LegalSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class LegalDocumentService {

    private static final Logger log = LoggerFactory.getLogger(LegalDocumentService.class);

    private final GeminiClient gemini;
    private final PromptBuilder promptBuilder;
    private final ObjectMapper mapper;

    public LegalDocumentService(GeminiClient gemini, PromptBuilder promptBuilder, ObjectMapper mapper) {
        this.gemini        = gemini;
        this.promptBuilder = promptBuilder;
        this.mapper        = mapper;
    }

    public DocumentResponse generatePrivacyPolicy(DocumentRequest req) {
        String prompt = promptBuilder.buildPrivacyPolicyPrompt(req);
        return generate(req, prompt, "PRIVACY_POLICY");
    }

    public DocumentResponse generateTermsOfService(DocumentRequest req) {
        String prompt = promptBuilder.buildTermsOfServicePrompt(req);
        return generate(req, prompt, "TERMS_OF_SERVICE");
    }

    private DocumentResponse generate(DocumentRequest req, String prompt, String docType) {
        DocumentResponse response = new DocumentResponse();
        response.setDocType(docType);

        try {
            String json = gemini.generate(prompt);
            JsonNode root = mapper.readTree(json);

            response.setTitle(root.path("title").asText(req.getAppName() + " " + docType.replace("_", " ")));
            response.setWordCount(root.path("wordCount").asInt(0));
            response.setSections(parseSections(root.path("sections")));
            response.setComplianceTags(buildComplianceTags(req));

        } catch (Exception ex) {
            log.error("Document generation failed for {}: {}", req.getAppName(), ex.getMessage());
            response.setError("Generation failed: " + ex.getMessage());
        }

        return response;
    }

    private List<LegalSection> parseSections(JsonNode sectionsNode) {
        List<LegalSection> result = new ArrayList<>();
        if (sectionsNode == null || !sectionsNode.isArray()) return result;

        for (JsonNode node : sectionsNode) {
            LegalSection section = new LegalSection();
            section.setHeading(node.path("heading").asText(""));
            section.setContent(node.path("content").asText(""));

            JsonNode sub = node.path("subSections");
            if (sub.isArray() && !sub.isEmpty()) {
                section.setSubSections(parseSections(sub));
            }
            result.add(section);
        }
        return result;
    }

    private List<String> buildComplianceTags(DocumentRequest req) {
        Set<String> tags = new LinkedHashSet<>();

        // Always include base Indian IT Act
        tags.add("IT_Act_India_2000");

        if (req.getTargetRegions().contains("India")) tags.add("DPDP_India");
        if (req.getTargetRegions().contains("EU"))    tags.add("GDPR");
        if (req.getTargetRegions().contains("USA"))   tags.add("CCPA");

        // COPPA if app can be used by children under 13
        if ("All Ages".equals(req.getTargetAgeGroup())) tags.add("COPPA");

        if (req.isHasInAppPurchases())               tags.add("PCI_DSS");
        if (req.getDataCollected().contains("Health")) tags.add("HIPAA_Aware");
        if (req.isHasAds())                           tags.add("AdMob_Policy");

        return new ArrayList<>(tags);
    }
}
