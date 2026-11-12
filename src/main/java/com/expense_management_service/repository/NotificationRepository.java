package com.expense_management_service.repository;

import com.expense_management_service.entity.Notification;
import com.expense_management_service.enums.NotificationCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** "Read" for the caller: a personal one with is_read, or a team one with their read marker. */
    String IS_READ = """
            ((n.employeeId is not null and coalesce(n.isRead, false) = true)
             or (n.recipientRole is not null and exists (
                 select 1 from NotificationRead r where r.notificationId = n.notificationId and r.employeeId = :me)))
            """;

    String VISIBLE = "(n.employeeId = :me or n.recipientRole in :roles)";

    /**
     * The caller's notifications, newest first, with every Notification Center filter optional
     * ({@code readFilter}: all / read / unread; {@code q} is already lower-cased and wrapped in %).
     */
    @Query("select n from Notification n where " + VISIBLE + """
             and (:category is null or n.category = :category)
             and (:eventType is null or n.eventType = :eventType)
             and (:from is null or n.sentAt >= :from)
             and (:to is null or n.sentAt < :to)
             and (:q is null or lower(n.title) like :q or lower(n.message) like :q
                  or lower(n.reportNumber) like :q or lower(n.actorName) like :q)
             and (:readFilter = 'all'
                  or (:readFilter = 'read' and """ + IS_READ + """
                  )
                  or (:readFilter = 'unread' and not """ + IS_READ + """
                  ))
            order by n.sentAt desc
            """)
    Page<Notification> search(@Param("me") String me,
                              @Param("roles") Collection<String> roles,
                              @Param("category") NotificationCategory category,
                              @Param("eventType") String eventType,
                              @Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to,
                              @Param("q") String q,
                              @Param("readFilter") String readFilter,
                              Pageable pageable);

    @Query("select count(n) from Notification n where " + VISIBLE + " and not " + IS_READ)
    long countUnread(@Param("me") String me, @Param("roles") Collection<String> roles);

    /** Unread team-inbox notifications the caller can see - for "mark all as read". */
    @Query("select n.notificationId from Notification n where n.recipientRole in :roles and not exists ("
            + "select 1 from NotificationRead r where r.notificationId = n.notificationId and r.employeeId = :me)")
    List<UUID> findUnreadTeamIds(@Param("me") String me, @Param("roles") Collection<String> roles);

    @Modifying
    @Query("update Notification n set n.isRead = true where n.employeeId = :me and coalesce(n.isRead, false) = false")
    int markAllPersonalRead(@Param("me") String me);

    /** De-duplication for repeating events (e.g. the hourly SLA reminder). */
    boolean existsByEmployeeIdAndEventTypeAndReportIdAndSentAtAfter(
            String employeeId, String eventType, UUID reportId, LocalDateTime after);

    /** De-duplication for bursty team alerts (e.g. a run of failing sync messages). */
    boolean existsByRecipientRoleAndEventTypeAndSentAtAfter(String recipientRole, String eventType, LocalDateTime after);
}
