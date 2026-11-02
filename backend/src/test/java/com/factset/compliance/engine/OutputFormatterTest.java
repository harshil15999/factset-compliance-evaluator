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

class OutputFormatterTest {

    private final RuleEngine engine = new RuleEngine();

    private Transaction tx(String id, BigDecimal amount) {
        return new Transaction(
                id,
                Instant.parse("2024-01-15T12:00:00Z"),
                amount,
                "USD",
                "US",
                "C1",
                "PERSONAL",
                "PAYMENT"
        );
    }

    @Test
    void formatsOutputWithAscendingRuleId() {
        OutputFormatter formatter = new OutputFormatter("\t");

        Rule r1 = new Rule("R003", "rule3", "amount", Operator.GT, "10", Severity.LOW);
        Rule r2 = new Rule("R001", "rule1", "amount", Operator.GT, "20", Severity.CRITICAL);
        Rule r3 = new Rule("R002", "rule2", "amount", Operator.GT, "30", Severity.HIGH);

        Transaction t1 = tx("TXN001", new BigDecimal("50")); // breaches all 3
        Transaction t2 = tx("TXN002", new BigDecimal("5"));  // breaches none

        String output = formatter.format(List.of(t1, t2), List.of(r1, r2, r3), engine);

        String expected = "Transaction\tBreached Rules\n" +
                "TXN001\tR001 (CRITICAL), R002 (HIGH), R003 (LOW)\n" +
                "TXN002\tnone\n";

        assertEquals(expected, output);
    }

    @Test
    void formatsOutputWithCustomSeparator() {
        OutputFormatter formatter = new OutputFormatter("    ");

        Rule r1 = new Rule("R001", "rule1", "amount", Operator.GT, "10", Severity.HIGH);
        Transaction t1 = tx("TXN001", new BigDecimal("50"));

        String output = formatter.format(List.of(t1), List.of(r1), engine);

        String expected = "Transaction    Breached Rules\n" +
                "TXN001    R001 (HIGH)\n";

        assertEquals(expected, output);
    }

    @Test
    void formatsOutputWithSeverityOrderWhenConfigured() {
        OutputFormatter formatter = new OutputFormatter("\t", "severity");

        Rule r1 = new Rule("R001", "rule1", "amount", Operator.GT, "10", Severity.LOW);
        Rule r2 = new Rule("R002", "rule2", "amount", Operator.GT, "10", Severity.CRITICAL);
        Rule r3 = new Rule("R003", "rule3", "amount", Operator.GT, "10", Severity.HIGH);

        Transaction t1 = tx("TXN001", new BigDecimal("50"));

        String output = formatter.format(List.of(t1), List.of(r1, r2, r3), engine);

        String expected = "Transaction\tBreached Rules\n" +
                "TXN001\tR002 (CRITICAL), R003 (HIGH), R001 (LOW)\n";

        assertEquals(expected, output);
    }
}
