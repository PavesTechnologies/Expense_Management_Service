package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalAssignment;
import com.expense_management_service.enums.AssignmentStatus;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.service.ApprovalDomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes {@link ApprovalDomainEvent}s to the browser over WebSocket (§14) - a sibling to
 * {@link RabbitApprovalEventListener}, not a replacement: same AFTER_COMMIT, fire-and-forget shape,
 * because nobody being connected (or the push itself failing) must never affect the engine's own
 * transaction, which has already committed by the time this runs. The RabbitMQ path stays the
 * durable/audit delivery mechanism; this is a second, independent consumer of the same event.
 * <p>
 * Notifies the report owner (drives the status pill / correction-visibility refresh) and every
 * currently-ACTIVE assignment's resolved approverId (drives the queue refresh) - not their
 * delegates. A delegate's queue is always resolved fresh from the DB on their next fetch, so this
 * is a live-refresh nicety, not a correctness issue; pushing to delegates too would mean re-running
 * delegation resolution for every assignment on every event, which isn't worth it for that - a
 * documented simplification, not an oversight.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApprovalWebSocketEventListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ExpenseReportRepository expenseReportRepository;
    private final ApprovalAssignmentRepository approvalAssignmentRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApprovalEvent(ApprovalDomainEvent event) {
        try {
            expenseReportRepository.findById(event.reportId())
                    .ifPresent(report -> messagingTemplate.convertAndSendToUser(report.getEmployeeId(), "/queue/report-updates", event));

            approvalAssignmentRepository.findByLevelInstance_Report_ReportId(event.reportId()).stream()
                    .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE)
                    .map(ApprovalAssignment::getApproverId)
                    .distinct()
                    .forEach(approverId -> messagingTemplate.convertAndSendToUser(approverId, "/queue/approval-queue-updates", event));
        } catch (Exception ex) {
            log.warn("Failed to push WebSocket update for approval event {} on report {} - connected clients will catch up on next fetch",
                    event.eventType(), event.reportId(), ex);
        }
    }
}
