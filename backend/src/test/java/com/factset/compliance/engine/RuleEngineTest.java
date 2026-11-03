package com.factset.compliance.engine;

import com.factset.compliance.model.Operator;
import com.factset.compliance.model.Rule;
import com.factset.compliance.model.Severity;
import com.factset.compliance.model.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineTest {

    private final RuleEngine engine = new RuleEngine();

    private Transaction tx(String id, String timestamp, BigDecimal amount, String currency, String country,
                           String customerId, String accountType, String transactionType) {
        return new Transaction(
                id,
                timestamp != null ? Instant.parse(timestamp) : null,
                amount,
                currency,
                country,
                customerId,
                accountType,
                transactionType
        );
    }

    @Test
    void amountGtAndLtAreStrict() {
        Rule gt100 = new Rule("R1", "gt100", "amount", Operator.GT, "100", Severity.HIGH);
        Rule lt100 = new Rule("R2", "lt100", "amount", Operator.LT, "100", Severity.LOW);

        // Exactly 100 should not breach gt or lt
        Transaction tExact = tx("T1", "2024-01-15T12:00:00Z", new BigDecimal("100"), "USD", "US", "C1", "P", "W");
        assertEquals(List.of(), engine.breaches(tExact, List.of(gt100, lt100)));

        // 100.01 breaches gt100
        Transaction tOver = tx("T2", "2024-01-15T12:00:00Z", new BigDecimal("100.01"), "USD", "US", "C1", "P", "W");
        assertEquals(List.of(gt100), engine.breaches(tOver, List.of(gt100, lt100)));

        // 99.99 breaches lt100
        Transaction tUnder = tx("T3", "2024-01-15T12:00:00Z", new BigDecimal("99.99"), "USD", "US", "C1", "P", "W");
        assertEquals(List.of(lt100), engine.breaches(tUnder, List.of(gt100, lt100)));
    }

    @Test
    void timestampHourEvaluatedInUtc() {
        Rule after22Utc = new Rule("R1", "late", "timestamp.hour", Operator.GT, "22", Severity.MEDIUM);

        // 22:30:00Z is hour 22 -> not gt 22
        Transaction t22 = tx("T1", "2024-01-15T22:30:00Z", new BigDecimal("50"), "USD", "US", "C1", "P", "W");
        assertEquals(List.of(), engine.breaches(t22, List.of(after22Utc)));

        // 23:00:00Z is hour 23 -> gt 22
        Transaction t23 = tx("T2", "2024-01-15T23:00:00Z", new BigDecimal("50"), "USD", "US", "C1", "P", "W");
        assertEquals(List.of(after22Utc), engine.breaches(t23, List.of(after22Utc)));

        // 00:15:00Z is hour 0 -> not gt 22
        Transaction t0 = tx("T3", "2024-01-15T00:15:00Z", new BigDecimal("50"), "USD", "US", "C1", "P", "W");
        assertEquals(List.of(), engine.breaches(t0, List.of(after22Utc)));
    }

    @Test
    void stringOperatorsAreCaseSensitive() {
        Rule eqUsd = new Rule("R1", "usd", "currency", Operator.EQ, "USD", Severity.LOW);
        Rule inCountries = new Rule("R2", "watchlist", "country", Operator.IN, "IR,KP,SY", Severity.HIGH);

        // Exact match
        Transaction tMatch = tx("T1", "2024-01-15T12:00:00Z", new BigDecimal("50"), "USD", "IR", "C1", "P", "W");
        assertEquals(List.of(eqUsd, inCountries), engine.breaches(tMatch, List.of(eqUsd, inCountries)));

        // Case difference does not match
        Transaction tLower = tx("T2", "2024-01-15T12:00:00Z", new BigDecimal("50"), "usd", "ir", "C1", "P", "W");
        assertEquals(List.of(), engine.breaches(tLower, List.of(eqUsd, inCountries)));
    }

    @Test
    void missingFieldDoesNotBreachRule() {
        Rule eqUsd = new Rule("R1", "usd", "currency", Operator.EQ, "USD", Severity.LOW);
        Rule inCountries = new Rule("R2", "watchlist", "country", Operator.IN, "IR,KP,SY", Severity.HIGH);

        // Transaction with null currency and country
        Transaction tNulls = tx("T1", "2024-01-15T12:00:00Z", new BigDecimal("50"), null, null, "C1", "P", "W");
        assertTrue(engine.breaches(tNulls, List.of(eqUsd, inCountries)).isEmpty());
    }

    @Test
    void amountEqualityComparesNumerically() {
        Rule eq100 = new Rule("R1", "eq100", "amount", Operator.EQ, "100", Severity.MEDIUM);
        Rule neq100 = new Rule("R2", "neq100", "amount", Operator.NEQ, "100", Severity.LOW);

        // Transaction with 100.00 matches eq 100 and does NOT breach neq 100
        Transaction tExact = tx("T1", "2024-01-15T12:00:00Z", new BigDecimal("100.00"), "USD", "US", "C1", "P", "W");
        assertEquals(List.of(eq100), engine.breaches(tExact, List.of(eq100, neq100)));

        // Rule with 100.00 matches transaction with 100
        Rule eq100Dec = new Rule("R3", "eq100Dec", "amount", Operator.EQ, "100.00", Severity.MEDIUM);
        Transaction tInteger = tx("T2", "2024-01-15T12:00:00Z", new BigDecimal("100"), "USD", "US", "C1", "P", "W");
        assertEquals(List.of(eq100Dec), engine.breaches(tInteger, List.of(eq100Dec)));

        // Transaction with 100.01 breaches neq100, but not eq100
        Transaction tDiff = tx("T3", "2024-01-15T12:00:00Z", new BigDecimal("100.01"), "USD", "US", "C1", "P", "W");
        assertEquals(List.of(neq100), engine.breaches(tDiff, List.of(eq100, neq100)));
    }

    @Test
    void transactionStringFieldsAreTrimmed() {
        Rule eqUsd = new Rule("R1", "usd", "currency", Operator.EQ, "USD", Severity.LOW);
        Rule eqUs = new Rule("R2", "us", "country", Operator.EQ, "US", Severity.LOW);
        Rule eqWire = new Rule("R3", "wire", "transaction_type", Operator.EQ, "WIRE_TRANSFER", Severity.LOW);

        // Transaction with leading/trailing spaces in fields
        Transaction tPadded = tx("T1", "2024-01-15T12:00:00Z", new BigDecimal("50"), " USD ", " US ", "C1", " PERSONAL ", " WIRE_TRANSFER ");
        List<Rule> breaches = engine.breaches(tPadded, List.of(eqUsd, eqUs, eqWire));
        assertEquals(List.of(eqUsd, eqUs, eqWire), breaches);
    }

    @Test
    void hourValuesCanonicalizedAtEvaluationTime() {
        // Rules instantiated directly with leading zeros
        Rule eq06 = new Rule("R1", "hour06", "timestamp.hour", Operator.EQ, "06", Severity.LOW);
        Rule inHours = new Rule("R2", "inHours", "timestamp.hour", Operator.IN, "01, 06, 23", Severity.MEDIUM);

        // Transaction at 06:15 UTC -> UTC hour is 6
        Transaction t06 = tx("T1", "2024-01-15T06:15:00Z", new BigDecimal("50"), "USD", "US", "C1", "P", "W");
        List<Rule> breaches = engine.breaches(t06, List.of(eq06, inHours));
        assertEquals(List.of(eq06, inHours), breaches);

        // Transaction at 07:00 UTC -> should not match
        Transaction t07 = tx("T2", "2024-01-15T07:00:00Z", new BigDecimal("50"), "USD", "US", "C1", "P", "W");
        assertTrue(engine.breaches(t07, List.of(eq06, inHours)).isEmpty());
    }
}
