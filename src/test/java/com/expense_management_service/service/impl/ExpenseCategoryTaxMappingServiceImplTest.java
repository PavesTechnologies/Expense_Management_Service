package com.expense_management_service.service.impl;

import com.expense_management_service.dto.request.ExpenseCategoryTaxMappingRequest;
import com.expense_management_service.dto.response.ExpenseCategoryTaxMappingResponse;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseCategoryTaxMapping;
import com.expense_management_service.entity.TaxCode;
import com.expense_management_service.enums.AuditSource;
import com.expense_management_service.enums.TaxType;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.ExpenseCategoryTaxMappingRepository;
import com.expense_management_service.repository.TaxCodeRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.TaxAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpenseCategoryTaxMappingServiceImplTest {

    @Mock
    private ExpenseCategoryTaxMappingRepository mappingRepository;
    @Mock
    private ExpenseCategoryRepository expenseCategoryRepository;
    @Mock
    private TaxCodeRepository taxCodeRepository;
    @Mock
    private TaxAuditService taxAuditService;
    @Mock
    private CurrentUserService currentUserService;

    private ExpenseCategoryTaxMappingServiceImpl service;

    private final LocalDate today = LocalDate.now();
    private final UUID categoryId = UUID.randomUUID();
    private ExpenseCategory hotel;
    private TaxCode gst18;
    private TaxCode gst12;
    /** What the repository holds for the category; saves and deletes act on it. */
    private final List<ExpenseCategoryTaxMapping> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ExpenseCategoryTaxMappingServiceImpl(mappingRepository, expenseCategoryRepository, taxCodeRepository,
                taxAuditService, currentUserService);
        hotel = ExpenseCategory.builder().categoryId(categoryId).categoryName("Hotel").build();
        gst18 = code("GST18", "18.00");
        gst12 = code("GST12", "12.00");
        lenient().when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(hotel));
        lenient().when(currentUserService.getEmployeeId()).thenReturn("5100001");
        lenient().when(mappingRepository.findByCategory_CategoryIdOrderByEffectiveFromDesc(categoryId)).thenAnswer(inv ->
                stored.stream().sorted((a, b) -> b.getEffectiveFrom().compareTo(a.getEffectiveFrom())).toList());
        lenient().when(mappingRepository.save(any(ExpenseCategoryTaxMapping.class))).thenAnswer(inv -> {
            ExpenseCategoryTaxMapping m = inv.getArgument(0);
            if (m.getMappingId() == null) {
                m.setMappingId(UUID.randomUUID());
                stored.add(m);
            }
            return m;
        });
        lenient().doAnswer(inv -> stored.remove(inv.getArgument(0))).when(mappingRepository).delete(any(ExpenseCategoryTaxMapping.class));
        lenient().when(taxCodeRepository.findById(gst18.getTaxCodeId())).thenReturn(Optional.of(gst18));
        lenient().when(taxCodeRepository.findById(gst12.getTaxCodeId())).thenReturn(Optional.of(gst12));
    }

    private static TaxCode code(String code, String rate) {
        return TaxCode.builder().taxCodeId(UUID.randomUUID()).taxCode(code).taxName(code).taxType(TaxType.CGST_SGST)
                .ratePercent(new BigDecimal(rate)).effectiveFrom(LocalDate.of(2017, 7, 1)).status("ACTIVE").build();
    }

    private ExpenseCategoryTaxMapping existing(TaxCode code, LocalDate from, LocalDate to) {
        ExpenseCategoryTaxMapping m = ExpenseCategoryTaxMapping.builder().mappingId(UUID.randomUUID()).category(hotel)
                .taxCode(code).effectiveFrom(from).effectiveTo(to).build();
        stored.add(m);
        lenient().when(mappingRepository.findById(m.getMappingId())).thenReturn(Optional.of(m));
        return m;
    }

    @Test
    void create_addsAMapping_auditsIt_andMirrorsTodaysCodeOnTheCategory() {
        ExpenseCategoryTaxMappingResponse response = service.create(categoryId,
                new ExpenseCategoryTaxMappingRequest(gst18.getTaxCodeId(), today.minusDays(10), null));

        assertThat(response.state()).isEqualTo("CURRENT");
        assertThat(response.taxCode()).isEqualTo("GST18");
        assertThat(hotel.getTaxCode()).isEqualTo("GST18");
        verify(taxAuditService).record(eq("ExpenseCategoryTaxMapping"), any(), eq("TAX_MAPPING_ADDED"), eq(null), any(),
                eq(AuditSource.ADMIN), eq(null));
    }

    @Test
    void create_rejectsAnOverlappingWindow() {
        existing(gst18, today.minusDays(30), null);

        assertThatThrownBy(() -> service.create(categoryId,
                new ExpenseCategoryTaxMappingRequest(gst12.getTaxCodeId(), today.plusDays(30), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already mapped to GST18");
    }

    @Test
    void create_allowsAScheduledRateChange_afterTheCurrentMappingEnds() {
        existing(gst18, today.minusDays(30), today.plusDays(29));

        ExpenseCategoryTaxMappingResponse response = service.create(categoryId,
                new ExpenseCategoryTaxMappingRequest(gst12.getTaxCodeId(), today.plusDays(30), null));

        assertThat(response.state()).isEqualTo("SCHEDULED");
        assertThat(hotel.getTaxCode()).isEqualTo("GST18"); // still today's code
    }

    @Test
    void create_rejectsAnInactiveCode() {
        gst12.setStatus("INACTIVE");

        assertThatThrownBy(() -> service.create(categoryId,
                new ExpenseCategoryTaxMappingRequest(gst12.getTaxCodeId(), today, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not Active");
    }

    @Test
    void create_rejectsAMappingStartingBeforeTheCodeItself() {
        gst12.setEffectiveFrom(today);

        assertThatThrownBy(() -> service.create(categoryId,
                new ExpenseCategoryTaxMappingRequest(gst12.getTaxCodeId(), today.minusDays(1), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only takes effect");
    }

    @Test
    void update_startedMapping_canBeEnded_butNotRepointed() {
        ExpenseCategoryTaxMapping current = existing(gst18, today.minusDays(30), null);

        assertThatThrownBy(() -> service.update(categoryId, current.getMappingId(),
                new ExpenseCategoryTaxMappingRequest(gst12.getTaxCodeId(), today.minusDays(30), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only its end date can change");

        ExpenseCategoryTaxMappingResponse ended = service.update(categoryId, current.getMappingId(),
                new ExpenseCategoryTaxMappingRequest(gst18.getTaxCodeId(), today.minusDays(30), today.minusDays(1)));

        assertThat(ended.state()).isEqualTo("PAST");
        assertThat(hotel.getTaxCode()).isNull();
        verify(taxAuditService).record(eq("ExpenseCategoryTaxMapping"), eq(current.getMappingId()), eq("TAX_MAPPING_ENDED"),
                any(), any(), eq(AuditSource.ADMIN), eq(null));
    }

    @Test
    void update_startedMapping_cannotBeEndedFurtherBackThanYesterday() {
        ExpenseCategoryTaxMapping current = existing(gst18, today.minusDays(30), null);

        assertThatThrownBy(() -> service.update(categoryId, current.getMappingId(),
                new ExpenseCategoryTaxMappingRequest(gst18.getTaxCodeId(), today.minusDays(30), today.minusDays(5))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yesterday");
    }

    @Test
    void delete_onlyRemovesMappingsThatHaveNotTakenEffect() {
        ExpenseCategoryTaxMapping started = existing(gst18, today.minusDays(1), today.plusDays(9));
        ExpenseCategoryTaxMapping scheduled = existing(gst12, today.plusDays(10), null);

        assertThatThrownBy(() -> service.delete(categoryId, started.getMappingId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("end it instead");

        service.delete(categoryId, scheduled.getMappingId());

        verify(mappingRepository).delete(scheduled);
        verify(mappingRepository, never()).delete(started);
    }

    @Test
    void resolveTaxCode_usesTheMappingInEffectOnTheExpenseDate() {
        existing(gst18, LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31));
        existing(gst12, LocalDate.of(2027, 4, 1), null);

        assertThat(service.resolveTaxCode(categoryId, LocalDate.of(2027, 3, 15))).contains(gst18);
        assertThat(service.resolveTaxCode(categoryId, LocalDate.of(2027, 4, 1))).contains(gst12);
        assertThat(service.resolveTaxCode(categoryId, LocalDate.of(2026, 3, 31))).isEmpty();
    }

    @Test
    void resolveTaxCode_ignoresAMappedCodeThatIsNoLongerActive() {
        existing(gst18, LocalDate.of(2026, 4, 1), null);
        gst18.setStatus("INACTIVE");

        assertThat(service.resolveTaxCode(categoryId, today)).isEmpty();
    }

    @Test
    void replaceCurrentMapping_endsTheOldMappingYesterday_andMapsTheNewCodeUntilTheNextScheduledOne() {
        ExpenseCategoryTaxMapping current = existing(gst18, today.minusDays(30), today.plusDays(19));
        existing(gst18, today.plusDays(20), null);

        service.replaceCurrentMapping(hotel, gst12, today);

        assertThat(current.getEffectiveTo()).isEqualTo(today.minusDays(1));
        ArgumentCaptor<ExpenseCategoryTaxMapping> saved = ArgumentCaptor.forClass(ExpenseCategoryTaxMapping.class);
        verify(mappingRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        ExpenseCategoryTaxMapping added = saved.getAllValues().stream().filter(m -> m.getTaxCode() == gst12).findFirst().orElseThrow();
        assertThat(added.getEffectiveFrom()).isEqualTo(today);
        assertThat(added.getEffectiveTo()).isEqualTo(today.plusDays(19));
        assertThat(hotel.getTaxCode()).isEqualTo("GST12");
    }

    @Test
    void replaceCurrentMapping_removesAMappingThatOnlyStartedToday() {
        ExpenseCategoryTaxMapping sameDay = existing(gst18, today, null);

        service.replaceCurrentMapping(hotel, null, today);

        verify(mappingRepository).delete(sameDay);
        verify(taxAuditService).record(eq("ExpenseCategoryTaxMapping"), eq(sameDay.getMappingId()), eq("TAX_MAPPING_REMOVED"),
                any(), eq(null), eq(AuditSource.ADMIN), eq(null));
    }
}
