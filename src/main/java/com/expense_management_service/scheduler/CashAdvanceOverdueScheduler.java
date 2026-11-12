package com.expense_management_service.scheduler;

import com.expense_management_service.service.CashAdvanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CashAdvanceOverdueScheduler {

    private final CashAdvanceService cashAdvanceService;

    @Scheduled(cron = "${cash-advance.overdue.cron:0 0 1 * * *}")
    public void runOverdueCheck() {
        log.info("Starting scheduled cash advance overdue check sweep");
        try {
            cashAdvanceService.processOverdueAdvances();
            log.info("Scheduled cash advance overdue check sweep completed successfully");
        } catch (Exception e) {
            log.error("Error occurred during scheduled cash advance overdue check sweep", e);
        }
    }
}
