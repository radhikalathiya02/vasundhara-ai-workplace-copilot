package com.vasundhara.atf.aiproduct;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class QueueService {

    private static final int AVG_WINDOW = 10;
    private static final int DEFAULT_AVG_SECONDS = 600; // 10 min default estimate

    private final Deque<AiBuildJob> queue = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();

    // Rolling average of last 10 completed build durations
    private final Deque<Long> recentDurations = new ArrayDeque<>();
    private final AtomicLong totalDurationMs = new AtomicLong(0);

    public int enqueue(AiBuildJob job) {
        lock.lock();
        try {
            queue.addLast(job);
            int pos = queue.size();
            job.setQueuePosition(pos);
            job.setEstimatedWaitSeconds(getEstimatedWait(pos));
            return pos;
        } finally {
            lock.unlock();
        }
    }

    public AiBuildJob dequeue() {
        lock.lock();
        try {
            AiBuildJob job = queue.pollFirst();
            // Re-number remaining queue positions
            int pos = 1;
            for (AiBuildJob j : queue) {
                j.setQueuePosition(pos++);
                j.setEstimatedWaitSeconds(getEstimatedWait(j.getQueuePosition()));
            }
            return job;
        } finally {
            lock.unlock();
        }
    }

    public void recordCompletedBuild(long durationMs) {
        lock.lock();
        try {
            recentDurations.addLast(durationMs);
            totalDurationMs.addAndGet(durationMs);
            if (recentDurations.size() > AVG_WINDOW) {
                totalDurationMs.addAndGet(-recentDurations.pollFirst());
            }
        } finally {
            lock.unlock();
        }
    }

    public int getEstimatedWait(int position) {
        int avgSeconds = getAvgBuildSeconds();
        return position * avgSeconds;
    }

    public int getAvgBuildSeconds() {
        if (recentDurations.isEmpty()) return DEFAULT_AVG_SECONDS;
        return (int) (totalDurationMs.get() / recentDurations.size() / 1000);
    }

    public List<QueueEntry> getQueueStatus() {
        lock.lock();
        try {
            List<QueueEntry> entries = new ArrayList<>();
            int pos = 1;
            for (AiBuildJob job : queue) {
                entries.add(new QueueEntry(job, pos++));
            }
            return entries;
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return queue.size();
    }
}
