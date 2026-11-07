package com.morrow.expenses.expense;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PhonePeStatementImportService {
    private static final int MAX_ROWS = 5_000;
    private static final int MAX_ERRORS = 20;
    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("d/M/uuuu H:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("d/M/uuuu H:mm", Locale.ROOT),
            DateTimeFormatter.ofPattern("d/M/uuuu h:mm:ss a", Locale.ROOT),
            DateTimeFormatter.ofPattern("d/M/uuuu h:mm a", Locale.ROOT),
            DateTimeFormatter.ofPattern("d-M-uuuu H:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("d-M-uuuu H:mm", Locale.ROOT),
            DateTimeFormatter.ofPattern("d MMM uuuu HH:mm:ss", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM uuuu HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM uuuu, h:mm a", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, uuuu, h:mm a", Locale.ENGLISH),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ROOT),
            DateTimeFormatter.ofPattern("d/M/uu", Locale.ROOT),
            DateTimeFormatter.ofPattern("d-M-uuuu", Locale.ROOT),
            DateTimeFormatter.ofPattern("d-M-uu", Locale.ROOT),
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM, uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH),
            DateTimeFormatter.ISO_LOCAL_DATE);

    private final ExpenseRepository repository;

    public PhonePeStatementImportService(ExpenseRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ExpenseImportResponse importStatement(MultipartFile file) {
        if (file.isEmpty()) {
            throw badRequest("Choose a non-empty statement file");
        }

        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw badRequest("The statement file must have a filename");
        }

        List<List<String>> rows;
        String extension = filename.substring(filename.lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT);
        try {
            rows = switch (extension) {
                case "csv" -> readCsv(file);
                case "xls", "xlsx" -> readWorkbook(file);
                default -> throw badRequest("Use a CSV, XLS, or XLSX statement file");
            };
        } catch (IOException | InvalidFormatException | IllegalArgumentException
                | EncryptedDocumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Could not read the statement file", exception);
        }

        Header header = findHeader(rows);
        List<Expense> imported = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> seenTransactionIds = new HashSet<>();
        int skippedCount = 0;

        for (int index = header.rowIndex() + 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }

            try {
                String description = value(row, header.descriptionColumn()).trim();
                String status = value(row, header.statusColumn()).toLowerCase(Locale.ROOT);
                String direction = value(row, header.directionColumn()).toLowerCase(Locale.ROOT);
                if (isIncoming(direction, description)
                        || containsAny(status, "failed", "pending", "cancelled", "canceled",
                                "reversed", "declined")) {
                    skippedCount++;
                    continue;
                }

                BigDecimal amount = parseAmount(value(row, header.amountColumn()));
                Instant spentAt = parseDate(value(row, header.dateColumn()));
                String transactionId = trimToNull(value(row, header.transactionIdColumn()));
                if (description.isBlank() || description.length() > 120) {
                    throw new IllegalArgumentException("Invalid transaction description");
                }

                boolean duplicateInFile = transactionId != null
                        && !seenTransactionIds.add(transactionId);
                if (transactionId != null && transactionId.length() > 120) {
                    throw new IllegalArgumentException("Invalid transaction ID");
                }
                if (duplicateInFile
                        || (transactionId != null
                                && repository.existsByExternalTransactionId(transactionId))) {
                    skippedCount++;
                    continue;
                }

                imported.add(new Expense(
                        description,
                        amount,
                        categoryFor(description),
                        spentAt,
                        transactionId));
            } catch (IllegalArgumentException | DateTimeParseException exception) {
                skippedCount++;
                if (errors.size() < MAX_ERRORS) {
                    errors.add("Row " + (index + 1) + ": " + exception.getMessage());
                }
            }
        }

        List<ExpenseResponse> saved = repository.saveAll(imported).stream()
                .map(ExpenseResponse::from)
                .toList();
        return new ExpenseImportResponse(saved.size(), skippedCount, errors, saved);
    }

    private List<List<String>> readCsv(MultipartFile file) throws IOException {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }

        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (quoted) {
                if (character == '"' && index + 1 < content.length()
                        && content.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else if (character == '"') {
                    quoted = false;
                } else {
                    field.append(character);
                }
            } else if (character == '"' && field.isEmpty()) {
                quoted = true;
            } else if (character == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (character == '\n' || character == '\r') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                if (character == '\r' && index + 1 < content.length()
                        && content.charAt(index + 1) == '\n') {
                    index++;
                }
            } else {
                field.append(character);
            }
        }

        if (quoted) {
            throw badRequest("The CSV file contains an unclosed quoted field");
        }
        if (!row.isEmpty() || !field.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        if (rows.size() > MAX_ROWS + 25) {
            throw badRequest("The statement contains too many rows (maximum 5,000)");
        }
        return rows;
    }

    private List<List<String>> readWorkbook(MultipartFile file)
            throws IOException, InvalidFormatException {
        try (var input = file.getInputStream();
                Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw badRequest("The workbook has no sheets");
            }

            List<List<String>> rows = new ArrayList<>();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (Row sourceRow : workbook.getSheetAt(0)) {
                List<String> row = new ArrayList<>();
                for (int column = 0; column < sourceRow.getLastCellNum(); column++) {
                    Cell cell = sourceRow.getCell(column);
                    if (cell == null) {
                        row.add("");
                    } else if (DateUtil.isCellDateFormatted(cell)) {
                        row.add(DateUtil.getLocalDateTime(cell.getNumericCellValue())
                                .toInstant(ZoneOffset.UTC)
                                .toString());
                    } else {
                        row.add(formatter.formatCellValue(cell, evaluator).trim());
                    }
                }
                rows.add(row);
                if (rows.size() > MAX_ROWS + 25) {
                    throw badRequest("The statement contains too many rows (maximum 5,000)");
                }
            }
            return rows;
        }
    }

    private Header findHeader(List<List<String>> rows) {
        for (int index = 0; index < Math.min(rows.size(), 25); index++) {
            List<String> row = rows.get(index);
            int date = findColumn(
                    row, "date", "transactiondate", "transactiondatetime",
                    "transactiondateandtime", "datetime");
            int amount = findColumn(row, "amount", "transactionamount");
            int description = findColumn(
                    row, "details", "description", "transactiondetails",
                    "transactiondescription", "merchant", "merchantname", "payee",
                    "counterparty", "paidto", "receivedfrom");
            if (date >= 0 && amount >= 0 && description >= 0) {
                return new Header(
                        index,
                        date,
                        amount,
                        description,
                        findColumn(
                                row, "type", "direction", "debitcredit", "creditdebit",
                                "transactiontype"),
                        findColumn(row, "status", "transactionstatus"),
                        findColumn(
                                row, "transactionid", "upiid", "referenceid",
                                "transactionref", "transactionreference", "referencenumber"));
            }
        }
        throw badRequest(
                "Could not find Date, Details/Description, and Amount columns in the statement");
    }

    private int findColumn(List<String> headers, String... aliases) {
        for (int column = 0; column < headers.size(); column++) {
            String normalized = normalize(headers.get(column));
            for (String alias : aliases) {
                if (normalized.equals(alias)) {
                    return column;
                }
            }
        }
        return -1;
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String value(List<String> row, int column) {
        return column >= 0 && column < row.size() ? row.get(column) : "";
    }

    private BigDecimal parseAmount(String value) {
        String cleaned = value.replace("₹", "")
                .replaceAll("(?i)INR", "")
                .replace(",", "")
                .replace("\u00a0", "")
                .trim()
                .replaceAll("[^0-9.+-]", "");
        BigDecimal amount = new BigDecimal(cleaned).abs().setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0 || amount.precision() - amount.scale() > 10) {
            throw new IllegalArgumentException("Invalid or unsupported transaction amount");
        }
        return amount;
    }

    private Instant parseDate(String value) {
        String date = value.trim();
        try {
            return Instant.parse(date);
        } catch (DateTimeParseException ignored) {
            // Try local date/time formats used by exported statements below.
        }
        for (DateTimeFormatter format : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(date, format).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                // Continue with the remaining supported formats.
            }
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(date, format).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                // Continue with the remaining supported formats.
            }
        }
        throw new IllegalArgumentException("Invalid transaction date");
    }

    private boolean isIncoming(String direction, String description) {
        return containsAny(direction, "credit", "received", "refund", "cashback")
                || containsAny(description.toLowerCase(Locale.ROOT),
                        "received from", "refund", "cashback", "cash back");
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private ExpenseCategory categoryFor(String description) {
        String text = description.toLowerCase(Locale.ROOT);
        if (containsAny(text, "food", "cafe", "restaurant", "grocery", "swiggy", "zomato")) {
            return ExpenseCategory.FOOD;
        }
        if (containsAny(text, "uber", "ola", "metro", "fuel", "petrol", "taxi", "bus")) {
            return ExpenseCategory.TRANSPORT;
        }
        if (containsAny(text, "amazon", "flipkart", "shopping", "store")) {
            return ExpenseCategory.SHOPPING;
        }
        if (containsAny(text, "electricity", "water", "bill", "recharge", "broadband")) {
            return ExpenseCategory.BILLS;
        }
        if (containsAny(text, "pharmacy", "hospital", "medical", "health", "clinic")) {
            return ExpenseCategory.HEALTH;
        }
        return ExpenseCategory.OTHER;
    }

    private String trimToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record Header(
            int rowIndex,
            int dateColumn,
            int amountColumn,
            int descriptionColumn,
            int directionColumn,
            int statusColumn,
            int transactionIdColumn) {
    }
}
