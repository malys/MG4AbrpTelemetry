package com.mg4.abrptelemetry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Rolling record of the last {@link #MAX_ENTRIES} upload attempts, and the service health
 * derived from them.
 *
 * Process-local on purpose: this is a diagnostic view, not persisted state. Written from
 * the scheduler thread and read from the UI thread, so every access is synchronized.
 */
final class UploadLog {

    static final int MAX_ENTRIES = 20;

    /** Consecutive failures that flip the service into the error state. */
    static final int FAILURES_FOR_ERROR = 3;

    enum State {
        /** Service is off. */
        STOPPED,
        /** Running, no upload attempted yet. */
        STARTING,
        /** Running and the recent uploads are going through. */
        RUNNING,
        /** Running but the last {@link #FAILURES_FOR_ERROR} attempts failed. */
        ERROR
    }

    /** One upload attempt. */
    static final class Entry {
        final long timestampMs;
        /** HTTP status, or 0 when the request never got a response (offline, timeout). */
        final int httpStatus;
        final boolean success;
        /** Short human-readable reason, e.g. "OK", "No internet", "HTTP 401". */
        final String detail;

        Entry(long timestampMs, int httpStatus, boolean success, String detail) {
            this.timestampMs = timestampMs;
            this.httpStatus = httpStatus;
            this.success = success;
            this.detail = detail;
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>(MAX_ENTRIES);
    private final Object lock = new Object();

    void record(Entry entry) {
        synchronized (lock) {
            while (entries.size() >= MAX_ENTRIES) entries.removeFirst();
            entries.addLast(entry);
        }
    }

    void clear() {
        synchronized (lock) { entries.clear(); }
    }

    /** Most recent first — that is the order the UI shows them in. */
    List<Entry> recent() {
        synchronized (lock) {
            List<Entry> copy = new ArrayList<>(entries);
            java.util.Collections.reverse(copy);
            return copy;
        }
    }

    Entry latest() {
        synchronized (lock) {
            return entries.isEmpty() ? null : entries.peekLast();
        }
    }

    /**
     * Number of failures at the end of the log. Reset by any success, so a single
     * recovered upload clears the error state.
     */
    int consecutiveFailures() {
        synchronized (lock) {
            int count = 0;
            java.util.Iterator<Entry> it = entries.descendingIterator();
            while (it.hasNext()) {
                if (it.next().success) break;
                count++;
            }
            return count;
        }
    }

    /**
     * Service state.
     *
     * Needs [running] from the service itself: an empty log means "not started yet" while
     * running, but means nothing at all when stopped.
     */
    State state(boolean running) {
        if (!running) return State.STOPPED;
        synchronized (lock) {
            if (entries.isEmpty()) return State.STARTING;
        }
        return consecutiveFailures() >= FAILURES_FOR_ERROR ? State.ERROR : State.RUNNING;
    }
}
