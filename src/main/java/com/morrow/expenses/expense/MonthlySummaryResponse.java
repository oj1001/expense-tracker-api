package com.morrow.expenses.expense;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;

public record MonthlySummaryResponse(
        YearMonth month,
        BigDecimal total,
        int transactionCount,
        Map<ExpenseCategory, BigDecimal> categoryTotals) {
}
