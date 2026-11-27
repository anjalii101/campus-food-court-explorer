package com.campusfoodcourt.campus_foodcourt.reviews;

import com.campusfoodcourt.campus_foodcourt.support.AbstractCatalogIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Database-level rules of V6 (reviews), exercised with plain SQL - no JPA checks involved. */
class ReviewSchemaConstraintsTest extends AbstractCatalogIntegrationTest {

    private UUID newDish() {
        return insertDish(insertOutlet(), unique("Dish"));
    }

    @Test
    void ratingMustBeBetweenOneAndFive() {
        UUID dish = newDish();
        for (int rating = 1; rating <= 5; rating++) {
            assertThat(insertReview(insertUser(), dish, rating)).isNotNull();
        }
        assertViolates(CHECK_VIOLATION, "ck_reviews_rating", () -> insertReview(insertUser(), dish, 0));
        assertViolates(CHECK_VIOLATION, "ck_reviews_rating", () -> insertReview(insertUser(), dish, 6));
        assertViolates(CHECK_VIOLATION, "ck_reviews_rating", () -> insertReview(insertUser(), dish, -1));
    }

    @Test
    void ratingIsMandatory() {
        long user = insertUser();
        UUID dish = newDish();
        assertViolates(NOT_NULL_VIOLATION, null, () -> jdbc.update("insert into reviews (id, user_id, dish_id,"
                + " created_at, updated_at) values (gen_random_uuid(), ?, ?, now(), now())", user, dish));
    }

    @Test
    void reviewTextIsOptionalAndLimitedTo2000Characters() {
        UUID ratingOnly = insertReview(insertUser(), newDish(), 4);
        assertThat(jdbc.queryForObject("select review_text is null from reviews where id = ?", Boolean.class,
                ratingOnly)).isTrue();

        jdbc.update("update reviews set review_text = ? where id = ?", "x".repeat(2000), ratingOnly);
        assertViolates(STRING_TOO_LONG, null,
                () -> jdbc.update("update reviews set review_text = ? where id = ?", "x".repeat(2001), ratingOnly));
    }

    @Test
    void aUserCanReviewADishOnlyOnce() {
        long user = insertUser();
        UUID dish = newDish();
        insertReview(user, dish, 5);

        assertViolates(UNIQUE_VIOLATION, "uk_reviews_user_dish", () -> insertReview(user, dish, 1));
        // The same user may review another dish, and another user may review the same dish.
        assertThat(insertReview(user, newDish(), 3)).isNotNull();
        assertThat(insertReview(insertUser(), dish, 3)).isNotNull();
    }

    @Test
    void reviewMustReferenceAnExistingUserAndDish() {
        assertViolates(FOREIGN_KEY_VIOLATION, "fk_reviews_user", () -> insertReview(-7L, newDish(), 3));
        assertViolates(FOREIGN_KEY_VIOLATION, "fk_reviews_dish", () -> insertReview(insertUser(), UUID.randomUUID(), 3));
    }

    @Test
    void deletingAReviewedDishIsRestricted() {
        UUID dish = newDish();
        insertReview(insertUser(), dish, 2);

        assertViolates(FOREIGN_KEY_VIOLATION, "fk_reviews_dish", () -> jdbc.update("delete from dishes where id = ?", dish));
    }

    @Test
    void deletingAUserWhoWroteAReviewIsRestricted() {
        long user = insertUser();
        UUID review = insertReview(user, newDish(), 2);

        assertViolates(FOREIGN_KEY_VIOLATION, "fk_reviews_user", () -> jdbc.update("delete from users where id = ?", user));
        assertThat(jdbc.queryForObject("select count(*) from reviews where id = ?", Long.class, review)).isOne();
    }

    @Test
    void newestReviewsOfADishAreServedFromTheDishCreatedAtIndex() {
        String definition = jdbc.queryForObject(
                "select indexdef from pg_indexes where tablename = 'reviews' and indexname = 'idx_reviews_dish_created_at'",
                String.class);
        assertThat(definition).contains("(dish_id, created_at DESC)");
    }

    @Test
    void updatedAtCannotBeBeforeCreatedAt() {
        UUID review = insertReview(insertUser(), newDish(), 5);
        assertViolates(CHECK_VIOLATION, "ck_reviews_updated_after_created", () -> jdbc.update(
                "update reviews set updated_at = created_at - interval '1 day' where id = ?", review));
    }
}
