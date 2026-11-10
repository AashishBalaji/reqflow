package com.reqflow.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BudgetLineControllerTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void duplicateBudgetLineReturns409InsteadOf500() throws Exception {
        String body = """
            {"department":"Engineering","category":"office_supplies","fiscalYear":2099,"allocatedAmount":1000.00}
            """;

        mockMvc.perform(post("/api/budget-lines").with(httpBasic("reviewer", "reqflow-dev"))
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated());

        // Same department/category/fiscalYear again -- violates the unique
        // constraint. Should come back as a clean 409, not a raw 500.
        mockMvc.perform(post("/api/budget-lines").with(httpBasic("reviewer", "reqflow-dev"))
                        .contentType("application/json").content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void createWithoutAuthIsRejected() throws Exception {
        String body = """
            {"department":"Sales","category":"office_supplies","fiscalYear":2099,"allocatedAmount":1000.00}
            """;

        mockMvc.perform(post("/api/budget-lines").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
    }
}
