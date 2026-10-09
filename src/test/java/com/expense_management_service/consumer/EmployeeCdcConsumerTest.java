package com.expense_management_service.consumer;

import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.service.CdcFailureLogService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeeCdcConsumerTest {

    private static final String TOPIC = "eos_dev.eos.employee_details";

    @Mock
    private EmployeeCacheRepository employeeCacheRepository;

    @Mock
    private CdcFailureLogService cdcFailureLogService;

    @Mock
    private Acknowledgment acknowledgment;

    private EmployeeCdcConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new EmployeeCdcConsumer(employeeCacheRepository, cdcFailureLogService);
    }

    private ConsumerRecord<String, String> consumerRecord(String key, String value) {
        return new ConsumerRecord<>(TOPIC, 0, 42L, key, value);
    }

    @Test
    void consume_createsNewEmployeeCacheRow_onInsertEvent() {
        String uuid = "3f1b2c4d-0000-0000-0000-000000000001";
        String payload = """
                {"employee_uuid":"%s","employee_id":"5100101","first_name":"Asha","last_name":"Verma",
                "work_email":"asha.verma@paves.com","joining_date":"2026-07-01",
                "reporting_manager_uuid":"5100001","employment_status":"Active","__op":"c","__deleted":"false"}
                """.formatted(uuid);

        when(employeeCacheRepository.findByEmployeeUuid(uuid)).thenReturn(Optional.empty());
        when(employeeCacheRepository.save(any(EmployeeCache.class))).thenAnswer(inv -> inv.getArgument(0));

        consumer.consume(consumerRecord(uuid, payload), acknowledgment);

        ArgumentCaptor<EmployeeCache> captor = ArgumentCaptor.forClass(EmployeeCache.class);
        verify(employeeCacheRepository).save(captor.capture());
        EmployeeCache saved = captor.getValue();
        assertThat(saved.getEmployeeUuid()).isEqualTo(uuid);
        assertThat(saved.getEmployeeId()).isEqualTo("5100101");
        assertThat(saved.getManagerEmployeeId()).isEqualTo("5100001");
        assertThat(saved.getJoiningDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(saved.getSyncedAt()).isNotNull();
        verify(acknowledgment).acknowledge();
        verify(cdcFailureLogService, never()).logFailure(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void consume_stripsLeadingByteOrderMark_beforeParsing() {
        // Observed against a real broker: some producers prepend a UTF-8 BOM, which
        // StringDeserializer decodes into a literal U+FEFF character. Jackson's String-based
        // parser does not skip it on its own and previously failed every such message with
        // "Unexpected character ('?' (code 65279 / 0xfeff))".
        String uuid = "3f1b2c4d-0000-0000-0000-00000000000b";
        String jsonWithBom = ((char) 0xFEFF) + """
                {"employee_uuid":"%s","employee_id":"5100111","__op":"c","__deleted":"false"}
                """.formatted(uuid);

        when(employeeCacheRepository.findByEmployeeUuid(uuid)).thenReturn(Optional.empty());
        when(employeeCacheRepository.save(any(EmployeeCache.class))).thenAnswer(inv -> inv.getArgument(0));

        consumer.consume(consumerRecord(uuid, jsonWithBom), acknowledgment);

        verify(employeeCacheRepository).save(any(EmployeeCache.class));
        verify(acknowledgment).acknowledge();
        verify(cdcFailureLogService, never()).logFailure(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void consume_updatesExistingRow_onUpdateEvent() {
        String uuid = "3f1b2c4d-0000-0000-0000-000000000002";
        EmployeeCache existing = EmployeeCache.builder().employeeUuid(uuid).employeeId("5100102").employmentStatus("Active").build();
        String payload = """
                {"employee_uuid":"%s","employee_id":"5100102","employment_status":"On-Notice","__op":"u","__deleted":"false"}
                """.formatted(uuid);

        when(employeeCacheRepository.findByEmployeeUuid(uuid)).thenReturn(Optional.of(existing));
        when(employeeCacheRepository.save(any(EmployeeCache.class))).thenAnswer(inv -> inv.getArgument(0));

        consumer.consume(consumerRecord(uuid, payload), acknowledgment);

        assertThat(existing.getEmploymentStatus()).isEqualTo("On-Notice");
        verify(acknowledgment).acknowledge();
        verify(cdcFailureLogService, never()).logFailure(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void consume_storesUnrecognizedEmploymentStatusVerbatim_withoutThrowing() {
        // EOS emits statuses ("Exited", "On-Notice") that the Leave Management System's local
        // enum has no mapping for and throws on. employmentStatus is a raw string here precisely
        // so an unfamiliar value can never crash the consumer.
        String uuid = "3f1b2c4d-0000-0000-0000-000000000003";
        String payload = """
                {"employee_uuid":"%s","employee_id":"5100103","employment_status":"Exited","__op":"u","__deleted":"false"}
                """.formatted(uuid);
        when(employeeCacheRepository.findByEmployeeUuid(uuid)).thenReturn(Optional.empty());
        when(employeeCacheRepository.save(any(EmployeeCache.class))).thenAnswer(inv -> inv.getArgument(0));

        consumer.consume(consumerRecord(uuid, payload), acknowledgment);

        ArgumentCaptor<EmployeeCache> captor = ArgumentCaptor.forClass(EmployeeCache.class);
        verify(employeeCacheRepository).save(captor.capture());
        assertThat(captor.getValue().getEmploymentStatus()).isEqualTo("Exited");
        verify(acknowledgment).acknowledge();
        verify(cdcFailureLogService, never()).logFailure(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void consume_doesNotThrow_whenGenderIsAbsentFromPayload() {
        String uuid = "3f1b2c4d-0000-0000-0000-000000000004";
        String payload = """
                {"employee_uuid":"%s","employee_id":"5100104","__op":"c","__deleted":"false"}
                """.formatted(uuid);
        when(employeeCacheRepository.findByEmployeeUuid(uuid)).thenReturn(Optional.empty());
        when(employeeCacheRepository.save(any(EmployeeCache.class))).thenAnswer(inv -> inv.getArgument(0));

        consumer.consume(consumerRecord(uuid, payload), acknowledgment);

        verify(acknowledgment).acknowledge();
        verify(cdcFailureLogService, never()).logFailure(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void consume_deletesRow_onRewriteModeDeleteEvent() {
        String uuid = "3f1b2c4d-0000-0000-0000-000000000005";
        EmployeeCache existing = EmployeeCache.builder().employeeUuid(uuid).employeeId("5100105").build();
        String payload = """
                {"employee_uuid":"%s","employee_id":"5100105","__op":"d","__deleted":"true"}
                """.formatted(uuid);
        when(employeeCacheRepository.findByEmployeeUuid(uuid)).thenReturn(Optional.of(existing));

        consumer.consume(consumerRecord(uuid, payload), acknowledgment);

        verify(employeeCacheRepository).delete(existing);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_acknowledgesWithoutError_whenDeleteEventReferencesUnknownEmployee() {
        String uuid = "3f1b2c4d-0000-0000-0000-000000000006";
        String payload = """
                {"employee_uuid":"%s","__op":"d","__deleted":"true"}
                """.formatted(uuid);
        when(employeeCacheRepository.findByEmployeeUuid(uuid)).thenReturn(Optional.empty());

        consumer.consume(consumerRecord(uuid, payload), acknowledgment);

        verify(employeeCacheRepository, never()).delete(any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_acknowledgesAndDoesNothingElse_onTrueTombstone() {
        consumer.consume(consumerRecord("some-key", null), acknowledgment);

        verify(employeeCacheRepository, never()).findByEmployeeUuid(any());
        verify(employeeCacheRepository, never()).delete(any());
        verify(cdcFailureLogService, never()).logFailure(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consume_recordsParseFailure_onMalformedPayload_andStillAcknowledges() {
        String malformed = "not-valid-json{{{";

        consumer.consume(consumerRecord("some-key", malformed), acknowledgment);

        verify(cdcFailureLogService).logFailure(
                eq(TOPIC), isNull(), isNull(), eq("unknown"), eq("PARSE_FAILED"), anyString(), eq(malformed), eq(0), eq(42L));
        verify(acknowledgment).acknowledge();
        verify(employeeCacheRepository, never()).save(any());
    }

    @Test
    void consume_recordsValidationFailure_whenEmployeeUuidIsMissing() {
        String payload = """
                {"employee_id":"5100107","__op":"c","__deleted":"false"}
                """;

        consumer.consume(consumerRecord("some-key", payload), acknowledgment);

        verify(cdcFailureLogService).logFailure(
                eq(TOPIC), eq("5100107"), isNull(), eq("c"), eq("VALIDATION_FAILED"), anyString(), eq(payload), eq(0), eq(42L));
        verify(acknowledgment).acknowledge();
        verify(employeeCacheRepository, never()).save(any());
    }

    @Test
    void consume_recordsUpsertFailure_whenRepositoryThrows_andStillAcknowledges() {
        String uuid = "3f1b2c4d-0000-0000-0000-000000000008";
        String payload = """
                {"employee_uuid":"%s","employee_id":"5100108","__op":"c","__deleted":"false"}
                """.formatted(uuid);
        when(employeeCacheRepository.findByEmployeeUuid(uuid)).thenReturn(Optional.empty());
        when(employeeCacheRepository.save(any(EmployeeCache.class))).thenThrow(new RuntimeException("DB unavailable"));

        consumer.consume(consumerRecord(uuid, payload), acknowledgment);

        verify(cdcFailureLogService).logFailure(
                eq(TOPIC), eq("5100108"), eq(uuid), eq("c"), eq("UPSERT_FAILED"), eq("DB unavailable"), eq(payload), eq(0), eq(42L));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void handleUpsert_parsesEpochDayJoiningDate() {
        String uuid = "3f1b2c4d-0000-0000-0000-000000000009";
        long epochDay = LocalDate.of(2026, 1, 15).toEpochDay();
        String payload = """
                {"employee_uuid":"%s","employee_id":"5100109","joining_date":"%d","__op":"c","__deleted":"false"}
                """.formatted(uuid, epochDay);
        when(employeeCacheRepository.findByEmployeeUuid(uuid)).thenReturn(Optional.empty());
        when(employeeCacheRepository.save(any(EmployeeCache.class))).thenAnswer(inv -> inv.getArgument(0));

        consumer.consume(consumerRecord(uuid, payload), acknowledgment);

        ArgumentCaptor<EmployeeCache> captor = ArgumentCaptor.forClass(EmployeeCache.class);
        verify(employeeCacheRepository).save(captor.capture());
        assertThat(captor.getValue().getJoiningDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void handleUpsert_leavesJoiningDateNull_whenUnparseable() {
        String uuid = "3f1b2c4d-0000-0000-0000-00000000000a";
        String payload = """
                {"employee_uuid":"%s","employee_id":"5100110","joining_date":"not-a-date","__op":"c","__deleted":"false"}
                """.formatted(uuid);
        when(employeeCacheRepository.findByEmployeeUuid(uuid)).thenReturn(Optional.empty());
        when(employeeCacheRepository.save(any(EmployeeCache.class))).thenAnswer(inv -> inv.getArgument(0));

        consumer.consume(consumerRecord(uuid, payload), acknowledgment);

        ArgumentCaptor<EmployeeCache> captor = ArgumentCaptor.forClass(EmployeeCache.class);
        verify(employeeCacheRepository).save(captor.capture());
        assertThat(captor.getValue().getJoiningDate()).isNull();
    }
}
