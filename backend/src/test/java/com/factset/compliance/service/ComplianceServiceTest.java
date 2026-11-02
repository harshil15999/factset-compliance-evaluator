package com.factset.compliance.service;

import com.factset.compliance.engine.OutputFormatter;
import com.factset.compliance.engine.RuleEngine;
import com.factset.compliance.load.RuleLoader;
import com.factset.compliance.load.TransactionLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplianceServiceTest {

    private ComplianceService service;

    @BeforeEach
    void setUp() {
        TransactionLoader transactionLoader = new TransactionLoader();
        RuleLoader ruleLoader = new RuleLoader();
        RuleEngine ruleEngine = new RuleEngine();
        OutputFormatter outputFormatter = new OutputFormatter("\t", "rule_id");
        service = new ComplianceService(transactionLoader, ruleLoader, ruleEngine, outputFormatter);
    }

    @Test
    void evaluatesLoadedData() {
        Map<String, Object> eval = service.evaluate();
        assertNotNull(eval);
        assertTrue((int) eval.get("totalTransactions") > 0);
        assertNotNull(eval.get("rawReport"));
    }

    @Test
    void schemaReturnsFieldsAndSeverities() {
        Map<String, Object> schema = service.getSchema();
        assertNotNull(schema.get("fields"));
        assertNotNull(schema.get("severities"));
    }

    @Test
    void invalidRuleRejected() {
        Map<String, String> invalid = Map.of(
                "rule_name", "test_invalid",
                "field", "amount",
                "operator", "eq", // invalid for amount
                "value", "100",
                "severity", "HIGH"
        );
        Map<String, Object> res = service.addRule(invalid);
        assertFalse((boolean) res.get("ok"));
        assertNotNull(res.get("problems"));
    }

    @Test
    void updateTransactionsWithValidJson() {
        String json = """
                {
                  "transactions": [
                    {
                      "transaction_id": "NEW001",
                      "timestamp": "2024-01-15T10:00:00Z",
                      "amount": 999.00,
                      "currency": "USD",
                      "country": "US",
                      "customer_id": "CUST999",
                      "account_type": "PERSONAL",
                      "transaction_type": "PAYMENT"
                    }
                  ]
                }
                """;
        Map<String, Object> res = service.updateTransactions(json, "new_tx.json");
        assertTrue((boolean) res.get("ok"));
        assertEquals(1, res.get("count"));

        Map<String, Object> eval = service.evaluate();
        assertEquals(1, eval.get("totalTransactions"));
    }

    @Test
    void updateTransactionsWithInvalidJsonFails() {
        String json = "{ \"invalid\": true }";
        Map<String, Object> res = service.updateTransactions(json, "bad.json");
        assertFalse((boolean) res.get("ok"));
    }
}
