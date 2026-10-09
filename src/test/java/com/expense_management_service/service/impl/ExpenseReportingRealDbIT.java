package com.expense_management_service.service.impl;

import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import jakarta.persistence.EntityManager;
import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Read-only check that the reporting and team queries (hand-written JPQL plus the new repository
 * queries) run against the real schema. SELECTs only - same real-DB setup as RealDbSmokeIT, and
 * named *IT so a normal build never runs it.
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(initializers = DotenvApplicationInitializer.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExpenseReportingRealDbIT {

    @Autowired private EntityManager entityManager;
    @Autowired private EmployeeCacheRepository employeeCacheRepository;
    @Autowired private ExpenseReportRepository expenseReportRepository;

    private ExpenseReportingServiceImpl serviceAs(String employeeId, String... roles) {
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.getCurrentUser()).thenReturn(
                new CurrentUser(UUID.randomUUID(), null, employeeId, "it@example.com", "IT", List.of(roles), List.of()));
        ExpenseReportingServiceImpl service = new ExpenseReportingServiceImpl(currentUserService, employeeCacheRepository);
        ReflectionTestUtils.setField(service, "em", entityManager);
        ReflectionTestUtils.setField(service, "baseCurrencyCode", "INR");
        return service;
    }

    @Test
    void organizationWideSummary_runsAgainstTheRealSchema() {
        var report = serviceAs("it-admin", "ADMIN").summary(LocalDate.now().minusMonths(35).withDayOfMonth(1), LocalDate.now());

        System.out.println("ORG totals=" + report.totals() + " categories=" + report.byCategory().size()
                + " costCenters=" + report.byCostCenter().size() + " departments=" + report.byDepartment().size()
                + " advances=" + report.cashAdvances() + " reimbursements=" + report.reimbursements());
        assertThat(report.scope()).isEqualTo("ORGANIZATION");
        assertThat(report.byMonth()).hasSize(36);
        assertThat(report.byStatus()).noneMatch(r -> r.key().equals("DRAFT"));
    }

    @Test
    void teamSummary_andTeamQueries_runForARealManager() {
        String manager = employeeCacheRepository.findAll().stream()
                .map(EmployeeCache::getManagerEmployeeId).filter(id -> id != null && !id.isBlank())
                .findFirst().orElse("no-such-manager");

        var report = serviceAs(manager, "MANAGER").summary(null, null);
        System.out.println("TEAM(" + manager + ") totals=" + report.totals());
        assertThat(report.scope()).isEqualTo("TEAM");

        Set<String> team = employeeCacheRepository.findByManagerEmployeeId(manager).stream()
                .map(EmployeeCache::getEmployeeId).collect(Collectors.toSet());
        if (!team.isEmpty()) {
            var page = expenseReportRepository.searchByEmployeeIds(team, ReportStatus.DRAFT, null, "a", PageRequest.of(0, 5));
            var all = expenseReportRepository.findByEmployeeIdInAndReportStatusNot(team, ReportStatus.DRAFT);
            System.out.println("TEAM reports page=" + page.getTotalElements() + " all=" + all.size());
            assertThat(all).noneMatch(r -> r.getReportStatus() == ReportStatus.DRAFT);
        }
        assertThat(employeeCacheRepository.findByEmploymentStatusIgnoreCase("Active")).isNotNull();
        assertThat(employeeCacheRepository.findByEmployeeIdIn(team.isEmpty() ? Set.of("none") : team)).isNotNull();
    }
}
