package com.campusfoodcourt.campus_foodcourt.auth.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory fixed-window counter: each key may make {@code maxRequests} requests per window, where a key's
 * window starts at its first request. Thread-safe.
 * <ul>
 *   <li><b>Atomic:</b> the check and the increment happen inside one {@link ConcurrentHashMap#compute}, so
 *       concurrent requests for the same key can never exceed the limit.</li>
 *   <li><b>Bounded memory:</b> at most {@code maxTrackedKeys} keys are held (enforced exactly, not
 *       approximately). Expired windows are purged once per window period and whenever the table is full.
 *       If the table is still full of live windows, a <i>new</i> key is rejected (fail closed) while already
 *       tracked keys keep being served normally.</li>
 * </ul>
 * State lives only in this JVM: it is lost on restart and not shared between application instances.
 */
public final class FixedWindowRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(FixedWindowRateLimiter.class);

    /** Outcome of one request. {@code retryAfterSeconds} is 0 when allowed and at least 1 when rejected. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);

        static Decision rejected(long retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds);
        }
    }

    private record Window(long startMillis, int count) {
    }

    private final String name;
    private final int maxRequests;
    private final long windowMillis;
    private final int maxTrackedKeys;
    private final Clock clock;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger trackedKeys = new AtomicInteger();
    private final AtomicLong nextCleanupAtMillis;
    private final AtomicLong nextFullWarningAtMillis = new AtomicLong(Long.MIN_VALUE);

    public FixedWindowRateLimiter(String name, int maxRequests, Duration window, int maxTrackedKeys, Clock clock) {
        if (maxRequests < 1) {
            throw new IllegalArgumentException("maxRequests must be at least 1");
        }
        if (window == null || window.toMillis() < 1) {
            throw new IllegalArgumentException("window must be at least 1 ms");
        }
        if (maxTrackedKeys < 1) {
            throw new IllegalArgumentException("maxTrackedKeys must be at least 1");
        }
        this.name = Objects.requireNonNull(name);
        this.maxRequests = maxRequests;
        this.windowMillis = window.toMillis();
        this.maxTrackedKeys = maxTrackedKeys;
        this.clock = Objects.requireNonNull(clock);
        this.nextCleanupAtMillis = new AtomicLong(clock.millis() + windowMillis);
    }

    /** Counts one request for {@code key} and says whether it may proceed. */
    public Decision tryAcquire(String key) {
        Objects.requireNonNull(key);
        long now = clock.millis();
        purgeIfDue(now);

        Decision decision = attempt(key, now);
        if (decision != null) {
            return decision;
        }
        // Table full: make room by dropping expired windows, then try once more.
        long earliestExpiry = purgeExpired(now);
        decision = attempt(key, now);
        if (decision != null) {
            return decision;
        }
        warnTableFull(now);
        return Decision.rejected(secondsUntil(earliestExpiry, now));
    }

    /** Number of keys currently held in memory. */
    public int trackedKeys() {
        return trackedKeys.get();
    }

    /** Returns null only when {@code key} is new and the table is at capacity. */
    private Decision attempt(String key, long now) {
        Decision[] result = new Decision[1];
        windows.compute(key, (k, current) -> {
            if (current == null) {
                if (trackedKeys.incrementAndGet() > maxTrackedKeys) {
                    trackedKeys.decrementAndGet();
                    return null; // leave the key absent
                }
                result[0] = Decision.ALLOWED;
                return new Window(now, 1);
            }
            long end = current.startMillis() + windowMillis;
            if (now >= end) {
                result[0] = Decision.ALLOWED;
                return new Window(now, 1);
            }
            if (current.count() < maxRequests) {
                result[0] = Decision.ALLOWED;
                return new Window(current.startMillis(), current.count() + 1);
            }
            result[0] = Decision.rejected(secondsUntil(end, now));
            return current; // count is not increased further, so it cannot overflow
        });
        return result[0];
    }

    private void purgeIfDue(long now) {
        long due = nextCleanupAtMillis.get();
        if (now >= due && nextCleanupAtMillis.compareAndSet(due, now + windowMillis)) {
            purgeExpired(now);
        }
    }

    /**
     * Removes every expired window and returns the earliest end time among the windows that remain
     * (or {@code now + window} if none remain).
     */
    long purgeExpired(long now) {
        long earliestEnd = Long.MAX_VALUE;
        for (Map.Entry<String, Window> entry : windows.entrySet()) {
            Window window = entry.getValue();
            long end = window.startMillis() + windowMillis;
            if (now >= end) {
                // Conditional remove: skipped if a concurrent request has just replaced this window.
                if (windows.remove(entry.getKey(), window)) {
                    trackedKeys.decrementAndGet();
                }
            } else {
                earliestEnd = Math.min(earliestEnd, end);
            }
        }
        return earliestEnd == Long.MAX_VALUE ? now + windowMillis : earliestEnd;
    }

    private void warnTableFull(long now) {
        long next = nextFullWarningAtMillis.get();
        if (now >= next && nextFullWarningAtMillis.compareAndSet(next, now + windowMillis)) {
            log.warn("Rate limiter '{}' is tracking its maximum of {} clients; new clients are rejected until "
                    + "windows expire (possible distributed attack, or app.auth.rate-limit.max-tracked-clients "
                    + "is too low)", name, maxTrackedKeys);
        }
    }

    private static long secondsUntil(long endMillis, long now) {
        long millis = Math.max(0, endMillis - now);
        return Math.max(1, (millis + 999) / 1000); // round up; Retry-After is never 0
    }
}
