package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.entity.User;
import com.campusfoodcourt.campus_foodcourt.repository.UserRepository;
import com.campusfoodcourt.campus_foodcourt.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private RefreshTokenService refreshTokenService;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    void concurrentRefreshWithSameTokenSucceedsAtMostOnce() throws Exception {
        User user = userRepository.save(User.builder().name("Concurrent")
                .email("concurrent-" + UUID.randomUUID() + "@campus.test")
                .password("$2a$10$abcdefghijklmnopqrstuuJ8D5nZ6gN1t1p9xS7fK2m3o4q5r6s7t").role("ROLE_USER").build());
        String token = refreshTokenService.issueForNewSession(user).rawToken();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    refreshTokenService.rotate(token);
                    return true;
                } catch (InvalidRefreshTokenException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int successes = 0;
        for (Future<Boolean> f : results) {
            if (f.get(30, TimeUnit.SECONDS)) {
                successes++;
            }
        }
        pool.shutdown();

        assertThat(successes).isEqualTo(1);
        // The losing requests are replays of a rotated token, so the whole family ends up revoked.
        UUID family = refreshTokenRepository.findAll().stream()
                .filter(t -> t.getTokenHash().equals(RefreshTokenService.hash(token)))
                .findFirst().orElseThrow().getFamilyId();
        assertThat(refreshTokenRepository.findByFamilyIdOrderByIdAsc(family))
                .hasSize(2)
                .allMatch(RefreshToken::isRevoked);
    }

    @Test
    void concurrentRefreshesOfDifferentSessionsAllSucceed() throws Exception {
        int sessions = 8;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < sessions; i++) {
            User user = userRepository.save(User.builder().name("Parallel " + i)
                    .email("parallel-" + UUID.randomUUID() + "@campus.test")
                    .password("$2a$10$abcdefghijklmnopqrstuuJ8D5nZ6gN1t1p9xS7fK2m3o4q5r6s7t").role("ROLE_USER").build());
            tokens.add(refreshTokenService.issueForNewSession(user).rawToken());
        }

        ExecutorService pool = Executors.newFixedThreadPool(sessions);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RefreshTokenService.Rotation>> results = new ArrayList<>();
        for (String token : tokens) {
            results.add(pool.submit(() -> {
                start.await();
                return refreshTokenService.rotate(token);
            }));
        }
        start.countDown();
        for (Future<RefreshTokenService.Rotation> f : results) {
            assertThat(f.get(30, TimeUnit.SECONDS).next().rawToken()).isNotBlank();
        }
        pool.shutdown();
    }
}
