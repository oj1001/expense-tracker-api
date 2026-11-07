package com.morrow.expenses.expense;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {
    List<Expense> findAllByOrderBySpentAtDesc();

    boolean existsByExternalTransactionId(String externalTransactionId);

    List<Expense> findBySpentAtGreaterThanEqualAndSpentAtLessThan(
            Instant start,
            Instant end);
}
