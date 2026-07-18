package com.vasundhara.atf.ads;

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
 * In-memory registry of Ad Analysis sessions. Thread-safe via ConcurrentHashMap.
 *
 * <p>On startup, previously persisted sessions are restored from the database so a
 * completed run's report is still reachable after a server restart (mirrors {@code ReportStore}).
 */
@Component
public class AdAnalysisSessionStore {

    private static final Logger log = LoggerFactory.getLogger(AdAnalysisSessionStore.class);

    private final ConcurrentHashMap<String, AdAnalysisSession> sessions = new ConcurrentHashMap<>();
    private final SessionPersistenceService persistence;

    public AdAnalysisSessionStore(SessionPersistenceService persistence) {
        this.persistence = persistence;
    }

    @PostConstruct
    void restoreFromDatabase() {
        List<AdAnalysisSession> restored = persistence.loadSessions("ads", AdAnalysisSession.class);
        restored.forEach(s -> sessions.put(s.getId(), s));
        if (!restored.isEmpty()) log.info("Restored {} ad analysis session(s) from database.", restored.size());
    }

    public void save(AdAnalysisSession session) {
        sessions.put(session.getId(), session);
        persistence.saveSession("ads", session);
    }

    public AdAnalysisSession get(String id) { return sessions.get(id); }

    public List<AdAnalysisSession> all() {
        return sessions.values().stream()
                .sorted(Comparator.comparingLong(AdAnalysisSession::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }
}
