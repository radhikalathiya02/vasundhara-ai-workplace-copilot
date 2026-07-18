package com.vasundhara.atf.analysis;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/** In-memory registry of "Analyze APK" sessions — short-lived, not persisted to the database. */
@Component
public class AnalysisSessionStore {

    private final ConcurrentHashMap<String, AnalysisSession> sessions = new ConcurrentHashMap<>();

    public void save(AnalysisSession session) { sessions.put(session.getId(), session); }

    public AnalysisSession get(String id) { return sessions.get(id); }
}
