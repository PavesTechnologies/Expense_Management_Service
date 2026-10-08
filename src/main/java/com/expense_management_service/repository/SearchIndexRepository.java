package com.expense_management_service.repository;

import com.expense_management_service.entity.SearchIndex;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SearchIndexRepository extends JpaRepository<SearchIndex, UUID> {

    // Get all reports by employee (scope: own reports only)
    Page<SearchIndex> findByEmployeeId(String employeeId, Pageable pageable);

    // Get all reports (for admin/finance - no scope filter)
    Page<SearchIndex> findAll(Pageable pageable);

    // Find by report ID
    List<SearchIndex> findByReportId(UUID reportId);
}
