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
}
