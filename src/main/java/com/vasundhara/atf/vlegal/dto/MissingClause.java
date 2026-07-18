package com.vasundhara.atf.vlegal.dto;

public class MissingClause {

    private String clause;
    private String severity; // LOW / MEDIUM / HIGH / CRITICAL
    private String description;

    public MissingClause() {}

    public MissingClause(String clause, String severity, String description) {
        this.clause = clause;
        this.severity = severity;
        this.description = description;
    }

    public String getClause() { return clause; }
    public void setClause(String clause) { this.clause = clause; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
