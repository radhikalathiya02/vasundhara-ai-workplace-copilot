package com.vasundhara.atf.compat;

import com.vasundhara.atf.db.service.SessionPersistenceService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory registry of compatibility-matrix sessions.
 *
 * <p>On startup, previously persisted sessions are restored from the database so a
 * completed run's report is still reachable after a server restart (mirrors {@code ReportStore}).
 */
@Component
public class CompatSessionStore {

    private static final Logger log = LoggerFactory.getLogger(CompatSessionStore.class);

    private final ConcurrentHashMap<String, CompatSession> sessions = new ConcurrentHashMap<>();
    private final SessionPersistenceService persistence;

    public CompatSessionStore(SessionPersistenceService persistence) {
        this.persistence = persistence;
    }

    @PostConstruct
    void restoreFromDatabase() {
        List<CompatSession> restored = persistence.loadSessions("compat", CompatSession.class);
        restored.forEach(s -> sessions.put(s.getId(), s));
        if (!restored.isEmpty()) log.info("Restored {} compatibility session(s) from database.", restored.size());
    }

    public void save(CompatSession s) {
        sessions.put(s.getId(), s);
        persistence.saveSession("compat", s);
    }
    public CompatSession get(String id) { return sessions.get(id); }

    public List<CompatSession> all() {
        return sessions.values().stream()
                .sorted(Comparator.comparingLong(CompatSession::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }
}
