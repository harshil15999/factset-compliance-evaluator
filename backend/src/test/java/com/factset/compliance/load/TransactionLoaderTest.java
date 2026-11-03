package com.factset.compliance.load;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.factset.compliance.model.Transaction;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionLoaderTest {

    private final TransactionLoader loader = new TransactionLoader();

    private static ByteArrayInputStream json(String body) {
        return new ByteArrayInputStream(("{\"transactions\":[" + body + "]}").getBytes(StandardCharsets.UTF_8));
    }

    private static String tx(String fields) {
        return "{" + fields + "}";
    }

    @Test
    void cleanTransactionHasNoProblems() throws Exception {
        List<String> problems = loader.validate(json(tx(
                "\"transaction_id\":\"T1\",\"timestamp\":\"2024-01-15T10:00:00Z\","
                        + "\"amount\":100,\"currency\":\"USD\",\"country\":\"US\","
                        + "\"customer_id\":\"CUST001\",\"account_type\":\"PERSONAL\",\"transaction_type\":\"WIRE_TRANSFER\"")));
        assertEquals(List.of(), problems);
    }

    @Test
    void currencyWithoutCountryIsValid() throws Exception {
        String body = tx("\"transaction_id\":\"T1\",\"timestamp\":\"2024-01-15T10:00:00Z\","
                + "\"amount\":100,\"currency\":\"USD\"");
        assertEquals(List.of(), loader.validate(json(body)));
        Transaction t = loader.load(json(body)).get(0);
        assertEquals("USD", t.currency());
        assertEquals(null, t.country());
    }

    @Test
    void countryWithoutCurrencyIsValid() throws Exception {
        String body = tx("\"transaction_id\":\"T1\",\"timestamp\":\"2024-01-15T10:00:00Z\","
                + "\"amount\":100,\"country\":\"GB\"");
        assertEquals(List.of(), loader.validate(json(body)));
        Transaction t = loader.load(json(body)).get(0);
        assertEquals(null, t.currency());
        assertEquals("GB", t.country());
    }

    @Test
    void neitherCurrencyNorCountryIsValid() throws Exception {
        String body = tx("\"transaction_id\":\"T1\",\"timestamp\":\"2024-01-15T10:00:00Z\","
                + "\"amount\":100");
        assertEquals(List.of(), loader.validate(json(body)));
        Transaction t = loader.load(json(body)).get(0);
        assertEquals(null, t.currency());
        assertEquals(null, t.country());
    }

    @Test
    void freeTextFieldsHaveNoCheck() throws Exception {
        String body = tx("\"transaction_id\":\"T1\",\"timestamp\":\"2024-01-15T10:00:00Z\","
                + "\"amount\":100,\"customer_id\":\"ANY_TEXT_!@#$\",\"account_type\":\"\",\"transaction_type\":\"SPECIAL\"");
        assertEquals(List.of(), loader.validate(json(body)));
    }

    @Test
    void valueChecksEachFlagOneProblem() throws Exception {
        assertTrue(onlyProblem("\"amount\":-5,\"currency\":\"USD\",\"country\":\"US\"")
                .contains("amount must be positive"));
        assertTrue(onlyProblem("\"amount\":0,\"currency\":\"USD\",\"country\":\"US\"")
                .contains("amount must be positive"));
        assertTrue(onlyProblem("\"amount\":100,\"currency\":\"DOLLARS\",\"country\":\"US\"")
                .contains("currency 'DOLLARS' is not a 3-letter ISO code"));
        assertTrue(onlyProblem("\"amount\":100,\"currency\":\"usd\",\"country\":\"US\"")
                .contains("currency 'usd' is not a 3-letter ISO code"));
        assertTrue(onlyProblem("\"amount\":100,\"currency\":\"\",\"country\":\"US\"")
                .contains("currency '' is not a 3-letter ISO code"));
        assertTrue(onlyProblem("\"amount\":100,\"currency\":\"USD\",\"country\":\"USA\"")
                .contains("country 'USA' is not a 2-letter ISO code"));
        assertTrue(onlyProblem("\"amount\":100,\"currency\":\"USD\",\"country\":\"us\"")
                .contains("country 'us' is not a 2-letter ISO code"));
        assertTrue(onlyProblem("\"amount\":100,\"currency\":\"USD\",\"country\":\"\"")
                .contains("country '' is not a 2-letter ISO code"));
        assertTrue(onlyProblem("\"amount\":100,\"currency\":\"USD\",\"country\":\"US\","
                + "\"timestamp\":\"3024-01-15T10:00:00Z\"").contains("out of the expected range"));
        assertTrue(onlyProblem("\"amount\":100,\"currency\":\"USD\",\"country\":\"US\","
                + "\"timestamp\":\"1999-12-31T23:59:59Z\"").contains("out of the expected range"));
    }

    @Test
    void loadSkipsMalformedTransactions() throws Exception {
        String body = tx("\"transaction_id\":\"T1\",\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":100")
                + "," + tx("\"transaction_id\":\"T2\",\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":-50")
                + "," + tx("\"transaction_id\":\"\",\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":100")
                + "," + tx("\"transaction_id\":\"T3\",\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":200");
        List<Transaction> loaded = loader.load(json(body));
        assertEquals(2, loaded.size());
        assertEquals("T1", loaded.get(0).transactionId());
        assertEquals("T3", loaded.get(1).transactionId());
    }

    @Test
    void loadLogsErrorsForMalformedAndDuplicateTransactions() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(TransactionLoader.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);

        try {
            String body = tx("\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":100")
                    + "," + tx("\"transaction_id\":\"T1\",\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":100")
                    + "," + tx("\"transaction_id\":\"T1\",\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":100")
                    + ",null";
            loader.load(json(body));

            List<ILoggingEvent> errorEvents = listAppender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .toList();

            assertTrue(errorEvents.stream().anyMatch(e ->
                    e.getFormattedMessage().contains("Skipping malformed transaction at position 1: transactions[1]: missing transaction_id")),
                    "Expected ERROR log for missing transaction_id with 1-based positional indicator");
            assertTrue(errorEvents.stream().anyMatch(e ->
                    e.getFormattedMessage().contains("Skipping duplicate transaction_id 'T1' at position 3")),
                    "Expected ERROR log for duplicate transaction_id");
            assertTrue(errorEvents.stream().anyMatch(e ->
                    e.getFormattedMessage().contains("Skipping non-object transaction element at position 4")),
                    "Expected ERROR log for non-object element");
        } finally {
            logger.detachAppender(listAppender);
        }
    }

    @Test
    void loadSkipsDuplicateTransactionIds() throws Exception {
        String body = tx("\"transaction_id\":\"T1\",\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":100")
                + "," + tx("\"transaction_id\":\"T1\",\"timestamp\":\"2024-01-15T11:00:00Z\",\"amount\":200")
                + "," + tx("\"transaction_id\":\"T2\",\"timestamp\":\"2024-01-15T12:00:00Z\",\"amount\":300");
        List<Transaction> loaded = loader.load(json(body));
        assertEquals(2, loaded.size());
        assertEquals("T1", loaded.get(0).transactionId());
        assertEquals(new BigDecimal("100"), loaded.get(0).amount());
        assertEquals("T2", loaded.get(1).transactionId());
    }

    @Test
    void validateCatchesMalformedJsonSyntax() {
        ByteArrayInputStream badJson = new ByteArrayInputStream("{\"transactions\": [unclosed".getBytes(StandardCharsets.UTF_8));
        List<String> problems = loader.validate(badJson);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("malformed JSON syntax"), problems.toString());
    }

    @Test
    void nullOrNonObjectElementsHandledGracefully() throws Exception {
        ByteArrayInputStream withNull = new ByteArrayInputStream("{\"transactions\": [null, 123]}".getBytes(StandardCharsets.UTF_8));
        List<String> problems = loader.validate(withNull);
        assertEquals(2, problems.size());
        assertTrue(problems.get(0).contains("expected a JSON object element"));
        assertTrue(problems.get(1).contains("expected a JSON object element"));

        ByteArrayInputStream loadNull = new ByteArrayInputStream("{\"transactions\": [null, 123]}".getBytes(StandardCharsets.UTF_8));
        List<Transaction> loaded = loader.load(loadNull);
        assertEquals(0, loaded.size());
    }

    @Test
    void existingChecksStillFire() throws Exception {
        // missing id, duplicate id, missing timestamp, missing amount
        List<String> problems = loader.validate(json(
                tx("\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":1,\"currency\":\"USD\",\"country\":\"US\"")
                        + "," + tx("\"transaction_id\":\"T1\",\"amount\":1,\"currency\":\"USD\",\"country\":\"US\"")
                        + "," + tx("\"transaction_id\":\"T1\",\"timestamp\":\"2024-01-15T10:00:00Z\",\"currency\":\"USD\",\"country\":\"US\"")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("missing transaction_id")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("missing or invalid timestamp")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("duplicate transaction_id")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("missing or invalid amount")), problems.toString());
    }

    @Test
    void scenarioA_missingOrBlankTransactionIdReportsIndex() throws Exception {
        // null ID at index 1, empty ID at index 2, whitespace ID at index 3, omitted ID at index 4
        String body = tx("\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":100")
                + "," + tx("\"transaction_id\":\"\",\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":100")
                + "," + tx("\"transaction_id\":\"   \",\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":100")
                + "," + tx("\"transaction_id\":null,\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":100")
                + "," + tx("\"transaction_id\":\"TXN_VALID\",\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":100");

        List<String> problems = loader.validate(json(body));
        assertEquals(4, problems.size());
        assertTrue(problems.get(0).equals("transactions[1]: missing transaction_id"));
        assertTrue(problems.get(1).equals("transactions[2]: missing transaction_id"));
        assertTrue(problems.get(2).equals("transactions[3]: missing transaction_id"));
        assertTrue(problems.get(3).equals("transactions[4]: missing transaction_id"));

        List<Transaction> loaded = loader.load(json(body));
        assertEquals(1, loaded.size());
        assertEquals("TXN_VALID", loaded.get(0).transactionId());
    }

    @Test
    void scenarioB_malformedTimestampReportsIndex() throws Exception {
        // out of bounds before 2000 at index 1, out of bounds future at index 2, non-ISO text at index 3, null at index 4
        String body = tx("\"transaction_id\":\"T1\",\"timestamp\":\"1999-12-31T23:59:59Z\",\"amount\":100")
                + "," + tx("\"transaction_id\":\"T2\",\"timestamp\":\"3025-01-01T00:00:00Z\",\"amount\":100")
                + "," + tx("\"transaction_id\":\"T3\",\"timestamp\":\"NOT_A_DATE\",\"amount\":100")
                + "," + tx("\"transaction_id\":\"T4\",\"timestamp\":null,\"amount\":100")
                + "," + tx("\"transaction_id\":\"T5\",\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":100");

        List<String> problems = loader.validate(json(body));
        assertEquals(4, problems.size());
        assertTrue(problems.get(0).contains("transactions[1]: timestamp 1999-12-31T23:59:59Z is out of the expected range"));
        assertTrue(problems.get(1).contains("transactions[2]: timestamp 3025-01-01T00:00:00Z is out of the expected range"));
        assertTrue(problems.get(2).contains("transactions[3]: Cannot deserialize value of type `java.time.Instant`"));
        assertTrue(problems.get(3).contains("transactions[4]: missing or invalid timestamp"));

        List<Transaction> loaded = loader.load(json(body));
        assertEquals(1, loaded.size());
        assertEquals("T5", loaded.get(0).transactionId());
    }

    @Test
    void scenarioC_negativeOrNonNumericAmountReportsIndex() throws Exception {
        // negative at index 1, zero at index 2, non-numeric string at index 3, null at index 4
        String body = tx("\"transaction_id\":\"T1\",\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":-50")
                + "," + tx("\"transaction_id\":\"T2\",\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":0")
                + "," + tx("\"transaction_id\":\"T3\",\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":\"NOT_A_NUMBER\"")
                + "," + tx("\"transaction_id\":\"T4\",\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":null")
                + "," + tx("\"transaction_id\":\"T5\",\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":100");

        List<String> problems = loader.validate(json(body));
        assertEquals(4, problems.size());
        assertTrue(problems.get(0).contains("transactions[1]: amount must be positive (was -50)"));
        assertTrue(problems.get(1).contains("transactions[2]: amount must be positive (was 0)"));
        assertTrue(problems.get(2).contains("transactions[3]: Cannot deserialize value of type `java.math.BigDecimal`"));
        assertTrue(problems.get(3).contains("transactions[4]: missing or invalid amount"));

        List<Transaction> loaded = loader.load(json(body));
        assertEquals(1, loaded.size());
        assertEquals("T5", loaded.get(0).transactionId());
    }

    @Test
    void scenarioD_duplicateTransactionIdReportsIndex() throws Exception {
        String body = tx("\"transaction_id\":\"DUP_1\",\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":100")
                + "," + tx("\"transaction_id\":\"DUP_1\",\"timestamp\":\"2024-01-15T11:00:00Z\",\"amount\":200")
                + "," + tx("\"transaction_id\":\"UNIQUE_2\",\"timestamp\":\"2024-01-15T12:00:00Z\",\"amount\":300");

        List<String> problems = loader.validate(json(body));
        assertEquals(1, problems.size());
        assertEquals("transactions[2]: duplicate transaction_id 'DUP_1'", problems.get(0));

        List<Transaction> loaded = loader.load(json(body));
        assertEquals(2, loaded.size());
        assertEquals("DUP_1", loaded.get(0).transactionId());
        assertEquals("UNIQUE_2", loaded.get(1).transactionId());
    }

    @Test
    void scenarioE_nonObjectOrCorruptedJsonSyntaxReportsProblem() throws Exception {
        // E1: Non-object elements
        ByteArrayInputStream nonObjStream = new ByteArrayInputStream("{\"transactions\": [\"not_an_object\", 42, null]}".getBytes(StandardCharsets.UTF_8));
        List<String> problemsE1 = loader.validate(nonObjStream);
        assertEquals(3, problemsE1.size());
        assertEquals("transactions[1]: expected a JSON object element", problemsE1.get(0));
        assertEquals("transactions[2]: expected a JSON object element", problemsE1.get(1));
        assertEquals("transactions[3]: expected a JSON object element", problemsE1.get(2));

        // E2: Corrupted JSON syntax
        ByteArrayInputStream corruptedStream = new ByteArrayInputStream("{\"transactions\": [{\"transaction_id\": \"T1\" unclosed".getBytes(StandardCharsets.UTF_8));
        List<String> problemsE2 = loader.validate(corruptedStream);
        assertEquals(1, problemsE2.size());
        assertTrue(problemsE2.get(0).contains("transactions: malformed JSON syntax"));
    }

    @Test
    void missingTransactionIdTakesPrecedenceOverJacksonDeserializationExceptions() throws Exception {
        // Record has BOTH null transaction_id AND unparseable timestamp (hour 25)
        String body = tx("\"transaction_id\":null,\"timestamp\":\"2024-01-15T25:30:00Z\",\"amount\":15000")
                + "," + tx("\"transaction_id\":\"TXN_OK\",\"timestamp\":\"2024-01-15T10:00:00Z\",\"amount\":100");

        List<String> problems = loader.validate(json(body));
        assertEquals(1, problems.size());
        assertEquals("transactions[1]: missing transaction_id", problems.get(0));

        List<Transaction> loaded = loader.load(json(body));
        assertEquals(1, loaded.size());
        assertEquals("TXN_OK", loaded.get(0).transactionId());
    }

    /** Validate a single transaction (with the required id/timestamp filled in) and return its one problem. */
    private String onlyProblem(String fields) throws Exception {
        List<String> problems = loader.validate(json(tx(
                "\"transaction_id\":\"T1\",\"timestamp\":\"2024-01-15T10:00:00Z\"," + fields)));
        // Strip a possible duplicate timestamp-range message only; each case is crafted to yield one.
        assertEquals(1, problems.size(), problems.toString());
        return problems.get(0);
    }
}
