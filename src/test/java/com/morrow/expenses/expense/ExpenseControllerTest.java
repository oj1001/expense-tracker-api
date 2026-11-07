package com.morrow.expenses.expense;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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

    @Test
    void importsPhonePeCsvAndSkipsCreditsAndPreviouslyImportedTransactions() throws Exception {
        MockMultipartFile statement = new MockMultipartFile(
                "file",
                "phonepe.csv",
                "text/csv",
                """
                        Date,Details,Type,Amount,Transaction ID,Status
                        06/11/2020,"Cafe, Pune",DEBIT,"₹250.50",PHONEPE-123,SUCCESS
                        06/11/2020,Received from friend,CREDIT,500,,SUCCESS
                        """.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/expenses/import").file(statement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount", is(1)))
                .andExpect(jsonPath("$.skippedCount", is(1)))
                .andExpect(jsonPath("$.expenses[0].description", is("Cafe, Pune")))
                .andExpect(jsonPath("$.expenses[0].category", is("FOOD")));

        mockMvc.perform(multipart("/api/expenses/import").file(statement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount", is(0)))
                .andExpect(jsonPath("$.skippedCount", is(2)));
    }

    @Test
    void rejectsUnsupportedStatementFormat() throws Exception {
        MockMultipartFile statement = new MockMultipartFile(
                "file", "statement.pdf", "application/pdf", "not a spreadsheet".getBytes());

        mockMvc.perform(multipart("/api/expenses/import").file(statement))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importsXlsxStatement() throws Exception {
        MockMultipartFile statement = new MockMultipartFile(
                "file",
                "phonepe.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                createExcelStatement());

        mockMvc.perform(multipart("/api/expenses/import").file(statement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount", is(1)))
                .andExpect(jsonPath("$.expenses[0].description", is("Metro")));
    }

    private byte[] createExcelStatement() throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Transactions");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Transaction Date");
            header.createCell(1).setCellValue("Transaction Details");
            header.createCell(2).setCellValue("Amount");
            header.createCell(3).setCellValue("Transaction ID");

            Row transaction = sheet.createRow(1);
            var dateCell = transaction.createCell(0);
            dateCell.setCellValue(DateUtil.getExcelDate(
                    LocalDateTime.of(2020, 11, 6, 0, 0)));
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(
                    workbook.createDataFormat().getFormat("dd/mm/yyyy"));
            dateCell.setCellStyle(dateStyle);
            transaction.createCell(1).setCellValue("Metro");
            transaction.createCell(2).setCellValue("₹45.00");
            transaction.createCell(3).setCellValue("PHONEPE-XLSX-123");

            workbook.write(output);
            return output.toByteArray();
        }
    }
}
