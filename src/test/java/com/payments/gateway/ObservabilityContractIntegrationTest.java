package com.payments.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.payments.gateway.support.IntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * ADR-027: alert rules and dashboards only query series the application really exports. Rare-event counters must
 * exist at zero before the first event, or {@code increase()} alerts miss it.
 */
class ObservabilityContractIntegrationTest extends IntegrationTest {

    private static final Path RULES = Path.of("deploy/observability/prometheus/rules/payment-gateway.yml");
    private static final Path DASHBOARDS = Path.of("deploy/observability/grafana/dashboards");
    private static final Path RUNBOOKS = Path.of("docs/runbooks.md");
    private static final String RUNBOOK_URL =
            "https://github.com/Deepika-1201/Payment-Orchestrator/blob/main/docs/runbooks.md#";
    private static final Pattern SERIES = Pattern.compile("\\b(?:pg|http_server_requests|hikaricp|jvm|process)_[a-z0-9_]+\\b");

    @Test
    void everySeriesTheRulesAndDashboardsQueryIsExportedAfterOnePayment() throws IOException {
        payAndSucceed(createMerchant(ALPHA), 10_000);

        Set<String> exported = scrape().lines()
                .filter(line -> !line.startsWith("#") && !line.isBlank())
                .map(line -> line.split("[{ ]", 2)[0])
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> queried = new TreeSet<>();
        for (String expr : queries()) {
            Matcher matcher = SERIES.matcher(expr);
            while (matcher.find()) {
                queried.add(matcher.group());
            }
        }

        assertThat(queried).as("series found in rules and dashboards").hasSizeGreaterThan(20);
        Set<String> missing = new TreeSet<>(queried);
        missing.removeAll(exported);
        assertThat(missing).as("queried but not exported at /actuator/prometheus").isEmpty();
    }

    @Test
    void latencyHistogramsCarryTheSloBucketBoundaries() {
        payAndSucceed(createMerchant(ALPHA), 10_000);

        List<String> lines = scrape().lines().toList();

        for (String le : List.of("0.04", "0.15", "0.2")) {
            assertThat(lines).as("http_server_requests bucket le=%s", le)
                    .anyMatch(line -> line.startsWith("http_server_requests_seconds_bucket{")
                            && line.contains("le=\"" + le + "\""));
        }
        assertThat(lines).anyMatch(line -> line.startsWith("pg_provider_call_seconds_bucket{"));
    }

    @Test
    void everyAlertHasASeverityASummaryAndARunbookSection() throws IOException {
        Set<String> runbookSections = Files.readAllLines(RUNBOOKS).stream()
                .filter(line -> line.startsWith("### "))
                .map(line -> line.substring(4).trim())
                .collect(Collectors.toSet());
        List<String> alerts = new ArrayList<>();
        for (JsonNode rule : rules()) {
            if (!rule.has("alert")) {
                continue;
            }
            String name = rule.get("alert").asString();
            alerts.add(name);
            assertThat(rule.path("labels").path("severity").asString()).as(name).isIn("critical", "warning");
            assertThat(rule.path("annotations").path("summary").asString()).as(name).isNotBlank();
            assertThat(rule.path("annotations").path("runbook_url").asString()).as(name)
                    .isEqualTo(RUNBOOK_URL + name.toLowerCase(Locale.ROOT));
            assertThat(runbookSections).as("docs/runbooks.md section for %s", name).contains(name);
        }
        assertThat(alerts).hasSizeGreaterThan(15).doesNotHaveDuplicates();
    }

    private String scrape() {
        Response response = send("GET", "/actuator/prometheus", Map.of(), null);
        assertThat(response.status()).isEqualTo(200);
        return response.raw();
    }

    private static List<JsonNode> rules() throws IOException {
        JsonNode groups = YAMLMapper.builder().build().readTree(Files.readString(RULES)).get("groups");
        List<JsonNode> rules = new ArrayList<>();
        groups.forEach(group -> group.get("rules").forEach(rules::add));
        return rules;
    }

    private static List<String> queries() throws IOException {
        List<String> expressions = new ArrayList<>();
        rules().forEach(rule -> expressions.add(rule.get("expr").asString()));
        JsonMapper mapper = JsonMapper.builder().build();
        try (Stream<Path> files = Files.list(DASHBOARDS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                JsonNode dashboard = mapper.readTree(Files.readString(file));
                dashboard.path("panels").forEach(panel ->
                        panel.path("targets").forEach(target -> expressions.add(target.path("expr").asString())));
                dashboard.path("templating").path("list").forEach(variable ->
                        expressions.add(variable.path("definition").asString("")));
            }
        }
        assertThat(expressions).isNotEmpty();
        return expressions.stream().filter(e -> !e.isBlank()).collect(Collectors.toList());
    }
}
