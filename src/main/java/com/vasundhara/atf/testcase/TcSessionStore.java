package com.vasundhara.atf.testcase;

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
 * In-memory registry of Test Case Execution sessions. Thread-safe via ConcurrentHashMap.
 *
 * <p>On startup, previously persisted sessions are restored from the database so a
 * completed run's report is still reachable after a server restart (mirrors {@code ReportStore}).
 */
@Component
public class TcSessionStore {

    private static final Logger log = LoggerFactory.getLogger(TcSessionStore.class);

    private final ConcurrentHashMap<String, TcSession> sessions = new ConcurrentHashMap<>();
    private final SessionPersistenceService persistence;

    public TcSessionStore(SessionPersistenceService persistence) {
        this.persistence = persistence;
    }

    @PostConstruct
    void restoreFromDatabase() {
        List<TcSession> restored = persistence.loadSessions("tc", TcSession.class);
        restored.forEach(s -> sessions.put(s.getId(), s));
        if (!restored.isEmpty()) log.info("Restored {} test case session(s) from database.", restored.size());
    }

    public void save(TcSession s) {
        sessions.put(s.getId(), s);
        persistence.saveSession("tc", s);
    }
    public TcSession get(String id)  { return sessions.get(id); }

    public List<TcSession> all() {
        return sessions.values().stream()
                .sorted(Comparator.comparingLong(TcSession::getStartTime).reversed())
                .collect(Collectors.toList());
    }
}
