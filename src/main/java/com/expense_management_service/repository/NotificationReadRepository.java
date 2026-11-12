package com.expense_management_service.repository;

import com.expense_management_service.entity.NotificationRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface NotificationReadRepository extends JpaRepository<NotificationRead, NotificationRead.Key> {

    @Query("select r.notificationId from NotificationRead r where r.employeeId = :me and r.notificationId in :ids")
    Set<UUID> findReadIds(@Param("me") String me, @Param("ids") Collection<UUID> ids);
}
