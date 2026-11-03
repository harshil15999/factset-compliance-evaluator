package com.factset.compliance.perf;

import com.factset.compliance.engine.OutputFormatter;
import com.factset.compliance.engine.RuleEngine;
import com.factset.compliance.load.RuleLoader;
import com.factset.compliance.model.Rule;
import com.factset.compliance.model.Transaction;
import io.pyroscope.http.Format;
import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.config.Config;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class PyroscopeProfilingTest {

    private boolean isPyroscopeRunning() {
        try {
            URL url = URI.create("http://127.0.0.1:4040/").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void profileRuleEngineWithPyroscope() throws Exception {
        boolean pyroscopeUp = isPyroscopeRunning();
        Assumptions.assumeTrue(pyroscopeUp, "Pyroscope server is not running on http://127.0.0.1:4040. Skipping profiling stream.");

        // 1. Configure and start Pyroscope Agent
        Config config = new Config.Builder()
                .setApplicationName("compliance-evaluator")
                .setServerAddress("http://127.0.0.1:4040")
                .setFormat(Format.JFR)
                .setProfilingInterval(Duration.ofMillis(10))
                .setUploadInterval(Duration.ofSeconds(1))
                .build();

        PyroscopeAgent.start(config);

        try {
            // 2. Load rules
            String rulesCsv = "rule_id,rule_name,field,operator,value,severity\n" +
                    "R001,high_value,amount,gt,10000,HIGH\n" +
                    "R002,sanctioned_country,country,in,\"IR,KP,SY\",HIGH\n" +
                    "R003,off_hours_wire,timestamp.hour,lt,6,MEDIUM\n" +
                    "R004,critical_payment_to_cuba,country,eq,CU,CRITICAL\n" +
                    "R005,low_value_personal,amount,lt,1000,LOW\n" +
                    "R006,wire_after_hours,timestamp.hour,gt,18,MEDIUM\n" +
                    "R007,non_usd_personal,currency,neq,USD,LOW\n" +
                    "R008,business_large_odd_country,country,in,\"RU,BY,MM\",LOW\n";
            List<Rule> rules = new RuleLoader().load(new StringReader(rulesCsv));

            // 3. Generate 15,000 synthetic transactions
            List<Transaction> transactions = new ArrayList<>(15_000);
            String[] countries = {"US", "IR", "GB", "DE", "SY", "KP"};
            String[] currencies = {"USD", "EUR", "GBP", "JPY"};
            Instant baseTime = Instant.parse("2024-01-15T12:00:00Z");

            for (int i = 0; i < 15_000; i++) {
                transactions.add(new Transaction(
                        "TXN" + String.format("%06d", i),
                        baseTime.plusSeconds(i * 60L),
                        BigDecimal.valueOf(100.0 + (i % 50_000)),
                        currencies[i % currencies.length],
                        countries[i % countries.length],
                        "CUST" + (i % 500),
                        (i % 2 == 0) ? "PERSONAL" : "BUSINESS",
                        (i % 3 == 0) ? "WIRE_TRANSFER" : "PAYMENT"
                ));
            }

            // 4. Run rule evaluation loops
            RuleEngine engine = new RuleEngine();
            OutputFormatter formatter = new OutputFormatter("\t", "rule_id");

            System.out.println("[INFO] Profiling 15,000 transactions under Pyroscope (port 4040)...");
            for (int iter = 0; iter < 3; iter++) {
                formatter.format(transactions, rules, engine);
            }

            // Allow upload interval to flush samples to Pyroscope
            Thread.sleep(2000);
            System.out.println("[INFO] Profiling complete! View your Flame Graph at http://localhost:4040");
        } finally {
            PyroscopeAgent.stop();
        }
    }
}
