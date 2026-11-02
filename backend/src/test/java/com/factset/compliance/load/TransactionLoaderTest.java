package com.factset.compliance.load;

import com.factset.compliance.model.Transaction;
import org.junit.jupiter.api.Test;

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

    /** Validate a single transaction (with the required id/timestamp filled in) and return its one problem. */
    private String onlyProblem(String fields) throws Exception {
        List<String> problems = loader.validate(json(tx(
                "\"transaction_id\":\"T1\",\"timestamp\":\"2024-01-15T10:00:00Z\"," + fields)));
        // Strip a possible duplicate timestamp-range message only; each case is crafted to yield one.
        assertEquals(1, problems.size(), problems.toString());
        return problems.get(0);
    }
}
