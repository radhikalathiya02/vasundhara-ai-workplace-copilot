package com.vasundhara.atf.vlegal.dto;

import java.util.List;

public class LegalSection {

    private String heading;
    private String content;
    private List<LegalSection> subSections;

    public LegalSection() {}

    public LegalSection(String heading, String content) {
        this.heading = heading;
        this.content = content;
    }

    public String getHeading() { return heading; }
    public void setHeading(String heading) { this.heading = heading; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public List<LegalSection> getSubSections() { return subSections; }
    public void setSubSections(List<LegalSection> subSections) { this.subSections = subSections; }
}
