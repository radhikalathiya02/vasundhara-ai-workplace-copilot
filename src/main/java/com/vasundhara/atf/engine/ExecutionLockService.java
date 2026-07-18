package com.vasundhara.atf.engine;

import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Single-execution lock: only one test run (any module) may be active at a time.
 * Acquire via {@link #tryAcquire(String, String)} before starting an async runner;
 * release via {@link #release(String)} in the runner's {@code finally} block.
 */
@Service
public class ExecutionLockService {

    public record LockInfo(String module, String id) {}

    private volatile LockInfo holder;

    /** Acquire the lock. Returns {@code true} on success, {@code false} if already held. */
    public synchronized boolean tryAcquire(String module, String id) {
        if (holder != null) return false;
        holder = new LockInfo(module, id);
        return true;
    }

    /** Release the lock if the given id is the current holder. No-op if already released. */
    public synchronized void release(String id) {
        if (holder != null && holder.id().equals(id)) holder = null;
    }

    /** Current lock holder, or empty if idle. */
    public synchronized Optional<LockInfo> getLockInfo() {
        return Optional.ofNullable(holder);
    }
}
