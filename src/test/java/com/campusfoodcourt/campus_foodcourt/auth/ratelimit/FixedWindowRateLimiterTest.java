package com.campusfoodcourt.campus_foodcourt.auth.ratelimit;

import com.campusfoodcourt.campus_foodcourt.auth.ratelimit.FixedWindowRateLimiter.Decision;
import com.campusfoodcourt.campus_foodcourt.support.MutableClock;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixedWindowRateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T10:00:00Z"));

    private FixedWindowRateLimiter limiter(int maxRequests, int maxTrackedKeys) {
        return new FixedWindowRateLimiter("test", maxRequests, WINDOW, maxTrackedKeys, clock);
    }

    // ------------------------------------------------------------------ allowed / blocked

    @Test
    void allowsRequestsWithinTheLimit() {
        FixedWindowRateLimiter limiter = limiter(3, 100);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("1.2.3.4")).isEqualTo(new Decision(true, 0));
        }
    }

    @Test
    void blocksRequestsOverTheLimitWithRetryAfterUntilTheWindowEnds() {
        FixedWindowRateLimiter limiter = limiter(3, 100);
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("1.2.3.4");
        }
        assertThat(limiter.tryAcquire("1.2.3.4")).isEqualTo(new Decision(false, 60));

        clock.advance(Duration.ofSeconds(45));
        assertThat(limiter.tryAcquire("1.2.3.4")).isEqualTo(new Decision(false, 15));
    }

    @Test
    void retryAfterIsRoundedUpAndNeverZero() {
        FixedWindowRateLimiter limiter = limiter(1, 100);
        limiter.tryAcquire("k");
        clock.advance(Duration.ofMillis(59_001)); // 999 ms left
        assertThat(limiter.tryAcquire("k").retryAfterSeconds()).isEqualTo(1);
        clock.advance(Duration.ofMillis(998));    // 1 ms left
        assertThat(limiter.tryAcquire("k")).isEqualTo(new Decision(false, 1));
    }

    @Test
    void rejectedRequestsDoNotExtendTheWindow() {
        FixedWindowRateLimiter limiter = limiter(1, 100);
        limiter.tryAcquire("k");
        for (int i = 0; i < 50; i++) {
            clock.advance(Duration.ofMillis(1_000));
            assertThat(limiter.tryAcquire("k").allowed()).isFalse();
        }
        clock.advance(Duration.ofSeconds(10)); // 60 s after the first request
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
    }

    @Test
    void keysAreCountedIndependently() {
        FixedWindowRateLimiter limiter = limiter(1, 100);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    // ------------------------------------------------------------------ reset

    @Test
    void counterResetsWhenTheWindowHasPassed() {
        FixedWindowRateLimiter limiter = limiter(2, 100);
        limiter.tryAcquire("k");
        limiter.tryAcquire("k");
        assertThat(limiter.tryAcquire("k").allowed()).isFalse();

        clock.advance(WINDOW.minusMillis(1));
        assertThat(limiter.tryAcquire("k").allowed()).as("1 ms before the window ends").isFalse();

        clock.advance(Duration.ofMillis(1));
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        assertThat(limiter.tryAcquire("k").allowed()).as("new window has the full, not a doubled, budget").isFalse();
    }

    // ------------------------------------------------------------------ cleanup / memory bound

    @Test
    void expiredEntriesArePurgedPeriodically() {
        FixedWindowRateLimiter limiter = limiter(5, 10_000);
        for (int i = 0; i < 1_000; i++) {
            limiter.tryAcquire("10.0." + (i / 256) + "." + (i % 256));
        }
        assertThat(limiter.trackedKeys()).isEqualTo(1_000);

        clock.advance(WINDOW.plusSeconds(1));
        limiter.tryAcquire("192.0.2.1"); // any request triggers the overdue cleanup
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void cleanupKeepsWindowsThatAreStillActive() {
        FixedWindowRateLimiter limiter = limiter(1, 100);
        limiter.tryAcquire("old");
        clock.advance(Duration.ofSeconds(30));
        limiter.tryAcquire("recent");
        clock.advance(Duration.ofSeconds(31)); // "old" expired, "recent" still 29 s left

        limiter.tryAcquire("trigger");
        assertThat(limiter.trackedKeys()).isEqualTo(2); // recent + trigger
        assertThat(limiter.tryAcquire("recent").allowed()).as("recent window survived cleanup").isFalse();
    }

    @Test
    void numberOfTrackedClientsNeverExceedsTheCap() {
        FixedWindowRateLimiter limiter = limiter(5, 3);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        clock.advance(Duration.ofSeconds(20));
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
        assertThat(limiter.tryAcquire("c").allowed()).isTrue();

        Decision overflow = limiter.tryAcquire("d");
        assertThat(overflow.allowed()).as("table full of live windows: new client fails closed").isFalse();
        assertThat(overflow.retryAfterSeconds()).as("until the earliest window (a) ends").isEqualTo(40);
        assertThat(limiter.trackedKeys()).isEqualTo(3);
        assertThat(limiter.tryAcquire("a").allowed()).as("tracked clients are unaffected").isTrue();
    }

    @Test
    void fullTableMakesRoomByPurgingExpiredEntriesOnDemand() {
        FixedWindowRateLimiter limiter = limiter(5, 3);   // created at t=0, periodic cleanup due at t=60
        clock.advance(Duration.ofSeconds(30));
        limiter.tryAcquire("a");                           // window ends t=90
        limiter.tryAcquire("b");                           // window ends t=90
        clock.advance(Duration.ofSeconds(31));
        limiter.tryAcquire("c");                           // t=61: periodic cleanup runs (nothing expired), next at t=121
        assertThat(limiter.trackedKeys()).isEqualTo(3);

        clock.advance(Duration.ofSeconds(30));             // t=91: a and b expired, periodic cleanup not due yet
        assertThat(limiter.tryAcquire("d").allowed()).isTrue();
        assertThat(limiter.trackedKeys()).isEqualTo(2);    // c + d: a and b were purged on demand
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new FixedWindowRateLimiter("x", 0, WINDOW, 10, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixedWindowRateLimiter("x", 1, Duration.ZERO, 10, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixedWindowRateLimiter("x", 1, WINDOW, 0, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ concurrency

    @RepeatedTest(20)
    void concurrentRequestsForOneClientCannotExceedTheLimit() throws Exception {
        FixedWindowRateLimiter limiter = limiter(10, 100);
        int allowed = countAllowed(64, 5, i -> "203.0.113.7", limiter);
        assertThat(allowed).isEqualTo(10);
    }

    @RepeatedTest(20)
    void concurrentNewClientsCannotExceedTheTrackingCap() throws Exception {
        FixedWindowRateLimiter limiter = limiter(10, 16);
        int allowed = countAllowed(64, 1, i -> "client-" + i, limiter);
        assertThat(allowed).isEqualTo(16);
        assertThat(limiter.trackedKeys()).isEqualTo(16);
    }

    private static int countAllowed(int threads, int requestsPerThread, java.util.function.IntFunction<String> key,
                                    FixedWindowRateLimiter limiter) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                results.add(pool.submit(() -> {
                    start.await();
                    int ok = 0;
                    for (int r = 0; r < requestsPerThread; r++) {
                        if (limiter.tryAcquire(key.apply(thread)).allowed()) {
                            ok++;
                        }
                    }
                    return ok;
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> f : results) {
                total += f.get(30, TimeUnit.SECONDS);
            }
            return total;
        } finally {
            pool.shutdownNow();
        }
    }
}
