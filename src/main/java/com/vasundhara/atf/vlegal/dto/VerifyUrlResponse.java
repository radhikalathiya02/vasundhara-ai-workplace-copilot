package com.vasundhara.atf.vlegal.dto;

import java.util.List;

public class VerifyUrlResponse {

    private int complianceScore;          // 0-100
    private String riskLevel;             // LOW / MEDIUM / HIGH
    private String documentType;          // auto-detected: Privacy Policy / Terms of Service / Unknown
    private String analysisSource;        // URL or RAW_TEXT
    private List<String> presentClauses;
    private List<MissingClause> missingClauses;
    private List<String> recommendedFixes;
    private String error;

    public int getComplianceScore() { return complianceScore; }
    public void setComplianceScore(int complianceScore) { this.complianceScore = complianceScore; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }
    public String getAnalysisSource() { return analysisSource; }
    public void setAnalysisSource(String analysisSource) { this.analysisSource = analysisSource; }
    public List<String> getPresentClauses() { return presentClauses; }
    public void setPresentClauses(List<String> presentClauses) { this.presentClauses = presentClauses; }
    public List<MissingClause> getMissingClauses() { return missingClauses; }
    public void setMissingClauses(List<MissingClause> missingClauses) { this.missingClauses = missingClauses; }
    public List<String> getRecommendedFixes() { return recommendedFixes; }
    public void setRecommendedFixes(List<String> recommendedFixes) { this.recommendedFixes = recommendedFixes; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
