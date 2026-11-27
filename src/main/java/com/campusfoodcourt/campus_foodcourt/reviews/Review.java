package com.campusfoodcourt.campus_foodcourt.reviews;

import com.campusfoodcourt.campus_foodcourt.common.Preconditions;
import com.campusfoodcourt.campus_foodcourt.common.Timestamps;
import com.campusfoodcourt.campus_foodcourt.menu.Dish;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A user's rating (1-5) of a dish, with optional text (table reviews, migration V6).
 * <p>
 * At most one review per user and dish is enforced by the database (uk_reviews_user_dish). That does NOT mean the
 * user bought the dish: purchase eligibility is checked by the order workflow in a later milestone.
 */
@Entity
@Table(name = "reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review {

    public static final int MIN_RATING = 1;
    public static final int MAX_RATING = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** users.id of the author. A plain id: this module never loads the User entity or its password hash. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** Lazy: loading a review never loads its dish unless the dish is actually used. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dish_id", nullable = false, updatable = false)
    private Dish dish;

    @Getter(AccessLevel.NONE)
    @Column(name = "rating", nullable = false)
    private short rating;

    /** Optional: a review may consist of a rating only. */
    @Column(name = "review_text", length = 2000)
    private String reviewText;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Review(long userId, Dish dish, int rating, String reviewText, Clock clock) {
        this.userId = userId;
        this.dish = Objects.requireNonNull(dish, "dish");
        this.rating = requireValidRating(rating);
        this.reviewText = Preconditions.blankToNull(reviewText);
        this.createdAt = Timestamps.now(clock);
        this.updatedAt = this.createdAt;
    }

    public int getRating() {
        return rating;
    }

    /** Id of the reviewed dish, readable without initialising the lazy dish proxy. */
    public UUID getDishId() {
        return dish.getId();
    }

    public void revise(int newRating, String newReviewText, Clock clock) {
        this.rating = requireValidRating(newRating);
        this.reviewText = Preconditions.blankToNull(newReviewText);
        this.updatedAt = Timestamps.now(clock);
    }

    private static short requireValidRating(int rating) {
        if (rating < MIN_RATING || rating > MAX_RATING) {
            throw new IllegalArgumentException("rating must be between " + MIN_RATING + " and " + MAX_RATING
                    + ": " + rating);
        }
        return (short) rating;
    }
}
