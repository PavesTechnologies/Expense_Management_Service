package com.expense_management_service.repository;

import com.expense_management_service.entity.SupplementaryPayment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupplementaryPaymentRepository extends JpaRepository<SupplementaryPayment, UUID> {

    /** Locks a payment request so a confirmation cannot run twice for the same payment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SupplementaryPayment p where p.paymentId = :id")
    Optional<SupplementaryPayment> findByIdForUpdate(@Param("id") UUID id);

    Optional<SupplementaryPayment> findByClaim_ClaimId(UUID claimId);

    boolean existsByClaim_ClaimId(UUID claimId);

    boolean existsByBankReference(String bankReference);

    List<SupplementaryPayment> findByStatusOrderByRequestedAtAsc(String status);
}
