package com.vasundhara.atf.vlegal.dto;

import java.util.List;

public class DocumentResponse {

    private String title;
    private List<LegalSection> sections;
    private List<String> complianceTags;
    private int wordCount;
    private String docType; // PRIVACY_POLICY or TERMS_OF_SERVICE
    private String error;   // non-null when generation failed

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public List<LegalSection> getSections() { return sections; }
    public void setSections(List<LegalSection> sections) { this.sections = sections; }
    public List<String> getComplianceTags() { return complianceTags; }
    public void setComplianceTags(List<String> complianceTags) { this.complianceTags = complianceTags; }
    public int getWordCount() { return wordCount; }
    public void setWordCount(int wordCount) { this.wordCount = wordCount; }
    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
