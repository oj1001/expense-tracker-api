package com.morrow.expenses.expense;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExpenseService {
    private final ExpenseRepository repository;
    private final PhonePeStatementImportService statementImportService;

    public ExpenseService(
            ExpenseRepository repository,
            PhonePeStatementImportService statementImportService) {
        this.repository = repository;
        this.statementImportService = statementImportService;
    }

    @Transactional(readOnly = true)
    public List<ExpenseResponse> list() {
        return repository.findAllByOrderBySpentAtDesc().stream()
                .map(ExpenseResponse::from)
                .toList();
    }

    @Transactional
    public ExpenseResponse create(CreateExpenseRequest request) {
        Expense expense = new Expense(
                request.description().trim(),
                request.amount(),
                request.category(),
                request.spentAt());
        return ExpenseResponse.from(repository.save(expense));
    }

    public ExpenseImportResponse importStatement(MultipartFile file) {
        return statementImportService.importStatement(file);
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found");
        }
        repository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public MonthlySummaryResponse summarize(YearMonth month) {
        Instant start = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<Expense> expenses = repository.findBySpentAtGreaterThanEqualAndSpentAtLessThan(start, end);

        Map<ExpenseCategory, BigDecimal> categoryTotals = new EnumMap<>(ExpenseCategory.class);
        Arrays.stream(ExpenseCategory.values())
                .forEach(category -> categoryTotals.put(category, BigDecimal.ZERO));
        BigDecimal total = expenses.stream()
                .map(Expense::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        expenses.forEach(expense -> categoryTotals.merge(
                expense.getCategory(), expense.getAmount(), BigDecimal::add));

        return new MonthlySummaryResponse(month, total, expenses.size(), categoryTotals);
    }
}
