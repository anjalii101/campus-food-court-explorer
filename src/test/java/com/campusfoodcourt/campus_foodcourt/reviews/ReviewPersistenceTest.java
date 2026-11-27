package com.campusfoodcourt.campus_foodcourt.reviews;

import com.campusfoodcourt.campus_foodcourt.menu.DietaryType;
import com.campusfoodcourt.campus_foodcourt.menu.Dish;
import com.campusfoodcourt.campus_foodcourt.menu.DishRepository;
import com.campusfoodcourt.campus_foodcourt.outlets.Outlet;
import com.campusfoodcourt.campus_foodcourt.outlets.OutletRepository;
import com.campusfoodcourt.campus_foodcourt.support.AbstractCatalogIntegrationTest;
import com.campusfoodcourt.campus_foodcourt.support.MutableClock;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JPA mapping of {@link Review} against the V6 schema. */
class ReviewPersistenceTest extends AbstractCatalogIntegrationTest {

    @Autowired
    private ReviewRepository reviews;
    @Autowired
    private DishRepository dishes;
    @Autowired
    private OutletRepository outlets;

    private final MutableClock clock = new MutableClock(T0);

    private Dish savedDish() {
        Outlet outlet = outlets.saveAndFlush(new Outlet(insertUser(), unique("Outlet"), "Canteen", clock));
        return dishes.saveAndFlush(new Dish(outlet, unique("Dish"), 6000, DietaryType.NON_VEG, clock));
    }

    @Test
    void reviewWithTextRoundTrips() {
        long user = insertUser();
        Dish dish = savedDish();
        Review review = new Review(user, dish, 5, "Best biryani on campus", clock);
        assertThat(review.getId()).isNull();

        reviews.saveAndFlush(review);

        assertThat(review.getId()).isNotNull();
        assertThat(review.getId().version()).isEqualTo(4);
        Review loaded = reviews.findByUserIdAndDish_Id(user, dish.getId()).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(review.getId());
        assertThat(loaded.getUserId()).isEqualTo(user);
        assertThat(loaded.getDishId()).isEqualTo(dish.getId());
        assertThat(loaded.getRating()).isEqualTo(5);
        assertThat(loaded.getReviewText()).isEqualTo("Best biryani on campus");
        assertThat(jdbc.queryForObject("select rating from reviews where id = ?", Short.class, review.getId()))
                .isEqualTo((short) 5);
    }

    @Test
    void aReviewMayConsistOfARatingOnly() {
        Review ratingOnly = reviews.saveAndFlush(new Review(insertUser(), savedDish(), 3, null, clock));
        Review blankText = reviews.saveAndFlush(new Review(insertUser(), savedDish(), 4, "   ", clock));

        assertThat(reviews.findById(ratingOnly.getId()).orElseThrow().getReviewText()).isNull();
        // Blank text is normalised to NULL, so "no text" has exactly one representation.
        assertThat(jdbc.queryForObject("select review_text is null from reviews where id = ?", Boolean.class,
                blankText.getId())).isTrue();
    }

    @Test
    void dishIsLoadedLazily() {
        Dish dish = savedDish();
        UUID reviewId = reviews.saveAndFlush(new Review(insertUser(), dish, 4, "Good", clock)).getId();

        tx.executeWithoutResult(s -> {
            entityManager.clear();
            Review loaded = entityManager.find(Review.class, reviewId);
            assertThat(Hibernate.isInitialized(loaded.getDish())).isFalse();
            assertThat(loaded.getDishId()).isEqualTo(dish.getId());
            assertThat(Hibernate.isInitialized(loaded.getDish())).isFalse();
            assertThat(loaded.getDish().getName()).isEqualTo(dish.getName());
            assertThat(Hibernate.isInitialized(loaded.getDish())).isTrue();
        });
    }

    @Test
    void reviseUpdatesRatingTextAndTimestampFromTheInjectedClock() {
        Review review = reviews.saveAndFlush(new Review(insertUser(), savedDish(), 2, "Too salty", clock));

        clock.advance(Duration.ofDays(3));
        Review reloaded = reviews.findById(review.getId()).orElseThrow();
        reloaded.revise(4, null, clock);
        reviews.saveAndFlush(reloaded);

        var row = jdbc.queryForMap("select rating, review_text, created_at, updated_at from reviews where id = ?",
                review.getId());
        assertThat(row).containsEntry("rating", 4).containsEntry("review_text", null);
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(T0);
        assertThat(((Timestamp) row.get("updated_at")).toInstant()).isEqualTo(T0.plus(Duration.ofDays(3)));
    }

    @Test
    void reviewsOfADishAreListedNewestFirst() {
        Dish dish = savedDish();
        Review oldest = reviews.saveAndFlush(new Review(insertUser(), dish, 3, null, clock));
        clock.advance(Duration.ofMinutes(1));
        Review newest = reviews.saveAndFlush(new Review(insertUser(), dish, 5, null, clock));

        assertThat(reviews.findByDish_IdOrderByCreatedAtDesc(dish.getId()))
                .extracting(Review::getId).containsExactly(newest.getId(), oldest.getId());
    }

    @Test
    void duplicateReviewIsRejectedThroughJpaToo() {
        long user = insertUser();
        Dish dish = savedDish();
        reviews.saveAndFlush(new Review(user, dish, 5, null, clock));

        assertThatThrownBy(() -> reviews.saveAndFlush(new Review(user, dish, 1, "changed my mind", clock)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentDuplicateReviewsResultInExactlyOneRow() throws Exception {
        long user = insertUser();
        Dish dish = savedDish();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                int rating = i % 5 + 1;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        reviews.saveAndFlush(new Review(user, dish, rating, null, clock));
                        return true;
                    } catch (DataIntegrityViolationException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertThat(successes).isOne();
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from reviews where user_id = ? and dish_id = ?", Long.class,
                user, dish.getId())).isOne();
    }
}
