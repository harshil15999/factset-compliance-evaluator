package com.factset.compliance;

import com.factset.compliance.engine.OutputFormatter;
import com.factset.compliance.engine.RuleEngine;
import com.factset.compliance.load.RuleLoader;
import com.factset.compliance.load.TransactionLoader;
import com.factset.compliance.model.Rule;
import com.factset.compliance.model.Severity;
import com.factset.compliance.model.Transaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Headless CLI application and entry point for the Transaction Compliance Evaluator.
 *
 * <p>Configured strictly with {@link WebApplicationType#NONE} to eliminate embedded Tomcat
 * and web server overhead, ensuring deterministic zero-collision CLI execution.
 *
 * <h3>Command-Line Modes:</h3>
 * <ul>
 *   <li><b>Standard Evaluation</b>: {@code java -jar app.jar <transactions.json> <rules.csv>}<br>
 *       Validates rules, evaluates all transactions, and prints the standard TSV report to stdout.</li>
 *   <li><b>Default Evaluation</b>: {@code java -jar app.jar}<br>
 *       Evaluates default bundled or working directory files.</li>
 *   <li><b>Pre-flight Validation</b>: {@code java -jar app.jar validate [<transactions.json> <rules.csv>]}<br>
 *       Inspects files for schema or semantic errors without executing the compliance report.</li>
 *   <li><b>Schema Introspection</b>: {@code java -jar app.jar schema}<br>
 *       Prints the rule vocabulary and field constraints as JSON for external tools and the web dashboard.</li>
 * </ul>
 */
@SpringBootApplication
public class ComplianceApplication implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ComplianceApplication.class);

    private final TransactionLoader transactionLoader;
    private final RuleLoader ruleLoader;
    private final RuleEngine ruleEngine;
    private final OutputFormatter outputFormatter;

    /**
     * Constructs the main application with necessary evaluation and loading dependencies.
     *
     * @param transactionLoader Loader for reading and validating transaction JSON payloads.
     * @param ruleLoader        Loader for parsing and validating CSV rule definitions.
     * @param ruleEngine        Core compliance rule evaluation engine.
     * @param outputFormatter   Formatter for rendering standard TSV reports.
     */
    public ComplianceApplication(TransactionLoader transactionLoader, RuleLoader ruleLoader,
                                 RuleEngine ruleEngine, OutputFormatter outputFormatter) {
        this.transactionLoader = transactionLoader;
        this.ruleLoader = ruleLoader;
        this.ruleEngine = ruleEngine;
        this.outputFormatter = outputFormatter;
    }

    /**
     * Main application entry point. Launches Spring Boot in headless CLI mode.
     *
     * @param args Command-line arguments.
     */
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ComplianceApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> files = args.getNonOptionArgs();

        if (!files.isEmpty() && files.get(0).equals("schema")) {
            System.out.println(schemaJson());
            System.exit(0);
            return;
        }

        boolean validate = !files.isEmpty() && files.get(0).equals("validate");
        if (validate) {
            files = files.subList(1, files.size());
        }

        if (files.size() == 1) {
            System.err.println("Usage: pass both <transactions.json> <rules.csv>, or neither to use the default copies.");
            System.exit(2);
            return;
        }

        Resource txRes;
        Resource rulesRes;
        if (files.size() == 2) {
            txRes = new FileSystemResource(files.get(0));
            rulesRes = new FileSystemResource(files.get(1));
        } else {
            txRes = new FileSystemResource("transactions.json");
            if (!txRes.exists()) txRes = new FileSystemResource("backend/src/main/resources/transactions.json");
            if (!txRes.exists()) txRes = new ClassPathResource("transactions.json");

            rulesRes = new FileSystemResource("rules.csv");
            if (!rulesRes.exists()) rulesRes = new FileSystemResource("backend/src/main/resources/rules.csv");
            if (!rulesRes.exists()) rulesRes = new ClassPathResource("rules.csv");
        }

        if (validate) {
            List<String> problems = new ArrayList<>(validateTransactions(txRes));
            problems.addAll(validateRules(rulesRes));
            problems.forEach(System.out::println);
            System.out.println(problems.isEmpty() ? "OK: inputs are valid" : problems.size() + " problem(s) found");
            System.exit(problems.isEmpty() ? 0 : 1);
            return;
        }

        // Report run: Always validate rules first and abort if any are invalid per candidate-brief
        List<String> ruleProblems = validateRules(rulesRes);
        if (!ruleProblems.isEmpty()) {
            ruleProblems.forEach(log::error);
            log.error("rules.csv has {} problem(s); refusing to run.", ruleProblems.size());
            System.exit(1);
        }

        List<Transaction> transactions;
        try (InputStream in = txRes.getInputStream()) {
            transactions = transactionLoader.load(in);
        }
        List<Rule> rules;
        try (Reader reader = new InputStreamReader(rulesRes.getInputStream(), StandardCharsets.UTF_8)) {
            rules = ruleLoader.load(reader);
        }
        System.out.print(outputFormatter.format(transactions, rules, ruleEngine));
        System.exit(0);
    }

    /** The rule vocabulary as JSON, derived entirely from RuleEngine + Severity (the single source). */
    private String schemaJson() throws Exception {
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
        return new ObjectMapper().writeValueAsString(schema);
    }

    private List<String> validateTransactions(Resource txRes) throws Exception {
        try (InputStream in = txRes.getInputStream()) {
            return transactionLoader.validate(in);
        }
    }

    private List<String> validateRules(Resource rulesRes) throws Exception {
        try (Reader reader = new InputStreamReader(rulesRes.getInputStream(), StandardCharsets.UTF_8)) {
            return ruleLoader.validate(reader);
        }
    }
}
