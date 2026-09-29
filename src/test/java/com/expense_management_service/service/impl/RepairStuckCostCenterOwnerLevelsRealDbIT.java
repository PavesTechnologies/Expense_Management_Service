package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.enums.LevelInstanceStatus;
import com.expense_management_service.enums.LevelType;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.mapper.ExpenseReportMapper;
import com.expense_management_service.mapper.PolicyViolationMapper;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.repository.ApprovalLevelInstanceRepository;
import com.expense_management_service.repository.ApprovalLineItemReviewRepository;
import com.expense_management_service.repository.ApprovalSplitReviewRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.FinanceVerificationReviewRepository;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.repository.VerificationQueryRepository;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.service.ApprovalFlowResolutionService;
import com.expense_management_service.service.ApproverSourceResolver;
import com.expense_management_service.service.BudgetEncumbranceService;
import com.expense_management_service.service.ChainCorrectnessService;
import com.expense_management_service.service.DelegationService;
import com.expense_management_service.service.MaterialChangeEvaluator;
import com.expense_management_service.service.PolicyEvaluationGateway;
import com.expense_management_service.service.SlaPolicyService;
import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.annotation.Commit;
import org.springframework.test.context.ContextConfiguration;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One-time data repair for the 3 real reports (all employee 5100014) that got stuck at their
 * "Cost Center Owner Approval" level BEFORE the {@code activateNextEligibleLevel}/{@code
 * allAssignmentsAlreadySkipped} fix (see {@code ApprovalWorkflowServiceImpl}) went live - their
 * Reporting Manager and Cost Center Owner are the same employee (5100023), so the level's only
 * assignment was correctly auto-skipped as a cross-level duplicate, but the pre-fix code left the
 * level instance itself sitting at {@code ACTIVE} forever instead of waiving it and advancing.
 * Redeploying the fix does not retroactively repair rows already in that broken state - nothing
 * re-evaluates an already-ACTIVE instance - so this runs the exact same (now-fixed) production
 * method against each affected row instead of hand-writing SQL.
 * <p>
 * Named *IT*, not *Test* (see {@code RealDbSmokeIT}), so Surefire never runs this in a normal
 * build - it requires real DB credentials and, more importantly, must never run more than once
 * against rows that are no longer in the broken state (the pre-flight assertion inside the test
 * guards against that: it refuses to touch any instance that isn't EXACTLY the reported "ACTIVE
 * with only SKIPPED assignments" pattern, so a second run is a safe no-op via that early failure,
 * not a silent double-repair).
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(initializers = DotenvApplicationInitializer.class)
@Commit // this repair must persist - @DataJpaTest rolls back by default
class RepairStuckCostCenterOwnerLevelsRealDbIT {

    private static final List<String> AFFECTED_REPORT_NUMBERS = List.of(
            "EXP-2026-2027-BDBA9C23", "EXP-2026-2027-C9E7F00A", "EXP-2026-0027D461");

    @Autowired private ExpenseReportRepository expenseReportRepository;
    @Autowired private ApprovalLevelInstanceRepository approvalLevelInstanceRepository;
    @Autowired private ApprovalAssignmentRepository approvalAssignmentRepository;
    @Autowired private ApprovalLineItemReviewRepository approvalLineItemReviewRepository;
    @Autowired private ApprovalSplitReviewRepository approvalSplitReviewRepository;
    @Autowired private PolicyViolationRepository policyViolationRepository;
    @Autowired private FinanceVerificationReviewRepository financeVerificationReviewRepository;
    @Autowired private VerificationQueryRepository verificationQueryRepository;

    @Test
    void repairStuckCostCenterOwnerLevels_forTheThreeKnownAffectedReports() throws Exception {
        SlaPolicyService slaPolicyService = mock(SlaPolicyService.class);
        when(slaPolicyService.resolveSlaBusinessDays()).thenReturn(3);
        ApprovalEventPublisher approvalEventPublisher = mock(ApprovalEventPublisher.class);

        // Only the real APPROVAL/FINANCE_VERIFICATION strategies + real repositories matter here -
        // every other collaborator is unreachable from completeSkippedLevel/activateNextEligibleLevel/
        // activateLevelInstance for this specific bug pattern (no split-owner assignments, no
        // resubmission, no policy re-evaluation), so a mock is both sufficient and safer than
        // wiring up the full production graph for a one-time backfill.
        ApprovalWorkflowServiceImpl service = new ApprovalWorkflowServiceImpl(
                expenseReportRepository, approvalLevelInstanceRepository, approvalAssignmentRepository,
                approvalLineItemReviewRepository, approvalSplitReviewRepository, policyViolationRepository,
                mock(ApprovalFlowResolutionService.class), mock(ApproverSourceResolver.class),
                mock(ChainCorrectnessService.class), mock(BudgetEncumbranceService.class),
                mock(DelegationService.class), mock(PolicyEvaluationGateway.class),
                approvalEventPublisher, slaPolicyService, new PolicyViolationMapper(),
                List.of(new ApprovalReviewStrategy(approvalLineItemReviewRepository),
                        new FinanceVerificationStrategy(financeVerificationReviewRepository, verificationQueryRepository)),
                new ExpenseReportResponseFactory(new ExpenseReportMapper(), policyViolationRepository),
                mock(MaterialChangeEvaluator.class));

        Method completeSkippedLevel = ApprovalWorkflowServiceImpl.class
                .getDeclaredMethod("completeSkippedLevel", ExpenseReport.class, ApprovalLevelInstance.class, int.class);
        completeSkippedLevel.setAccessible(true);

        for (String reportNumber : AFFECTED_REPORT_NUMBERS) {
            ExpenseReport report = expenseReportRepository.findAll().stream()
                    .filter(r -> reportNumber.equals(r.getReportNumber()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Expected report " + reportNumber + " not found - aborting repair"));
            assertThat(report.getReportStatus()).isEqualTo(ReportStatus.PENDING_APPROVAL);

            int cycle = approvalLevelInstanceRepository.findMaxSubmissionCycle(report.getReportId());
            ApprovalLevelInstance stuckInstance = approvalLevelInstanceRepository
                    .findByReport_ReportIdAndSubmissionCycleAndStatus(report.getReportId(), cycle, LevelInstanceStatus.ACTIVE)
                    .orElseThrow(() -> new IllegalStateException("Report " + reportNumber + " has no ACTIVE level - already repaired or a different problem, aborting"));

            // Refuse to touch anything that isn't EXACTLY the reported bug pattern.
            assertThat(stuckInstance.getLevelType()).isEqualTo(LevelType.APPROVAL);
            List<ApprovalAssignment> assignments = approvalAssignmentRepository
                    .findByLevelInstance_InstanceId(stuckInstance.getInstanceId());
            assertThat(assignments).isNotEmpty();
            assertThat(assignments).allMatch(a -> a.getStatus() == AssignmentStatus.SKIPPED);

            completeSkippedLevel.invoke(service, report, stuckInstance, cycle);

            System.out.println("Repaired " + reportNumber + " - level " + stuckInstance.getLevelOrder() + " waived, chain advanced.");
        }

        // Verify the end state for all three: the stuck level is COMPLETED and the next level
        // (Finance) is now genuinely ACTIVE with a live assignment and pending review rows.
        for (String reportNumber : AFFECTED_REPORT_NUMBERS) {
            ExpenseReport report = expenseReportRepository.findAll().stream()
                    .filter(r -> reportNumber.equals(r.getReportNumber())).findFirst().orElseThrow();
            int cycle = approvalLevelInstanceRepository.findMaxSubmissionCycle(report.getReportId());
            List<ApprovalLevelInstance> instances = approvalLevelInstanceRepository
                    .findByReport_ReportIdAndSubmissionCycleOrderByLevelOrderAsc(report.getReportId(), cycle);

            ApprovalLevelInstance costCenterOwnerLevel = instances.stream()
                    .filter(i -> i.getLevelType() == LevelType.APPROVAL && i.getLevelOrder() == 2).findFirst().orElseThrow();
            assertThat(costCenterOwnerLevel.getStatus()).isEqualTo(LevelInstanceStatus.COMPLETED);

            ApprovalLevelInstance financeLevel = instances.stream()
                    .filter(i -> i.getLevelType() == LevelType.FINANCE_VERIFICATION).findFirst().orElseThrow();
            assertThat(financeLevel.getStatus()).isEqualTo(LevelInstanceStatus.ACTIVE);

            List<ApprovalAssignment> financeAssignments = approvalAssignmentRepository
                    .findByLevelInstance_InstanceId(financeLevel.getInstanceId());
            assertThat(financeAssignments).anyMatch(a -> a.getStatus() == AssignmentStatus.ACTIVE && a.getAssignedAt() != null);

            assertThat(financeVerificationReviewRepository.findByLevelInstance_InstanceId(financeLevel.getInstanceId()))
                    .isNotEmpty();

            assertThat(report.getReportStatus()).isEqualTo(ReportStatus.PENDING_FINANCE_VERIFICATION);

            System.out.println("Verified " + reportNumber + " - now PENDING_FINANCE_VERIFICATION, Finance level ACTIVE.");
        }
    }
}
