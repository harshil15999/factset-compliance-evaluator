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
 *       Inspects both files for schema or semantic errors without executing the compliance report.</li>
 *   <li><b>Rules-Only Validation</b>: {@code java -jar app.jar validate-rules [<rules.csv>]}<br>
 *       Inspects rules CSV for schema or semantic errors without touching transaction data.</li>
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

    /**
     * Dispatches command-line execution based on provided non-option arguments.
     *
     * <p>Execution flows:
     * <ol>
     *   <li>{@code schema}: Generates and dumps the field/operator JSON metadata for frontend integration.</li>
     *   <li>{@code validate-rules}: Standalone CSV rule check for fast UI additions without transaction loading.</li>
     *   <li>{@code validate}: Dry-run inspection verifying both transaction and rule datasets.</li>
     *   <li>Default report execution: Validates rules strictly, then streams evaluated TSV records to stdout.</li>
     * </ol>
     *
     * @param args Application command-line arguments.
     * @throws Exception If an unhandled file I/O or evaluation exception occurs.
     */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> files = args.getNonOptionArgs();

        // Mode 1: Schema Introspection (JSON output for web dashboard / external tooling)
        if (!files.isEmpty() && "schema".equals(files.get(0))) {
            System.out.println(schemaJson());
            System.exit(0);
            return;
        }

        // Mode 2: Standalone Rule Validation (validates rules without requiring transactions)
        if (!files.isEmpty() && "validate-rules".equals(files.get(0))) {
            Resource rulesRes = resolveRequiredResource(files.size() > 1 ? files.get(1) : null, "rules.csv", "rules");
            exitWithValidationResult(validate(null, rulesRes), "rules are");
            return;
        }

        // Mode 3: Pre-flight Validation (verifies both transactions and rules without report generation)
        boolean validate = !files.isEmpty() && "validate".equals(files.get(0));
        if (validate) {
            files = files.subList(1, files.size());
        }

        // Enforce balanced file input: either both files provided or use default data directory copies
        if (files.size() == 1) {
            System.err.println("Usage: pass both <transactions.json> <rules.csv>, or neither to use the default copies.");
            System.exit(2);
            return;
        }

        Resource txRes = resolveRequiredResource(files.size() == 2 ? files.get(0) : null, "transactions.json", "transactions");
        Resource rulesRes = resolveRequiredResource(files.size() == 2 ? files.get(1) : null, "rules.csv", "rules");

        if (validate) {
            exitWithValidationResult(validate(txRes, rulesRes), "inputs are");
            return;
        }

        // Mode 4: Standard Evaluation Report Run
        // Per candidate-brief: always validate rules first and abort if any are invalid
        validateRulesOrAbort(rulesRes);

        // Load datasets through dedicated loaders with automatic stream closing
        List<Transaction> transactions = loadTransactions(txRes);
        List<Rule> rules = loadRules(rulesRes);

        // Format and stream final TSV evaluation report to stdout
        System.out.print(outputFormatter.format(transactions, rules, ruleEngine));
        System.exit(0);
    }

    /**
     * Resolves a required resource path, verifying that the target exists.
     *
     * <p>If the file cannot be located across the resolution search chain, an error message
     * is written to {@code stderr} and the application immediately aborts with exit code 2.
     *
     * @param path            User-provided path (can be {@code null} or blank to use defaults).
     * @param defaultFileName Default file name to search for (e.g. {@code rules.csv}).
     * @param type            Descriptive resource name for diagnostic error messages (e.g. {@code rules}).
     * @return The resolved and verified {@link Resource}.
     */
    private Resource resolveRequiredResource(String path, String defaultFileName, String type) {
        Resource res = resolveResource(path, defaultFileName);
        if (!res.exists()) {
            log.error("Error: {} file not found: {}", type, res.getDescription());
            System.err.println("Error: " + type + " file not found: " + res.getDescription());
            System.exit(2);
        }
        return res;
    }

    /**
     * Resolves a resource using a layered fallback hierarchy:
     * <ol>
     *   <li>Direct file system path (if explicitly supplied).</li>
     *   <li>Relative path under the canonical {@code data/} directory.</li>
     *   <li>Project root working directory.</li>
     *   <li>Backend source resources directory (for local IDE development).</li>
     *   <li>Bundled jar classpath resource (fallback distribution).</li>
     * </ol>
     *
     * @param path            Candidate file path supplied via CLI arguments, or {@code null}.
     * @param defaultFileName Canonical filename to locate when {@code path} is omitted.
     * @return The best-matching {@link Resource} candidate.
     */
    private Resource resolveResource(String path, String defaultFileName) {
        if (path != null && !path.isBlank()) {
            Resource res = new FileSystemResource(path);
            if (!res.exists()) {
                res = new FileSystemResource("data/" + path);
            }
            if (!res.exists()) {
                res = new FileSystemResource("../" + path);
            }
            if (!res.exists()) {
                res = new FileSystemResource("../data/" + path);
            }
            return res;
        }
        Resource res = new FileSystemResource("data/" + defaultFileName);
        if (!res.exists()) res = new FileSystemResource("../data/" + defaultFileName);
        if (!res.exists()) res = new FileSystemResource(defaultFileName);
        if (!res.exists()) res = new FileSystemResource("backend/src/main/resources/" + defaultFileName);
        if (!res.exists()) res = new ClassPathResource(defaultFileName);
        return res;
    }

    /**
     * Validates candidate transaction and rule resources, aggregating all diagnostics.
     *
     * <p>Supports partial validation (e.g., passing {@code txRes = null} validates only rules).
     *
     * @param txRes    Optional transaction resource to inspect.
     * @param rulesRes Optional rules resource to inspect.
     * @return Aggregated list of diagnostic problem descriptions, or empty if valid.
     * @throws Exception If an error occurs opening or reading resource streams.
     */
    private List<String> validate(Resource txRes, Resource rulesRes) throws Exception {
        List<String> problems = new ArrayList<>();
        if (txRes != null) {
            problems.addAll(validateTransactions(txRes));
        }
        if (rulesRes != null) {
            problems.addAll(validateRules(rulesRes));
        }
        return problems;
    }

    /**
     * Validates rules prior to report generation, aborting execution if semantic errors exist.
     *
     * <p>Enforces the core requirement from {@code candidate-brief.md}: corrupted or semantically
     * invalid rules must refuse to run to prevent erroneous compliance breaches from being published.
     * Errors are directed to SLF4J (and stderr) so standard stdout stays reserved for TSV data.
     *
     * @param rulesRes The rule resource to validate.
     * @throws Exception If an I/O error occurs opening the rule resource.
     */
    private void validateRulesOrAbort(Resource rulesRes) throws Exception {
        List<String> problems = validateRules(rulesRes);
        if (!problems.isEmpty()) {
            problems.forEach(log::error);
            log.error("rules.csv has {} problem(s); refusing to run.", problems.size());
            System.exit(1);
        }
    }

    /**
     * Evaluates validation diagnostic output, printing human-readable status and terminating execution.
     *
     * <ul>
     *   <li>On success (no problems): Prints {@code OK: <target> valid} and exits with code 0.</li>
     *   <li>On failure: Prints each diagnosed issue, prints the total problem count, and exits with code 1.</li>
     * </ul>
     *
     * @param problems List of validation error strings.
     * @param target   Target entity description for output formatting (e.g. {@code "rules are"} or {@code "inputs are"}).
     */
    private void exitWithValidationResult(List<String> problems, String target) {
        if (problems.isEmpty()) {
            System.out.println("OK: " + target + " valid");
            System.exit(0);
        } else {
            problems.forEach(log::error);
            log.error("{} problem(s) found", problems.size());
            problems.forEach(System.out::println);
            System.out.println(problems.size() + " problem(s) found");
            System.exit(1);
        }
    }

    /**
     * Streams and validates transactions using the configured {@link TransactionLoader}.
     *
     * @param txRes The transaction resource to validate.
     * @return List of transaction syntax and validation errors.
     * @throws Exception If an I/O error occurs reading the resource.
     */
    private List<String> validateTransactions(Resource txRes) throws Exception {
        try (InputStream in = txRes.getInputStream()) {
            return transactionLoader.validate(in);
        }
    }

    /**
     * Parses and validates rule definitions using the configured {@link RuleLoader}.
     *
     * @param rulesRes The CSV rule resource to validate.
     * @return List of CSV syntax and semantic rule validation errors.
     * @throws Exception If an I/O error occurs reading the resource.
     */
    private List<String> validateRules(Resource rulesRes) throws Exception {
        try (Reader reader = new InputStreamReader(rulesRes.getInputStream(), StandardCharsets.UTF_8)) {
            return ruleLoader.validate(reader);
        }
    }

    /**
     * Ingests transactions from the resource into memory for rule evaluation.
     *
     * @param txRes The transaction resource.
     * @return Loaded list of valid {@link Transaction} instances.
     * @throws Exception If an I/O error occurs reading the stream.
     */
    private List<Transaction> loadTransactions(Resource txRes) throws Exception {
        try (InputStream in = txRes.getInputStream()) {
            return transactionLoader.load(in);
        }
    }

    /**
     * Ingests rules from the CSV resource into memory for compliance matching.
     *
     * @param rulesRes The rule resource.
     * @return Loaded list of valid {@link Rule} instances.
     * @throws Exception If an I/O error occurs reading the reader.
     */
    private List<Rule> loadRules(Resource rulesRes) throws Exception {
        try (Reader reader = new InputStreamReader(rulesRes.getInputStream(), StandardCharsets.UTF_8)) {
            return ruleLoader.load(reader);
        }
    }

    /**
     * Generates the rule engine vocabulary as a JSON payload for external consumers.
     *
     * <p>Derived dynamically from {@link RuleEngine} and {@link Severity} to guarantee that
     * frontend validation and documentation cannot drift from backend engine capabilities.
     *
     * @return JSON string representing allowed fields, data types, operators, and severities.
     * @throws Exception If serialization to JSON fails.
     */
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
}
