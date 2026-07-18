package com.vasundhara.atf.smartexec;

import com.vasundhara.atf.db.service.SessionPersistenceService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** In-memory + DB-backed registry of Smart Execution sessions. Mirrors AdAnalysisSessionStore. */
@Component
public class SmartSessionStore {

    private static final Logger log = LoggerFactory.getLogger(SmartSessionStore.class);

    private final ConcurrentHashMap<String, SmartSession> sessions = new ConcurrentHashMap<>();
    private final SessionPersistenceService persistence;

    public SmartSessionStore(SessionPersistenceService persistence) {
        this.persistence = persistence;
    }

    @PostConstruct
    void restoreFromDatabase() {
        List<SmartSession> restored = persistence.loadSessions("smartexec", SmartSession.class);
        restored.forEach(s -> sessions.put(s.getId(), s));
        if (!restored.isEmpty()) log.info("Restored {} Smart Execution session(s) from database.", restored.size());
    }

    public void save(SmartSession session) {
        sessions.put(session.getId(), session);
        persistence.saveSession("smartexec", session);
    }

    public SmartSession get(String id) { return sessions.get(id); }

    public List<SmartSession> all() {
        return sessions.values().stream()
                .sorted(Comparator.comparingLong(SmartSession::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }

    /** Most recent COMPLETED session for the same package, excluding {@code excludeId} — used by the Regression category. */
    public SmartSession findBaseline(String packageName, String excludeId) {
        if (packageName == null) return null;
        return sessions.values().stream()
                .filter(s -> packageName.equals(s.getPackageName()) && !s.getId().equals(excludeId)
                        && s.getState() == SmartSession.State.COMPLETED)
                .max(Comparator.comparingLong(SmartSession::getCreatedAt))
                .orElse(null);
    }
}
