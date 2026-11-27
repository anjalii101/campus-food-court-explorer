package com.campusfoodcourt.campus_foodcourt.reviews;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    // "Dish_Id" explicitly traverses review.dish.id (Review.getDishId() is a convenience getter, not a mapped field).

    /** Newest first; served by index idx_reviews_dish_created_at. */
    List<Review> findByDish_IdOrderByCreatedAtDesc(UUID dishId);

    Optional<Review> findByUserIdAndDish_Id(Long userId, UUID dishId);
}
