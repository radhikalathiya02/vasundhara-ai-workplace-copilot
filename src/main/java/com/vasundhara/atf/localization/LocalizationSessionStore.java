package com.vasundhara.atf.localization;

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
 * In-memory registry of localization sessions.
 *
 * <p>On startup, previously persisted sessions are restored from the database so a
 * completed run's report is still reachable after a server restart (mirrors {@code ReportStore}).
 */
@Component
public class LocalizationSessionStore {

    private static final Logger log = LoggerFactory.getLogger(LocalizationSessionStore.class);

    private final ConcurrentHashMap<String, LocalizationSession> sessions = new ConcurrentHashMap<>();
    private final SessionPersistenceService persistence;

    public LocalizationSessionStore(SessionPersistenceService persistence) {
        this.persistence = persistence;
    }

    @PostConstruct
    void restoreFromDatabase() {
        List<LocalizationSession> restored = persistence.loadSessions("l10n", LocalizationSession.class);
        restored.forEach(s -> sessions.put(s.getId(), s));
        if (!restored.isEmpty()) log.info("Restored {} localization session(s) from database.", restored.size());
    }

    public void save(LocalizationSession s) {
        sessions.put(s.getId(), s);
        persistence.saveSession("l10n", s);
    }
    public LocalizationSession get(String id) { return sessions.get(id); }

    public List<LocalizationSession> all() {
        return sessions.values().stream()
                .sorted(Comparator.comparingLong(LocalizationSession::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }
}
