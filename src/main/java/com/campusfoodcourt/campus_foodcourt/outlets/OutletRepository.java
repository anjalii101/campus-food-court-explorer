package com.campusfoodcourt.campus_foodcourt.outlets;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OutletRepository extends JpaRepository<Outlet, UUID> {

    Optional<Outlet> findByVendorUserId(Long vendorUserId);
}
