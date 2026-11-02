package com.factset.compliance.service;

import com.factset.compliance.engine.OutputFormatter;
import com.factset.compliance.engine.RuleEngine;
import com.factset.compliance.load.RuleLoader;
import com.factset.compliance.load.TransactionLoader;
import com.factset.compliance.model.Rule;
import com.factset.compliance.model.Severity;
import com.factset.compliance.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ComplianceService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceService.class);
    private static final Pattern RULE_ID_PATTERN = Pattern.compile("^R(\\d+)");

    private final TransactionLoader transactionLoader;
    private final RuleLoader ruleLoader;
    private final RuleEngine ruleEngine;
    private final OutputFormatter outputFormatter;

    private List<Transaction> transactions = new ArrayList<>();
    private List<Rule> rules = new ArrayList<>();
    private File rulesFile;
    private String transactionSource = "transactions.json (bundled)";

    public ComplianceService(TransactionLoader transactionLoader,
                             RuleLoader ruleLoader,
                             RuleEngine ruleEngine,
                             OutputFormatter outputFormatter) {
        this.transactionLoader = transactionLoader;
        this.ruleLoader = ruleLoader;
        this.ruleEngine = ruleEngine;
        this.outputFormatter = outputFormatter;
        initData();
    }

    private synchronized void initData() {
        try {
            // Locate or initialize rules.csv on disk
            File diskRules = new File("backend/src/main/resources/rules.csv");
            if (!diskRules.exists()) {
                diskRules = new File("rules.csv");
            }
            if (!diskRules.exists()) {
                // If running from jar with no local file, extract bundled rules.csv to working directory
                try (InputStream in = new ClassPathResource("rules.csv").getInputStream();
                     OutputStream out = new FileOutputStream(diskRules)) {
                    in.transferTo(out);
                }
            }
            this.rulesFile = diskRules;
            try (Reader reader = new InputStreamReader(new FileInputStream(rulesFile), StandardCharsets.UTF_8)) {
                this.rules = ruleLoader.load(reader);
            }

            // Locate or initialize transactions.json
            File diskTx = new File("backend/src/main/resources/transactions.json");
            if (!diskTx.exists()) {
                diskTx = new File("transactions.json");
            }
            if (diskTx.exists()) {
                try (InputStream in = new FileInputStream(diskTx)) {
                    this.transactions = transactionLoader.load(in);
                    this.transactionSource = diskTx.getName();
                }
            } else {
                try (InputStream in = new ClassPathResource("transactions.json").getInputStream()) {
                    this.transactions = transactionLoader.load(in);
                    this.transactionSource = "transactions.json (bundled)";
                }
            }
        } catch (Exception e) {
            log.error("Failed to initialize compliance data", e);
        }
    }

    public synchronized List<Rule> getRules() {
        return new ArrayList<>(this.rules);
    }

    public synchronized List<Transaction> getTransactions() {
        return new ArrayList<>(this.transactions);
    }

    public synchronized String getTransactionSource() {
        return this.transactionSource;
    }

    public synchronized String getRulesSource() {
        return this.rulesFile != null ? this.rulesFile.getPath() : "rules.csv";
    }

    public Map<String, Object> getSchema() {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String name : RuleEngine.FIELDS) {
            String type = RuleEngine.fieldType(name);
            Set<String> allowed = RuleEngine.allowedOperators(name);
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("name", name);
            spec.put("type", type);
            spec.put("operators", RuleEngine.OPERATORS.stream().filter(allowed::contains).toList());
            if (type.equals("hour")) {
                spec.put("min", RuleEngine.HOUR_MIN);
                spec.put("max", RuleEngine.HOUR_MAX);
            }
            fields.add(spec);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("fields", fields);
        schema.put("severities", Arrays.stream(Severity.values()).map(Enum::name).toList());
        return schema;
    }

    public synchronized Map<String, Object> evaluate() {
        List<Map<String, Object>> results = new ArrayList<>();
        int totalBreaches = 0;
        int breachedTransactions = 0;

        for (Transaction t : transactions) {
            List<Rule> breaches = ruleEngine.breaches(t, rules).stream()
                    .sorted(Comparator.comparing(Rule::ruleId))
                    .toList();

            boolean hasBreaches = !breaches.isEmpty();
            if (hasBreaches) {
                breachedTransactions++;
                totalBreaches += breaches.size();
            }

            List<Map<String, String>> breachDetails = breaches.stream().map(r -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("ruleId", r.ruleId());
                m.put("ruleName", r.ruleName() != null ? r.ruleName() : "");
                m.put("severity", r.severity().name());
                m.put("field", r.field());
                m.put("operator", r.operator().symbol());
                m.put("value", r.value() != null ? r.value() : "");
                return m;
            }).toList();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("transactionId", t.transactionId());
            item.put("timestamp", t.timestamp() != null ? t.timestamp().toString() : "");
            item.put("amount", t.amount());
            item.put("currency", t.currency());
            item.put("country", t.country());
            item.put("accountType", t.accountType());
            item.put("transactionType", t.transactionType());
            item.put("customerId", t.customerId());
            item.put("breached", hasBreaches);
            item.put("breachedRules", breachDetails);
            results.add(item);
        }

        String rawReport = outputFormatter.format(transactions, rules, ruleEngine);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalTransactions", transactions.size());
        response.put("breachedTransactions", breachedTransactions);
        response.put("compliantTransactions", transactions.size() - breachedTransactions);
        response.put("totalBreaches", totalBreaches);
        response.put("transactionSource", transactionSource);
        response.put("rulesSource", getRulesSource());
        response.put("results", results);
        response.put("rawReport", rawReport);
        return response;
    }

    public synchronized Map<String, Object> addRule(Map<String, String> input) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            String existingCsv = rulesFile != null && rulesFile.exists()
                    ? Files.readString(rulesFile.toPath(), StandardCharsets.UTF_8)
                    : "rule_id,rule_name,field,operator,value,severity\n";

            String ruleId = nextRuleId(existingCsv);
            String row = String.join(",",
                    ruleId,
                    quoteCell(input.get("rule_name")),
                    quoteCell(input.get("field")),
                    quoteCell(input.get("operator")),
                    quoteCell(input.get("value")),
                    quoteCell(input.get("severity"))
            );

            String candidate = existingCsv;
            if (!candidate.endsWith("\n") && !candidate.isEmpty()) {
                candidate += "\n";
            }
            candidate += row + "\n";

            List<String> problems = ruleLoader.validate(new StringReader(candidate));
            if (!problems.isEmpty()) {
                response.put("ok", false);
                response.put("problems", problems);
                return response;
            }

            // Save candidate to file
            if (rulesFile != null) {
                Files.writeString(rulesFile.toPath(), candidate, StandardCharsets.UTF_8);
            }
            this.rules = ruleLoader.load(new StringReader(candidate));

            response.put("ok", true);
            response.put("ruleId", ruleId);
            return response;
        } catch (Exception e) {
            log.error("Failed to add rule", e);
            response.put("ok", false);
            response.put("problems", List.of(e.getMessage() != null ? e.getMessage() : "Unknown error"));
            return response;
        }
    }

    public synchronized Map<String, Object> updateTransactions(String jsonContent, String filename) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            byte[] bytes = jsonContent.getBytes(StandardCharsets.UTF_8);
            List<String> problems = transactionLoader.validate(new ByteArrayInputStream(bytes));
            if (!problems.isEmpty()) {
                response.put("ok", false);
                response.put("problems", problems);
                return response;
            }

            List<Transaction> loaded = transactionLoader.load(new ByteArrayInputStream(bytes));
            this.transactions = loaded;
            this.transactionSource = filename != null && !filename.isBlank() ? filename : "custom_upload.json";

            response.put("ok", true);
            response.put("count", loaded.size());
            response.put("transactionSource", this.transactionSource);
            return response;
        } catch (Exception e) {
            log.error("Failed to update transactions", e);
            response.put("ok", false);
            response.put("problems", List.of(e.getMessage() != null ? e.getMessage() : "Failed to parse JSON"));
            return response;
        }
    }

    private String nextRuleId(String csv) {
        int max = 0;
        String[] lines = csv.split("\\r?\\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String idCell = line.split(",")[0].trim();
            Matcher m = RULE_ID_PATTERN.matcher(idCell);
            if (m.find()) {
                try {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignored) {}
            }
        }
        return String.format("R%03d", max + 1);
    }

    private String quoteCell(String v) {
        if (v == null) return "";
        String s = v.trim();
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
