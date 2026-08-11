package com.expense_management_service.repository;

import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.enums.LevelInstanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalLevelInstanceRepository extends JpaRepository<ApprovalLevelInstance, UUID> {

    List<ApprovalLevelInstance> findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(UUID reportId, Integer submissionCycle);

    Optional<ApprovalLevelInstance> findByReport_ReportIdAndSubmissionCycleAndStatus(
            UUID reportId, Integer submissionCycle, LevelInstanceStatus status);

    /** 0 if the report has never been through the approval engine yet (no prior cycle). */
    @Query("SELECT COALESCE(MAX(i.submissionCycle), 0) FROM ApprovalLevelInstance i WHERE i.report.reportId = :reportId")
    Integer findMaxSubmissionCycle(@Param("reportId") UUID reportId);
}
