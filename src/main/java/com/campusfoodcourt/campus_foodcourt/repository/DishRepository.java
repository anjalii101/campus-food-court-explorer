package com.campusfoodcourt.campus_foodcourt.repository;
import com.campusfoodcourt.campus_foodcourt.entity.Dish;
import com.campusfoodcourt.campus_foodcourt.entity.Dish;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;


public interface DishRepository extends JpaRepository<Dish, Long> {
    List<Dish> findByNameContainingIgnoreCase(String name);
    List<Dish> findByNameIgnoreCase(String name);
    List<Dish> findByNameContainingIgnoreCaseOrderByRatingDesc(String name);
}