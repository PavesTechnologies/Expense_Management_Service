package com.expense_management_service.repository;

import com.expense_management_service.entity.EmployeeCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeCacheRepository extends JpaRepository<EmployeeCache, UUID> {

    Optional<EmployeeCache> findByEmployeeId(String employeeId);

    Optional<EmployeeCache> findByEmployeeUuid(String employeeUuid);

    List<EmployeeCache> findByManagerEmployeeId(String managerEmployeeId);

    List<EmployeeCache> findByEmploymentStatus(String employmentStatus);
}
