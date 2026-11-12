package com.expense_management_service.repository;

import com.expense_management_service.entity.OutboundEmail;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboundEmailRepository extends JpaRepository<OutboundEmail, UUID> {

    List<OutboundEmail> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    List<OutboundEmail> findByEmailTypeAndReferenceId(String emailType, String referenceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OutboundEmail e where e.outboundEmailId = :id")
    Optional<OutboundEmail> findByIdForUpdate(@Param("id") UUID id);
}
