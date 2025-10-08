package com.campusfoodcourt.campus_foodcourt.repository;
import com.campusfoodcourt.campus_foodcourt.entity.Outlet;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;


public interface OutletRepository extends JpaRepository<Outlet, Long> {
    List<Outlet> findByFoodCourtContainingIgnoreCase(String foodCourt);
}