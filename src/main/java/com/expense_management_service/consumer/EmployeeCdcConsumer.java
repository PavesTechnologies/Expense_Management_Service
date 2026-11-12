package com.expense_management_service.consumer;

import com.expense_management_service.dto.external.EmployeeCdcEvent;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.service.CdcFailureLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * Consumes flattened Debezium change events for {@code eos.employee_details}
 * and keeps {@link EmployeeCache} in sync - the local mirror of employee and
 * manager data the approval workflow (EP06) resolves approvers against.
 *
 * <p>This deliberately differs from the Leave Management System's reference
 * implementation ({@code EmployeeCdcConsumer} in
 * {@code paves-intranet-employee-leave-management}, merged and running in
 * production since May 2026) in three ways that fix real bugs found there:</p>
 * <ul>
 *   <li>Every failure path writes a {@link com.expense_management_service.entity.CdcFailureLog}
 *       row via {@link CdcFailureLogService} <i>before</i> acknowledging. LMS's
 *       consumer only logs and acks - its {@code CdcFailureLogService} is never
 *       called, so its retry cron always finds an empty table.</li>
 *   <li>{@code employmentStatus} is cached as a raw string and never converted
 *       to a local enum. LMS calls {@code EmployeeStatus.valueOf(...)}, which
 *       throws on EOS values it has no mapping for ("Exited", "On-Notice") -
 *       the exception is then swallowed and the employee is never synced.</li>
 *   <li>No {@code gender} field is cached at all (the approval workflow has no
 *       use for it), which removes LMS's null-gender {@code NullPointerException}
 *       by construction rather than by adding a null check.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmployeeCdcConsumer {

    private static final String FAILURE_PARSE_FAILED = "PARSE_FAILED";
    private static final String FAILURE_VALIDATION_FAILED = "VALIDATION_FAILED";
    private static final String FAILURE_UPSERT_FAILED = "UPSERT_FAILED";
    private static final String FAILURE_DELETE_FAILED = "DELETE_FAILED";

    // Some producers (observed from Windows-originated tooling while testing this consumer
    // against a real broker) prepend a UTF-8 byte-order-mark. StringDeserializer decodes it
    // into a literal U+FEFF character that Jackson's String-based parser does not skip on its
    // own, so it must be stripped before parsing.
    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    private final EmployeeCacheRepository employeeCacheRepository;
    private final CdcFailureLogService cdcFailureLogService;

    // Deliberately NOT constructor-injected: this Spring Boot version's JacksonAutoConfiguration
    // registers a tools.jackson.databind.ObjectMapper bean (Jackson 3), not a
    // com.fasterxml.jackson.databind.ObjectMapper (Jackson 2) one - the type this consumer and
    // every DTO in this codebase actually use. No bean of the classic type exists to inject.
    // Matches the same workaround already used in ExchangeRateControllerTest.
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @KafkaListener(topics = "${cdc.employee.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String rawValue = record.value();

        if (rawValue == null) {
            // A true Kafka tombstone, as opposed to a rewrite-mode delete (which still
            // carries a value with __deleted=true, handled below). Nothing to correlate
            // without a payload - the preceding rewrite-mode delete already did the work.
            log.info("Tombstone received for key {} on topic {}, nothing to process", record.key(), record.topic());
            ack.acknowledge();
            return;
        }

        rawValue = stripLeadingByteOrderMark(rawValue);

        EmployeeCdcEvent event;
        try {
            event = objectMapper.readValue(rawValue, EmployeeCdcEvent.class);
        } catch (Exception e) {
            cdcFailureLogService.logFailure(record.topic(), null, null, "unknown",
                    FAILURE_PARSE_FAILED, e.getMessage(), rawValue, record.partition(), record.offset());
            ack.acknowledge();
            return;
        }

        if (event.employeeUuid() == null || event.employeeUuid().isBlank()) {
            cdcFailureLogService.logFailure(record.topic(), event.employeeId(), null, event.op(),
                    FAILURE_VALIDATION_FAILED, "Event has no employee_uuid", rawValue,
                    record.partition(), record.offset());
            ack.acknowledge();
            return;
        }

        try {
            if (event.isDelete()) {
                handleDelete(event);
            } else {
                handleUpsert(event);
            }
        } catch (Exception e) {
            log.error("Failed to process CDC event op={} employeeUuid={}: {}",
                    event.op(), event.employeeUuid(), e.getMessage(), e);
            cdcFailureLogService.logFailure(record.topic(), event.employeeId(), event.employeeUuid(), event.op(),
                    event.isDelete() ? FAILURE_DELETE_FAILED : FAILURE_UPSERT_FAILED,
                    e.getMessage(), rawValue, record.partition(), record.offset());
        }

        // Acknowledge either way: a poison message must not block the partition
        // forever. Its permanent record lives in cdc_failure_log, which
        // CdcRetryScheduler replays independently of the live topic offset.
        ack.acknowledge();
    }

    @Transactional
    public void handleUpsert(EmployeeCdcEvent event) {
        EmployeeCache employee = employeeCacheRepository.findByEmployeeUuid(event.employeeUuid())
                .orElseGet(EmployeeCache::new);

        employee.setEmployeeUuid(event.employeeUuid());
        employee.setEmployeeId(safe(event.employeeId(), event.employeeUuid()));
        employee.setFirstName(event.firstName());
        employee.setLastName(event.lastName());
        employee.setWorkEmail(event.workEmail());
        employee.setManagerEmployeeId(event.reportingManagerUuid());
        employee.setDepartmentUuid(event.departmentUuid());
        employee.setDesignationUuid(event.designationUuid());
        employee.setEmploymentStatus(event.employmentStatus());
        employee.setEmploymentType(event.employmentType());
        employee.setJoiningDate(parseJoiningDate(event.joiningDate(), event.employeeUuid()));
        employee.setSyncedAt(LocalDateTime.now());

        employeeCacheRepository.save(employee);
        log.info("Upserted employee cache row: employeeId={} employeeUuid={} status={}",
                employee.getEmployeeId(), employee.getEmployeeUuid(), employee.getEmploymentStatus());
    }

    @Transactional
    public void handleDelete(EmployeeCdcEvent event) {
        employeeCacheRepository.findByEmployeeUuid(event.employeeUuid()).ifPresentOrElse(employee -> {
            employeeCacheRepository.delete(employee);
            log.info("Deleted employee cache row: employeeId={} employeeUuid={}",
                    employee.getEmployeeId(), employee.getEmployeeUuid());
        }, () -> log.warn("Delete event received for employeeUuid={} but no cache row exists, nothing to do",
                event.employeeUuid()));
    }

    private String safe(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private String stripLeadingByteOrderMark(String value) {
        return (!value.isEmpty() && value.charAt(0) == BYTE_ORDER_MARK) ? value.substring(1) : value;
    }

    private LocalDate parseJoiningDate(String rawJoiningDate, String employeeUuid) {
        if (rawJoiningDate == null || rawJoiningDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(rawJoiningDate);
        } catch (DateTimeParseException isoParseFailure) {
            try {
                return LocalDate.ofEpochDay(Long.parseLong(rawJoiningDate));
            } catch (NumberFormatException epochParseFailure) {
                log.warn("Could not parse joining_date '{}' for employeeUuid={}, leaving null",
                        rawJoiningDate, employeeUuid);
                return null;
            }
        }
    }
}
