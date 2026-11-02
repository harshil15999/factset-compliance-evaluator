package com.factset.compliance.load;

import com.factset.compliance.model.Operator;
import com.factset.compliance.model.Rule;
import com.factset.compliance.model.Severity;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleLoaderTest {

    private final RuleLoader loader = new RuleLoader();

    private static String csv(String... rows) {
        StringBuilder sb = new StringBuilder("rule_id,rule_name,field,operator,value,severity\n");
        for (String row : rows) {
            sb.append(row).append("\n");
        }
        return sb.toString();
    }

    @Test
    void validRulesPassValidation() throws Exception {
        String data = csv(
                "R001,large_transaction,amount,gt,10000,HIGH",
                "R002,watchlist,country,in,\"IR,KP,SY\",HIGH",
                "R003,night_owl,timestamp.hour,gt,22,MEDIUM",
                "R004,non_usd,currency,neq,USD,LOW",
                "R005,wire,transaction_type,eq,WIRE_TRANSFER,MEDIUM",
                "R006,biz,account_type,eq,BUSINESS,LOW"
        );
        List<String> problems = loader.validate(new StringReader(data));
        assertEquals(List.of(), problems);
    }

    @Test
    void duplicateRuleIdFlagged() throws Exception {
        String data = csv(
                "R001,rule_one,amount,gt,1000,LOW",
                "R001,rule_two,amount,gt,2000,HIGH"
        );
        List<String> problems = loader.validate(new StringReader(data));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("duplicate rule_id 'R001'"), problems.toString());
    }

    @Test
    void invalidRuleIdFormatFlagged() throws Exception {
        String data = csv("RULE1,bad_id,amount,gt,1000,LOW");
        List<String> problems = loader.validate(new StringReader(data));
        assertTrue(problems.stream().anyMatch(p -> p.contains("expected format (e.g. R001)")), problems.toString());
    }

    @Test
    void missingRequiredColumnsFlagged() throws Exception {
        String data = "rule_id,rule_name,operator,value,severity\n" +
                "R001,missing_field,gt,1000,LOW\n";
        List<String> problems = loader.validate(new StringReader(data));
        assertTrue(problems.stream().anyMatch(p -> p.contains("missing required column 'field'")), problems.toString());
    }

    @Test
    void unknownFieldAndInvalidOperatorFlagged() throws Exception {
        String data = csv(
                "R001,bad_field,invalid_field,eq,foo,LOW",
                "R002,bad_operator_for_number,amount,eq,100,LOW",
                "R003,bad_operator_for_string,country,gt,US,LOW"
        );
        List<String> problems = loader.validate(new StringReader(data));
        assertEquals(3, problems.size());
        assertTrue(problems.get(0).contains("unknown field 'invalid_field'"));
        assertTrue(problems.get(1).contains("operator 'eq' is not valid for field 'amount'"));
        assertTrue(problems.get(2).contains("operator 'gt' is not valid for field 'country'"));
    }

    @Test
    void amountValidationChecks() throws Exception {
        String data = csv(
                "R001,not_a_number,amount,gt,abc,HIGH",
                "R002,negative_amount,amount,gt,-50,HIGH",
                "R003,vacuous_lt_zero,amount,lt,0,HIGH"
        );
        List<String> problems = loader.validate(new StringReader(data));
        assertEquals(3, problems.size());
        assertTrue(problems.get(0).contains("value 'abc' is not a number"));
        assertTrue(problems.get(1).contains("amount threshold cannot be negative"));
        assertTrue(problems.get(2).contains("'lt 0' on amount can never match"));
    }

    @Test
    void hourValidationChecks() throws Exception {
        String data = csv(
                "R001,not_an_int,timestamp.hour,gt,midnight,HIGH",
                "R002,out_of_range,timestamp.hour,gt,25,HIGH",
                "R003,vacuous_gt_23,timestamp.hour,gt,23,HIGH",
                "R004,vacuous_lt_0,timestamp.hour,lt,0,HIGH"
        );
        List<String> problems = loader.validate(new StringReader(data));
        assertEquals(4, problems.size());
        assertTrue(problems.get(0).contains("is not an hour 0-23"));
        assertTrue(problems.get(1).contains("is not an hour 0-23"));
        assertTrue(problems.get(2).contains("'gt 23' on timestamp.hour can never match"));
        assertTrue(problems.get(3).contains("'lt 0' on timestamp.hour can never match"));
    }

    @Test
    void countryAndCurrencyValidationChecks() throws Exception {
        String data = csv(
                "R001,bad_country_len,country,eq,USA,HIGH",
                "R002,bad_country_in,country,in,\"INR,USA\",HIGH",
                "R003,bad_currency_len,currency,eq,US,HIGH",
                "R004,bad_currency_name,currency,eq,DOLLARS,HIGH"
        );
        List<String> problems = loader.validate(new StringReader(data));
        assertEquals(5, problems.size()); // R002 produces 2 problems (one for INR, one for USA)
        assertTrue(problems.stream().anyMatch(p -> p.contains("value 'USA' is not a 2-letter ISO country code")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("value 'INR' is not a 2-letter ISO country code")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("value 'US' is not a 3-letter ISO currency code")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("value 'DOLLARS' is not a 3-letter ISO currency code")));
    }

    @Test
    void inListValidationChecks() throws Exception {
        String data = csv(
                "R001,empty_item,country,in,\"IR,,SY\",HIGH",
                "R002,trailing_comma,country,in,\"IR,\",HIGH"
        );
        List<String> problems = loader.validate(new StringReader(data));
        assertEquals(2, problems.size());
        assertTrue(problems.get(0).contains("'in' list has an empty item"));
        assertTrue(problems.get(1).contains("'in' list has an empty item"));
    }

    @Test
    void blankValueAndInvalidSeverityFlagged() throws Exception {
        String data = csv(
                "R001,blank_val,amount,gt,,HIGH",
                "R002,bad_severity,amount,gt,100,EXTREME"
        );
        List<String> problems = loader.validate(new StringReader(data));
        assertEquals(2, problems.size());
        assertTrue(problems.get(0).contains("value is blank"));
        assertTrue(problems.get(1).contains("invalid severity 'EXTREME'"));
    }

    @Test
    void loadSkipsMalformedRulesWithWarning() throws Exception {
        String data = csv(
                "R001,good,amount,gt,1000,LOW",
                "R002,bad_op,amount,UNKNOWN_OP,1000,LOW",
                "R003,negative_amount,amount,gt,-10,LOW",
                "R004,good_two,country,eq,US,HIGH"
        );
        List<Rule> loaded = loader.load(new StringReader(data));
        assertEquals(2, loaded.size());
        assertEquals("R001", loaded.get(0).ruleId());
        assertEquals("R004", loaded.get(1).ruleId());
    }

    @Test
    void hourValueWithLeadingZerosIsCanonicalized() throws Exception {
        String data = csv(
                "R001,padded_hour,timestamp.hour,eq,05,LOW",
                "R002,padded_list,timestamp.hour,in,\"01,02,03\",MEDIUM"
        );
        List<Rule> loaded = loader.load(new StringReader(data));
        assertEquals(2, loaded.size());
        assertEquals("5", loaded.get(0).value());
        assertEquals("1,2,3", loaded.get(1).value());

        List<String> problems = loader.validate(new StringReader(data));
        assertEquals(List.of(), problems);
    }

    @Test
    void emptyCsvAndMissingHeaderAreFlagged() throws Exception {
        List<String> pEmpty = loader.validate(new StringReader(""));
        assertEquals(1, pEmpty.size());
        assertTrue(pEmpty.get(0).contains("empty or missing required header"));

        String badHeader = "rule_id,field,operator\nR001,amount,gt\n";
        List<String> pBadHeader = loader.validate(new StringReader(badHeader));
        assertTrue(pBadHeader.stream().anyMatch(p -> p.contains("missing required column 'severity'")));
    }
}
