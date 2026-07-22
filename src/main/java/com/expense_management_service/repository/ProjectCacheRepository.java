package com.expense_management_service.repository;

import com.expense_management_service.entity.ProjectCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProjectCacheRepository extends JpaRepository<ProjectCache, UUID> {
}
