package com.campusfoodcourt.campus_foodcourt.menu;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DishRepository extends JpaRepository<Dish, UUID> {

    /**
     * Current (not soft-deleted) menu of an outlet. "Outlet_Id" explicitly traverses dish.outlet.id; without the
     * underscore Spring Data would resolve the convenience getter {@code Dish.getOutletId()} as a property.
     */
    List<Dish> findByOutlet_IdAndDeletedAtIsNullOrderByNameAsc(UUID outletId);

    Optional<Dish> findByIdAndDeletedAtIsNull(UUID id);
}
