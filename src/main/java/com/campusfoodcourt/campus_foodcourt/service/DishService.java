package com.campusfoodcourt.campus_foodcourt.service;

import com.campusfoodcourt.campus_foodcourt.entity.Dish;
import com.campusfoodcourt.campus_foodcourt.entity.Outlet;
import com.campusfoodcourt.campus_foodcourt.exception.ResourceNotFoundException;
import com.campusfoodcourt.campus_foodcourt.repository.DishRepository;
import com.campusfoodcourt.campus_foodcourt.repository.OutletRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DishService {
    private final DishRepository dishRepository;
    private final OutletRepository outletRepository;


    public DishService(DishRepository dishRepository, OutletRepository outletRepository) {
        this.dishRepository = dishRepository;
        this.outletRepository = outletRepository;
    }


    public List<Dish> searchByName(String name) {
        return dishRepository.findByNameContainingIgnoreCase(name);
    }


    public Dish create(Long outletId, Dish dish) {
        Outlet outlet = outletRepository.findById(outletId)
                .orElseThrow(() -> new ResourceNotFoundException("Outlet not found"));
        dish.setOutlet(outlet);
        return dishRepository.save(dish);
    }
}


