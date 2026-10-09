package com.expense_management_service.repository;

import com.expense_management_service.entity.SavedFilter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavedFilterRepository extends JpaRepository<SavedFilter, UUID> {

    List<SavedFilter> findByEmployeeIdOrderByFilterNameAsc(String employeeId);

    Optional<SavedFilter> findByEmployeeIdAndFilterNameIgnoreCase(String employeeId, String filterName);
}
