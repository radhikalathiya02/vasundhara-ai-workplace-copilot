package com.vasundhara.atf.remoteconfig;

import com.vasundhara.atf.db.service.SessionPersistenceService;
import com.vasundhara.atf.model.RemoteConfigSession;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory registry of all Remote Config sessions created during this server's
 * lifetime. Thread-safe via {@link ConcurrentHashMap}.
 *
 * <p>On startup, previously persisted sessions are restored from the database so a
 * completed run's report is still reachable after a server restart (mirrors {@code ReportStore}).
 */
@Component
public class RemoteConfigSessionStore {

    private static final Logger log = LoggerFactory.getLogger(RemoteConfigSessionStore.class);

    private final ConcurrentHashMap<String, RemoteConfigSession> sessions = new ConcurrentHashMap<>();
    private final SessionPersistenceService persistence;

    public RemoteConfigSessionStore(SessionPersistenceService persistence) {
        this.persistence = persistence;
    }

    @PostConstruct
    void restoreFromDatabase() {
        List<RemoteConfigSession> restored = persistence.loadSessions("rc", RemoteConfigSession.class);
        restored.forEach(s -> sessions.put(s.getId(), s));
        if (!restored.isEmpty()) log.info("Restored {} remote config session(s) from database.", restored.size());
    }

    public void save(RemoteConfigSession session) {
        sessions.put(session.getId(), session);
        persistence.saveSession("rc", session);
    }

    public RemoteConfigSession get(String id) {
        return sessions.get(id);
    }

    /** All sessions, newest first by creation time. */
    public List<RemoteConfigSession> all() {
        return sessions.values().stream()
                .sorted(Comparator.comparingLong(RemoteConfigSession::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }
}
