package com.expense_management_service.service.impl;

import com.expense_management_service.integration.intranet.IntranetClient;
import com.expense_management_service.security.CurrentUserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntranetChatToolsTest {

    @Mock private IntranetClient client;
    @Mock private CurrentUserService currentUserService;

    private final ObjectMapper mapper = new ObjectMapper();
    private IntranetChatTools tools;
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @BeforeEach
    void setUp() {
        tools = new IntranetChatTools(client, currentUserService);
        ReflectionTestUtils.setField(tools, "businessTimeZone", "Asia/Kolkata");
    }

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    @Test
    void handlesOnlyItsOwnTools() {
        assertThat(tools.handles(IntranetChatTools.LEAVE_BALANCE)).isTrue();
        assertThat(tools.handles(ChatbotTools.MY_EXPENSE_REPORTS)).isFalse();
    }

    @Test
    void leaveBalance_usesTheCallersOwnUserId_andUnwrapsData() throws Exception {
        when(currentUserService.getUmsUserId()).thenReturn(42L);
        when(client.leaveBalance(eq(42L), anyInt()))
                .thenReturn(json("{\"data\":[{\"leaveName\":\"Earned Leave\",\"remainingBalance\":14.75}]}"));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tools.execute(IntranetChatTools.LEAVE_BALANCE);

        verify(client).leaveBalance(42L, today.getYear());
        assertThat(out.get("leaveTypes").toString()).contains("Earned Leave").contains("14.75");
    }

    @Test
    void holidays_keepsOnlyUpcomingOnes_soonestFirst() throws Exception {
        String past = today.minusDays(3).toString();
        String later = today.plusDays(20).toString();
        String soon = today.plusDays(2).toString();
        when(client.holidays(anyInt())).thenReturn(json("[{\"holidayName\":\"Old\",\"holidayDate\":\"" + past + "\"},"
                + "{\"holidayName\":\"Later\",\"holidayDate\":\"" + later + "\"},"
                + "{\"holidayName\":\"Soon\",\"holidayDate\":\"" + soon + "\"}]"));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tools.execute(IntranetChatTools.HOLIDAYS);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> upcoming = (List<Map<String, Object>>) out.get("upcoming");

        assertThat(upcoming).extracting(h -> h.get("name")).containsExactly("Soon", "Later");
    }

    @Test
    void celebrations_areSortedByNextOccurrence() throws Exception {
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy");
        when(client.celebrations()).thenReturn(json("{\"birthdays\":["
                + "{\"name\":\"Far\",\"date\":\"" + today.plusDays(40).format(f) + "\"},"
                + "{\"name\":\"Today\",\"date\":\"" + today.format(f) + "\"}],"
                + "\"workAnniversaries\":[{\"name\":\"Suresh\",\"date\":\"" + today.plusDays(5).format(f) + "\",\"anniversaryYear\":3}],"
                + "\"newJoinees\":[]}"));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tools.execute(IntranetChatTools.CELEBRATIONS);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> birthdays = (List<Map<String, Object>>) out.get("birthdays");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> anniversaries = (List<Map<String, Object>>) out.get("workAnniversaries");

        assertThat(birthdays).extracting(b -> b.get("name")).containsExactly("Today", "Far");
        assertThat(birthdays.get(0).get("inDays")).isEqualTo(0L);
        assertThat(anniversaries.get(0).get("years")).isEqualTo(3);
    }

    @Test
    void workSummary_usesTheCallersOwnUserId() throws Exception {
        when(currentUserService.getUmsUserId()).thenReturn(7L);
        when(client.workSummary(7L)).thenReturn(json("{\"activeProjectCount\":2}"));

        assertThat(tools.execute(IntranetChatTools.WORK_SUMMARY).toString()).contains("activeProjectCount").contains("2");
    }

    @Test
    void offersAllTwelveModuleTools() {
        assertThat(tools.definitions()).hasSize(12);
        for (String name : List.of(IntranetChatTools.FIND_COLLEAGUE, IntranetChatTools.BENCH_SUMMARY,
                IntranetChatTools.USERS_AND_ROLES, IntranetChatTools.RECRUITMENT_SUMMARY,
                IntranetChatTools.PAYABLES_SUMMARY, IntranetChatTools.RECEIVABLES_SUMMARY)) {
            assertThat(tools.handles(name)).as(name).isTrue();
        }
    }

    @Test
    void findColleague_returnsOnlyDirectoryFields_andAtMostFiveMatches() throws Exception {
        StringBuilder people = new StringBuilder("[");
        for (int i = 1; i <= 7; i++) {
            if (i > 1) people.append(',');
            people.append("{\"employee_id\":\"E").append(i).append("\",\"first_name\":\"Ravi\",\"last_name\":\"Kumar").append(i)
                    .append("\",\"work_email\":\"ravi").append(i).append("@paves.com\",\"personal_email\":\"secret@gmail.com\",")
                    .append("\"contact_number\":\"9999999999\",\"date_of_birth\":\"1990-01-01\",")
                    .append("\"designation_uuid\":\"d1\",\"department_uuid\":\"p1\",\"location\":\"Hyderabad\"}");
        }
        people.append(",{\"employee_id\":\"E9\",\"first_name\":\"Asha\",\"last_name\":\"Rao\"}]");
        when(client.coreEmployeeDetails()).thenReturn(json("{\"data\":" + people + "}"));
        when(client.departments()).thenReturn(json("[{\"department_uuid\":\"p1\",\"department_name\":\"Engineering\"}]"));
        when(client.designations()).thenReturn(json("[{\"designation_uuid\":\"d1\",\"designation_name\":\"Developer\"}]"));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tools.execute(IntranetChatTools.FIND_COLLEAGUE, Map.of("name", "ravi"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> found = (List<Map<String, Object>>) out.get("people");

        assertThat(out.get("matches")).isEqualTo(7);
        assertThat(found).hasSize(5);
        assertThat(found.get(0)).containsEntry("workEmail", "ravi1@paves.com")
                .containsEntry("designation", "Developer").containsEntry("department", "Engineering")
                .containsOnlyKeys("name", "employeeId", "workEmail", "designation", "department", "location");
        assertThat(out.toString()).doesNotContain("secret@gmail.com").doesNotContain("9999999999").doesNotContain("1990-01-01");
    }

    @Test
    void findColleague_needsAName_andNeverListsTheWholeDirectory() {
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tools.execute(IntranetChatTools.FIND_COLLEAGUE, Map.of());

        assertThat(out).containsKey("error");
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void aModuleThatIsNotConnected_saysSo_withoutFailing() {
        when(client.payablesSummary()).thenThrow(new IntranetClient.ModuleNotConnectedException("Accounts Payable"));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tools.execute(IntranetChatTools.PAYABLES_SUMMARY);

        assertThat((String) out.get("error")).contains("Accounts Payable is not connected");
    }

    @Test
    void aModuleThatRefusesTheUser_saysTheyHaveNoAccess() {
        when(client.benchKpis()).thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.FORBIDDEN, "Forbidden", null, null, null));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tools.execute(IntranetChatTools.BENCH_SUMMARY);

        assertThat((String) out.get("error")).contains("does not have access");
    }

    @Test
    void whenOnePartFails_theOtherPartStillAnswers() throws Exception {
        when(client.recruitmentStats()).thenReturn(json("{\"data\":{\"openCampaigns\":4}}"));
        when(client.hiringFunnel(30)).thenThrow(new RuntimeException("AIRS timeout"));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tools.execute(IntranetChatTools.RECRUITMENT_SUMMARY);

        assertThat(out.get("stats").toString()).contains("openCampaigns").contains("4");
        assertThat(out.get("hiringFunnelLast30Days").toString()).contains("could not be loaded");
    }

    @Test
    void usersAndRoles_passesTheSearch_andKeepsOnlyNameEmailRoles() throws Exception {
        when(client.usersWithRoles("asha", 10)).thenReturn(json(
                "{\"total\":1,\"users\":[{\"name\":\"Asha Rao\",\"mail\":\"asha@paves.com\",\"roles\":[\"Manager\"],\"password_hash\":\"x\"}]}"));
        when(client.roles()).thenReturn(json("[{\"role_name\":\"Manager\"}]"));

        String out = tools.execute(IntranetChatTools.USERS_AND_ROLES, Map.of("search", "asha")).toString();

        assertThat(out).contains("Asha Rao").contains("asha@paves.com").contains("Manager").doesNotContain("password_hash");
    }

    @Test
    void receivables_looksUpInvoicesOnlyWhenSearched_andTrimsLongLists() throws Exception {
        StringBuilder items = new StringBuilder("[");
        for (int i = 0; i < 25; i++) items.append(i == 0 ? "" : ",").append("{\"invoice\":\"INV-").append(i).append("\"}");
        items.append("]");
        when(client.receivablesSummary("MTD")).thenReturn(json("{\"data\":{\"kpis\":{\"outstanding\":1200},\"worklist\":" + items
                + ",\"trend\":[1,2,3]}}"));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tools.execute(IntranetChatTools.RECEIVABLES_SUMMARY);
        JsonNode summary = (JsonNode) mapper.valueToTree(out.get("summaryThisMonth"));

        assertThat(summary.path("kpis").path("outstanding").asInt()).isEqualTo(1200);
        assertThat(summary.path("worklist").size()).isEqualTo(10);
        assertThat(summary.has("trend")).isFalse();
        assertThat(out).doesNotContainKey("invoices");
        verify(client, org.mockito.Mockito.never()).receivablesInvoices(org.mockito.ArgumentMatchers.any(), anyInt());
    }

    @Test
    void anEmptyServiceResponse_doesNotFailTheTool() {
        when(client.todayOnLeave()).thenReturn(null);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tools.execute(IntranetChatTools.ON_LEAVE_TODAY);

        assertThat(out.get("count")).isEqualTo(0);
    }
}
