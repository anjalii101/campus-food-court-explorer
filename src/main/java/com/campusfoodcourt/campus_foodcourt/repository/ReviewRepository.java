package com.campusfoodcourt.campus_foodcourt.repository;
import com.campusfoodcourt.campus_foodcourt.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;


public interface ReviewRepository extends JpaRepository<Review, Long> {
}