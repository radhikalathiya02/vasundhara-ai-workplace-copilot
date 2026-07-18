package com.vasundhara.atf.testcase;

import java.util.ArrayList;
import java.util.List;

/** A parsed test case with all its steps, metadata, and quality warnings. */
public class TcItem {

    private String id        = "";
    private String module    = "";
    private String feature   = "";
    private String name      = "";
    private String priority  = "MEDIUM"; // HIGH | MEDIUM | LOW
    private String preconditions = "";
    private List<TcStep>  steps    = new ArrayList<>();
    private boolean duplicate;
    private String  duplicateOf;
    private List<String> warnings = new ArrayList<>(); // ambiguous steps, missing expected results

    public String getId()            { return id; }
    public void   setId(String v)    { this.id = v == null ? "" : v.trim(); }

    public String getModule()           { return module; }
    public void   setModule(String v)   { this.module = v == null ? "" : v.trim(); }

    public String getFeature()          { return feature; }
    public void   setFeature(String v)  { this.feature = v == null ? "" : v.trim(); }

    public String getName()             { return name; }
    public void   setName(String v)     { this.name = v == null ? "" : v.trim(); }

    public String getPriority()         { return priority; }
    public void   setPriority(String v) {
        if (v == null || v.isBlank()) { this.priority = "MEDIUM"; return; }
        String u = v.trim().toUpperCase();
        this.priority = u.startsWith("H") ? "HIGH" : u.startsWith("L") ? "LOW" : "MEDIUM";
    }

    public String getPreconditions()          { return preconditions; }
    public void   setPreconditions(String v)  { this.preconditions = v == null ? "" : v.trim(); }

    public List<TcStep> getSteps()            { return steps; }
    public void         setSteps(List<TcStep> v) { this.steps = v; }

    public boolean isDuplicate()             { return duplicate; }
    public void    setDuplicate(boolean v)   { this.duplicate = v; }

    public String getDuplicateOf()           { return duplicateOf; }
    public void   setDuplicateOf(String v)   { this.duplicateOf = v; }

    public List<String> getWarnings()            { return warnings; }
    public void         setWarnings(List<String> v) { this.warnings = v; }
    public void         addWarning(String w)     { warnings.add(w); }

    /** Stable key used for duplicate detection — normalised name + step digest. */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder(name.toLowerCase().replaceAll("\\s+", " ").trim());
        for (TcStep s : steps) sb.append('|').append(s.description().toLowerCase().trim());
        return sb.toString();
    }
}
