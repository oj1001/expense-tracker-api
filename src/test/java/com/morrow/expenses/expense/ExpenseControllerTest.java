package com.morrow.expenses.expense;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ExpenseControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void createsExpenseAndIncludesItInMonthlySummary() throws Exception {
        mockMvc.perform(post("/api/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "Market",
                                  "amount": 125.50,
                                  "category": "FOOD",
                                  "spentAt": "2026-11-06T12:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description", is("Market")))
                .andExpect(jsonPath("$.category", is("FOOD")));

        mockMvc.perform(get("/api/summary").param("month", "2026-11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(125.5)))
                .andExpect(jsonPath("$.transactionCount", is(1)))
                .andExpect(jsonPath("$.categoryTotals.FOOD", is(125.5)));
    }

    @Test
    void rejectsNonPositiveExpenseAmount() throws Exception {
        mockMvc.perform(post("/api/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "Invalid",
                                  "amount": 0,
                                  "category": "FOOD",
                                  "spentAt": "2026-11-06T12:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
