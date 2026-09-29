package com.expense_management_service.entity;

import com.expense_management_service.enums.NotificationCategory;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One workflow notification: who did what, to which record, its current status, and what (if
 * anything) the recipient should do next. Exactly one of {@link #employeeId} (a personal
 * notification) or {@link #recipientRole} (a team inbox, e.g. every FINANCE_EXECUTIVE) is set.
 * Personal read state lives in {@link #isRead}; team read state is per person, in
 * {@link NotificationRead}.
 */
@Entity
@Table(name = "notification")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "notification_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID notificationId;

    /** Recipient of a personal notification; null for a team-inbox one. */
    @Column(name = "employee_id", length = 255)
    private String employeeId;

    /** Recipient role of a team-inbox notification (upper-case, e.g. FINANCE_EXECUTIVE); null for a personal one. */
    @Column(name = "recipient_role", length = 64)
    private String recipientRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 32)
    private NotificationCategory category;

    /** The workflow event that produced it (REPORT_SUBMITTED, PAYMENT_COMPLETED, ...), for filtering. */
    @Column(name = "event_type", length = 64)
    private String eventType;

    /** Legacy free-text type column; kept in step with {@link #eventType}. */
    @Column(name = "notification_type", length = 255)
    private String notificationType;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "report_id")
    private UUID reportId;

    @Column(name = "report_number", length = 64)
    private String reportNumber;

    /** Who caused it (display name), e.g. the submitting employee. */
    @Column(name = "actor_name", length = 255)
    private String actorName;

    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency_code", length = 8)
    private String currencyCode;

    /** The record's status at the time, in words ("Pending manager approval"). */
    @Column(name = "status_label", length = 128)
    private String statusLabel;

    /** Button text for the next step ("Review expense"); null when nothing is expected. */
    @Column(name = "action_label", length = 64)
    private String actionLabel;

    /** In-app route the notification opens. */
    @Column(name = "link", length = 512)
    private String link;

    @Column(name = "is_read")
    private Boolean isRead;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}
