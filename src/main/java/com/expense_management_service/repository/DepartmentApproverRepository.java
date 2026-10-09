package com.expense_management_service.repository;

import com.expense_management_service.entity.DepartmentApprover;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DepartmentApproverRepository extends JpaRepository<DepartmentApprover, UUID> {

    Optional<DepartmentApprover> findByDepartmentUuid(UUID departmentUuid);
}
