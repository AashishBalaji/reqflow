package com.reqflow.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Hits the REST surface end to end through H2 -- the same surface the AI
 * triage agent calls as tools, so these assertions double as a contract
 * test for that agent. POSTs authenticate with the default dev
 * credentials from application.yml (reviewer/reqflow-dev); GETs stay
 * unauthenticated to match SecurityConfig.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RequisitionControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void listsSeededRequisitions() throws Exception {
        mockMvc.perform(get("/api/requisitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(4))));
    }

    @Test
    void filtersByStatus() throws Exception {
        mockMvc.perform(get("/api/requisitions").param("status", "PENDING_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].status", everyItem(is("PENDING_REVIEW"))));
    }

    @Test
    void createAutoApprovesWithinPolicy() throws Exception {
        String body = """
            {"requesterName":"Test","department":"Engineering","vendorId":2,
             "category":"it_equipment","amount":100.00,"justification":"cables"}
            """;

        mockMvc.perform(post("/api/requisitions").with(httpBasic("reviewer", "reqflow-dev"))
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AUTO_APPROVED"));
    }

    @Test
    void createWithoutAuthIsRejected() throws Exception {
        String body = """
            {"requesterName":"Test","department":"Engineering","vendorId":2,
             "category":"it_equipment","amount":100.00,"justification":"cables"}
            """;

        mockMvc.perform(post("/api/requisitions").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createRejectsMissingRequesterName() throws Exception {
        String body = """
            {"requesterName":"","department":"Engineering","vendorId":2,
             "category":"it_equipment","amount":100.00,"justification":"cables"}
            """;

        mockMvc.perform(post("/api/requisitions").with(httpBasic("reviewer", "reqflow-dev"))
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.requesterName").exists());
    }

    @Test
    void approveEndpointDecidesAPendingRequisition() throws Exception {
        String decision = """
            {"decidedBy":"agent:claude-triage-v1","reason":"reviewed manually"}
            """;

        // requisition id 2 is seeded as PENDING_REVIEW (vendor risk) in data.sql
        mockMvc.perform(post("/api/requisitions/2/approve").with(httpBasic("reviewer", "reqflow-dev"))
                        .contentType("application/json").content(decision))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value("agent:claude-triage-v1"));
    }

    @Test
    void unknownRequisitionReturns404() throws Exception {
        mockMvc.perform(get("/api/requisitions/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void responsesCarryTheActivityLog() throws Exception {
        // requisition id 1 is seeded AUTO_APPROVED in data.sql, with matching
        // SUBMITTED + AUTO_APPROVED seed events.
        mockMvc.perform(get("/api/requisitions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(2)))
                .andExpect(jsonPath("$.events[0].activity").value("SUBMITTED"))
                .andExpect(jsonPath("$.events[1].activity").value("AUTO_APPROVED"));
    }

    @Test
    void openEndpointRecordsAnEventWithoutDeciding() throws Exception {
        // requisition id 2 is seeded PENDING_REVIEW with 2 seed events
        // (SUBMITTED, FLAGGED_FOR_REVIEW) in data.sql, so opening it adds a
        // third at index 2.
        mockMvc.perform(post("/api/requisitions/2/open").with(httpBasic("reviewer", "reqflow-dev")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.events", hasSize(3)))
                .andExpect(jsonPath("$.events[2].activity").value("OPENED_FOR_REVIEW"));
    }

    @Test
    void openWithoutAuthIsRejected() throws Exception {
        mockMvc.perform(post("/api/requisitions/2/open"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void openingAnAlreadyDecidedRequisitionIsAConflict() throws Exception {
        // requisition id 1 is seeded AUTO_APPROVED, not PENDING_REVIEW.
        mockMvc.perform(post("/api/requisitions/1/open").with(httpBasic("reviewer", "reqflow-dev")))
                .andExpect(status().isConflict());
    }
}
