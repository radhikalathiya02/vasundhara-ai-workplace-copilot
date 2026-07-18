package com.vasundhara.atf.db.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vasundhara.atf.db.entity.ModuleSessionEntity;
import com.vasundhara.atf.db.repository.ModuleSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists any module session (compat, l10n, rc, ads, tc) as a JSON snapshot.
 * Called from session stores as a non-blocking side-effect — failures are logged
 * at DEBUG level and never propagate to callers.
 *
 * <p>Sessions are stored with their ID as the primary key, so calling
 * {@code saveSession()} repeatedly for the same session is idempotent (upsert).
 */
@Service
public class SessionPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(SessionPersistenceService.class);

    private final ModuleSessionRepository repo;
    private final ObjectMapper mapper;

    public SessionPersistenceService(ModuleSessionRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    /**
     * Serialize {@code session} to JSON and upsert into {@code module_sessions}.
     * The session object must have a public {@code getId()} method.
     *
     * @param module  one of 'compat', 'l10n', 'rc', 'ads', 'tc'
     * @param session the live session object
     */
    @Transactional
    public void saveSession(String module, Object session) {
        try {
            String id = (String) session.getClass().getMethod("getId").invoke(session);
            if (id == null || id.isBlank()) return;

            ModuleSessionEntity entity = repo.findById(id).orElse(new ModuleSessionEntity(id, module));
            entity.setModule(module);

            // Optional fields — tolerate missing methods gracefully
            trySet(session, "getState",       v -> entity.setState(v.toString()));
            trySet(session, "getApkFileName", v -> entity.setApkFileName(v.toString()));
            trySet(session, "getCreatedAt",   v -> entity.setCreatedAt(toLong(v)));

            entity.setUpdatedAt(System.currentTimeMillis());
            entity.setDataJson(mapper.writeValueAsString(session));
            repo.save(entity);
        } catch (Exception e) {
            log.debug("Session persistence skipped for module={}: {}", module, e.getMessage());
        }
    }

    /**
     * Load every persisted session for {@code module} back into its concrete type, so a module's
     * session store can repopulate its in-memory cache on startup. Each row's {@code dataJson}
     * (a full Jackson snapshot written by {@link #saveSession}) is deserialized independently —
     * one corrupt/incompatible row is skipped rather than failing the whole restore, since a
     * session lost this way just means that one run's report is unavailable, not a hard failure.
     *
     * @param module one of 'compat', 'l10n', 'rc', 'ads', 'tc'
     * @param type   the concrete session class saveSession() was called with for this module
     */
    @Transactional(readOnly = true)
    public <T> List<T> loadSessions(String module, Class<T> type) {
        List<T> out = new ArrayList<>();
        try {
            for (ModuleSessionEntity e : repo.findByModuleOrderByCreatedAtDesc(module)) {
                try {
                    if (e.getDataJson() != null) out.add(mapper.readValue(e.getDataJson(), type));
                } catch (Exception ex) {
                    log.debug("Skipping unrestorable {} session {}: {}", module, e.getId(), ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Could not restore {} sessions from database: {}", module, e.getMessage());
        }
        return out;
    }

    private void trySet(Object session, String methodName, java.util.function.Consumer<Object> setter) {
        try {
            Object value = session.getClass().getMethod(methodName).invoke(session);
            if (value != null) setter.accept(value);
        } catch (Exception ignored) {}
    }

    private Long toLong(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        return null;
    }
}
