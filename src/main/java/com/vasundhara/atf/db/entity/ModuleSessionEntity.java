package com.vasundhara.atf.db.entity;

import jakarta.persistence.*;

/**
 * Stores a serialized snapshot of any module session (compat, l10n, rc, ads, tc).
 * The full session object is stored as JSON in {@code data_json} so schema evolution
 * of individual sessions doesn't require migration changes.
 */
@Entity
@Table(name = "module_sessions",
       indexes = @Index(name = "idx_module_sessions_module", columnList = "module"))
public class ModuleSessionEntity {

    @Id
    @Column(length = 36)
    private String id;

    /** Session type: 'compat', 'l10n', 'rc', 'ads', 'tc'. */
    @Column(length = 20, nullable = false)
    private String module;

    @Column(name = "apk_file_name", length = 255)
    private String apkFileName;

    @Column(length = 30)
    private String state;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    /** Full JSON serialization of the session object. */
    @Column(name = "data_json", columnDefinition = "TEXT")
    private String dataJson;

    public ModuleSessionEntity() {}

    public ModuleSessionEntity(String id, String module) {
        this.id = id;
        this.module = module;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getModule() { return module; }
    public void setModule(String module) { this.module = module; }

    public String getApkFileName() { return apkFileName; }
    public void setApkFileName(String apkFileName) { this.apkFileName = apkFileName; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }

    public String getDataJson() { return dataJson; }
    public void setDataJson(String dataJson) { this.dataJson = dataJson; }
}
