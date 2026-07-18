package com.vasundhara.atf.db.entity;

import com.vasundhara.atf.model.Severity;
import jakarta.persistence.*;

@Entity
@Table(name = "findings")
public class FindingEntity {

    @Id
    @Column(name = "finding_id", length = 36)
    private String findingId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "result_id", nullable = false)
    private TestResultEntity testResult;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Severity severity;

    @Column(length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(length = 500)
    private String evidence;

    @Column(name = "ts_millis")
    private long tsMillis;

    @Column(name = "occurrence_count")
    private int occurrenceCount;

    public FindingEntity() {}

    public FindingEntity(String findingId) {
        this.findingId = findingId;
    }

    public String getFindingId() { return findingId; }
    public void setFindingId(String findingId) { this.findingId = findingId; }

    public TestResultEntity getTestResult() { return testResult; }
    public void setTestResult(TestResultEntity testResult) { this.testResult = testResult; }

    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }

    public long getTsMillis() { return tsMillis; }
    public void setTsMillis(long tsMillis) { this.tsMillis = tsMillis; }

    public int getOccurrenceCount() { return occurrenceCount; }
    public void setOccurrenceCount(int occurrenceCount) { this.occurrenceCount = occurrenceCount; }
}
