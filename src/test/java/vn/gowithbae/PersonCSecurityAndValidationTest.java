package vn.gowithbae;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import vn.gowithbae.api.AdminPlaceReportsApiController;
import vn.gowithbae.api.AdminPlaceReportsApiDelegate;
import vn.gowithbae.api.AdminSupportApiController;
import vn.gowithbae.api.AdminSupportApiDelegate;
import vn.gowithbae.api.ChoiceSessionsApiController;
import vn.gowithbae.api.ChoiceSessionsApiDelegate;
import vn.gowithbae.api.DatePlansApiController;
import vn.gowithbae.api.DatePlansApiDelegate;
import vn.gowithbae.api.MeApiController;
import vn.gowithbae.api.MeApiDelegate;
import vn.gowithbae.api.PlacesApiController;
import vn.gowithbae.api.PlacesApiDelegate;
import vn.gowithbae.api.ShareGrantsApiController;
import vn.gowithbae.api.ShareGrantsApiDelegate;
import vn.gowithbae.api.SupportApiController;
import vn.gowithbae.api.SupportApiDelegate;
import vn.gowithbae.api.model.DateReminderState;
import vn.gowithbae.api.model.PageMeta;
import vn.gowithbae.api.model.PlaceReportPage;
import vn.gowithbae.api.model.SupportTicketPage;
import vn.gowithbae.common.ApiExceptionHandler;
import vn.gowithbae.config.SecurityConfig;

/** Kiểm tra Spring SecurityConfig, Bean Validation và RFC 9457 Problem cho các endpoint Người 3 (không cần Docker). */
@WebMvcTest(controllers = {
        ChoiceSessionsApiController.class,
        ShareGrantsApiController.class,
        DatePlansApiController.class,
        SupportApiController.class,
        AdminSupportApiController.class,
        AdminPlaceReportsApiController.class,
        PlacesApiController.class,
        MeApiController.class
}, properties = {
        "app.jwt.secret=test-secret-test-secret-test-secret-123",
        "DB_URL=", "DB_USERNAME=", "DB_PASSWORD="
})
@Import({ SecurityConfig.class, ApiExceptionHandler.class })
class PersonCSecurityAndValidationTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean ChoiceSessionsApiDelegate choiceSessionsDelegate;
    @MockitoBean ShareGrantsApiDelegate shareGrantsDelegate;
    @MockitoBean DatePlansApiDelegate datePlansDelegate;
    @MockitoBean SupportApiDelegate supportDelegate;
    @MockitoBean AdminSupportApiDelegate adminSupportDelegate;
    @MockitoBean AdminPlaceReportsApiDelegate adminPlaceReportsDelegate;
    @MockitoBean PlacesApiDelegate placesDelegate;
    @MockitoBean MeApiDelegate meDelegate;

    @Test
    void reminderEndpointsPermitGuestSessionHeadersThroughSecurityFilter() throws Exception {
        UUID planId = UUID.randomUUID();
        when(datePlansDelegate.getDateReminder(planId))
                .thenReturn(ResponseEntity.ok(new DateReminderState(null, false, false)));
        when(datePlansDelegate.respondToDateReminder(any(), any()))
                .thenReturn(ResponseEntity.ok(new DateReminderState(null, false, false)));

        mvc.perform(get("/api/v1/date-plans/{id}/reminder", planId)
                        .header("X-Recipient-Session", "sample-guest-session-token-1234567890123456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shouldShow").value(false))
                .andExpect(jsonPath("$.canRespond").value(false));

        mvc.perform(put("/api/v1/date-plans/{id}/reminder", planId)
                        .header("X-Recipient-Session", "sample-guest-session-token-1234567890123456")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"DISMISS\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void privateMemberEndpointsRequireAuthentication() throws Exception {
        mvc.perform(post("/api/v1/date-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\":\"" + UUID.randomUUID() + "\",\"scheduledAt\":\"2027-01-01T19:00:00+07:00\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/me/places/{placeId}/visit-count", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/support-tickets"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminPlaceReportsAndSupportEnforceRoleIsolation() throws Exception {
        when(adminPlaceReportsDelegate.adminListPlaceReports(any(), any(), any(), any()))
                .thenReturn(ResponseEntity.ok(new PlaceReportPage(List.of(), new PageMeta(0, 20, 0L, 0))));
        when(adminSupportDelegate.adminListTickets(any(), any(), any(), any()))
                .thenReturn(ResponseEntity.ok(new SupportTicketPage(List.of(), new PageMeta(0, 20, 0L, 0))));

        mvc.perform(get("/api/v1/admin/place-reports")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPPORT"))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/admin/place-reports")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REVIEWER"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/support-tickets")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REVIEWER"))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/admin/support-tickets")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPPORT"))))
                .andExpect(status().isOk());
    }

    @Test
    void validationErrorsProduceRfc9457ErrorsArray() throws Exception {
        mvc.perform(post("/api/v1/choice-sessions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MEMBER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeIds\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].field").exists());

        mvc.perform(get("/api/v1/share-grants/{token}", "too-short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].field").value("inviteToken"));
    }

    @Test
    void downloadCalendarReturnsTextCalendarHeaders() throws Exception {
        UUID planId = UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "calendar", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment().filename("date-plan-" + planId + ".ics").build());
        headers.setCacheControl("no-store");
        when(datePlansDelegate.downloadDatePlanCalendar(planId))
                .thenReturn(new ResponseEntity<>("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", headers, HttpStatus.OK));

        mvc.perform(get("/api/v1/date-plans/{id}/calendar.ics", planId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"date-plan-" + planId + ".ics\""))
                .andExpect(content().string("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"));
    }
}
