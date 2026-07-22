package com.expense_management_service.repository;

import com.expense_management_service.entity.CostAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CostAllocationRepository extends JpaRepository<CostAllocation, UUID> {
}
