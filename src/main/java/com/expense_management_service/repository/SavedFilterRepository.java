package com.expense_management_service.repository;

import com.expense_management_service.entity.SavedFilter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SavedFilterRepository extends JpaRepository<SavedFilter, UUID> {
}
