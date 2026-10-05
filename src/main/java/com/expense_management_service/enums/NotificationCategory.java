package com.expense_management_service.enums;

/**
 * What a notification means for its recipient - drives its icon/color and the Notification
 * Center's type filter. "New" is not a category: it's simply unread.
 */
public enum NotificationCategory {
    /** The recipient must do something (review, verify, pay, fix). */
    ACTION_REQUIRED,
    /** Moving along, waiting on someone else (sent to Finance, with AP). */
    PENDING,
    /** A step finished successfully (approved, verified, paid). */
    COMPLETED,
    /** Something ended badly or broke (rejected, failed job). */
    FAILED,
    /** Worth knowing, nothing to do (config changed, reminders for others). */
    INFO
}
