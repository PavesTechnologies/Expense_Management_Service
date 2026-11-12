package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/** Per-person read marker for a team-inbox {@link Notification} (one row per reader). */
@Entity
@Table(name = "notification_read")
@IdClass(NotificationRead.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationRead {

    @Id
    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Id
    @Column(name = "employee_id", length = 255, nullable = false)
    private String employeeId;

    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID notificationId;
        private String employeeId;
    }
}
