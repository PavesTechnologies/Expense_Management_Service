package com.expense_management_service.scheduler;

import com.expense_management_service.service.impl.DeadlineReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Daily sweep that emails employees whose cash advance expense deadline is 7 days away. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeadlineReminderScheduler {

    private final DeadlineReminderService deadlineReminderService;

    @Scheduled(cron = "${cash-advance.deadline-reminder.cron:0 0 8 * * *}")
    public void runDeadlineReminders() {
        try {
            int queued = deadlineReminderService.sendDueReminders(LocalDateTime.now());
            log.info("Cash advance deadline reminder sweep queued {} reminder(s)", queued);
        } catch (Exception e) {
            log.error("Error during the cash advance deadline reminder sweep", e);
        }
    }
}
