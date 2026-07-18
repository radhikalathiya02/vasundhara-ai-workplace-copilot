package com.vasundhara.atf.webtest;

import com.vasundhara.atf.webtest.model.WebScanSession;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of website scan sessions — isolated from the Android run history and the
 * database, so the Website Testing module can never affect existing functionality. Newest-first
 * listing powers the module's own scan history view.
 */
@Component
public class WebScanSessionStore {

    private final ConcurrentHashMap<String, WebScanSession> sessions = new ConcurrentHashMap<>();

    public void save(WebScanSession session) { sessions.put(session.getId(), session); }

    public WebScanSession get(String id) { return sessions.get(id); }

    public void delete(String id) { sessions.remove(id); }

    /** All sessions, newest first. */
    public List<WebScanSession> all() {
        return sessions.values().stream()
                .sorted(Comparator.comparingLong(WebScanSession::getCreatedAtMillis).reversed())
                .toList();
    }
}
