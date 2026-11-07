package com.morrow.expenses.expense;

import java.util.List;

public record ExpenseImportResponse(
        int importedCount,
        int skippedCount,
        List<String> errors,
        List<ExpenseResponse> expenses) {
}
