package com.campusfoodcourt.campus_foodcourt.service;

import com.campusfoodcourt.campus_foodcourt.entity.Outlet;
import com.campusfoodcourt.campus_foodcourt.exception.ResourceNotFoundException;
import com.campusfoodcourt.campus_foodcourt.repository.OutletRepository;
import org.springframework.stereotype.Service;


import java.util.List;


@Service
public class OutletService {
    private final OutletRepository outletRepository;


    public OutletService(OutletRepository outletRepository) {
        this.outletRepository = outletRepository;
    }


    public List<Outlet> getAll() {
        return outletRepository.findAll();
    }


    public Outlet getById(Long id) {
        return outletRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Outlet not found"));
    }


    public Outlet create(Outlet outlet) {
        return outletRepository.save(outlet);
    }
}