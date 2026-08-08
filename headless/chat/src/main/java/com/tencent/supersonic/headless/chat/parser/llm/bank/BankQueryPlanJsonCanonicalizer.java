package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites common model JSON mistakes into the strict {@link BankQueryPlan} shape before Jackson
 * deserialization. Unknown top-level keys are dropped so FAIL_ON_UNKNOWN_PROPERTIES does not
 * reject near-correct plans that invent helper fields.
 */
public final class BankQueryPlanJsonCanonicalizer {

    private static final Set<String> ALLOWED_ROOT = Set.of("version", "intent", "metrics",
            "dimensions", "organizations", "time", "filters", "calculation", "orderBy", "limit",
            "output", "derivedMetrics", "action");

    private static final Map<String, String> ROOT_ALIASES = Map.of("time_range", "time",
            "timeRange", "time", "orgs", "organizations", "organisation", "organizations",
            "organizations_list", "organizations");

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private BankQueryPlanJsonCanonicalizer() {}

    public static ObjectNode canonicalize(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("plan root must be a JSON object");
        }
        ObjectNode in = (ObjectNode) root;
        ObjectNode out = F.objectNode();

        // Promote aliased roots.
        ObjectNode staged = in.deepCopy();
        for (Map.Entry<String, String> alias : ROOT_ALIASES.entrySet()) {
            if (staged.has(alias.getKey()) && !staged.has(alias.getValue())) {
                staged.set(alias.getValue(), staged.get(alias.getKey()));
            }
        }

        out.put("version", textOr(staged, "version", BankQueryPlan.CURRENT_VERSION));
        if (staged.hasNonNull("intent")) {
            out.put("intent", staged.get("intent").asText());
        }
        if (staged.hasNonNull("action")) {
            out.put("action", staged.get("action").asText());
        }
        out.set("metrics", canonicalizeMetrics(staged.get("metrics")));
        out.set("dimensions", canonicalizeDimensions(staged.get("dimensions")));
        out.set("organizations", canonicalizeOrganizations(staged.get("organizations")));
        out.set("time", canonicalizeTime(staged.get("time")));
        out.set("filters", canonicalizeFilters(staged.get("filters")));
        out.set("calculation", canonicalizeCalculation(staged.get("calculation")));
        out.set("orderBy", canonicalizeOrderBy(staged.get("orderBy"), staged.get("sort")));
        if (staged.has("limit") && !staged.get("limit").isNull()) {
            if (staged.get("limit").isNumber()) {
                out.put("limit", staged.get("limit").asInt());
            } else if (staged.get("limit").isTextual()
                    && StringUtils.isNumeric(staged.get("limit").asText())) {
                out.put("limit", Integer.parseInt(staged.get("limit").asText()));
            } else {
                out.putNull("limit");
            }
        } else {
            out.putNull("limit");
        }
        out.set("output", canonicalizeOutput(staged.get("output"), out));
        if (staged.has("derivedMetrics") && staged.get("derivedMetrics").isArray()) {
            out.set("derivedMetrics", staged.get("derivedMetrics").deepCopy());
        }

        // Drop anything else (additional_analysis, sql, ...).
        Iterator<String> names = out.fieldNames();
        Set<String> keep = new LinkedHashSet<>();
        while (names.hasNext()) {
            String name = names.next();
            if (ALLOWED_ROOT.contains(name)) {
                keep.add(name);
            }
        }
        ObjectNode filtered = F.objectNode();
        for (String name : keep) {
            filtered.set(name, out.get(name));
        }
        return filtered;
    }

    private static ArrayNode canonicalizeMetrics(JsonNode metricsNode) {
        ArrayNode metrics = F.arrayNode();
        if (metricsNode == null || metricsNode.isNull()) {
            return metrics;
        }
        if (metricsNode.isTextual()) {
            metrics.add(metricObject(metricsNode.asText(), "DEFAULT"));
            return metrics;
        }
        if (!metricsNode.isArray()) {
            return metrics;
        }
        for (JsonNode item : metricsNode) {
            if (item == null || item.isNull()) {
                continue;
            }
            if (item.isTextual()) {
                metrics.add(metricObject(item.asText(), "DEFAULT"));
                continue;
            }
            if (!item.isObject()) {
                continue;
            }
            String biz = firstText(item, "bizName", "zb_id", "zbId", "metricCode", "metric",
                    "code", "name");
            String agg = firstText(item, "aggregation", "agg");
            if (StringUtils.isBlank(agg)) {
                agg = "DEFAULT";
            }
            ObjectNode metric = metricObject(biz, agg.toUpperCase(Locale.ROOT));
            String alias = firstText(item, "alias");
            if (StringUtils.isNotBlank(alias)) {
                metric.put("alias", alias);
            }
            metrics.add(metric);
        }
        return metrics;
    }

    private static ObjectNode metricObject(String bizName, String aggregation) {
        ObjectNode metric = F.objectNode();
        if (StringUtils.isNotBlank(bizName)) {
            metric.put("bizName", BankQueryPlanAliasNormalizer.canonicalizeMetric(bizName));
        }
        metric.put("aggregation", aggregation);
        return metric;
    }

    private static ArrayNode canonicalizeDimensions(JsonNode dimensionsNode) {
        ArrayNode dimensions = F.arrayNode();
        if (dimensionsNode == null || !dimensionsNode.isArray()) {
            return dimensions;
        }
        // Keep raw dimension tokens here; BankQueryPlanAliasNormalizer has whitelist context and
        // decides whether Chinese labels (机构) stay or become bank_organization.
        for (JsonNode item : dimensionsNode) {
            if (item != null && item.isTextual() && StringUtils.isNotBlank(item.asText())) {
                dimensions.add(item.asText().trim());
            }
        }
        return dimensions;
    }

    private static ArrayNode canonicalizeOrganizations(JsonNode orgsNode) {
        ArrayNode orgs = F.arrayNode();
        if (orgsNode == null || orgsNode.isNull()) {
            return orgs;
        }
        if (orgsNode.isTextual()) {
            orgs.add(orgObject(orgsNode.asText()));
            return orgs;
        }
        if (!orgsNode.isArray()) {
            return orgs;
        }
        for (JsonNode item : orgsNode) {
            if (item == null || item.isNull()) {
                continue;
            }
            if (item.isTextual()) {
                orgs.add(orgObject(item.asText()));
                continue;
            }
            if (item.isObject()) {
                String code = firstText(item, "code", "org_id", "orgId", "orgCode", "id");
                ObjectNode org = orgObject(code);
                String biz = firstText(item, "bizName", "name", "org_name");
                if (StringUtils.isNotBlank(biz)) {
                    org.put("bizName", biz);
                }
                orgs.add(org);
            }
        }
        return orgs;
    }

    private static ObjectNode orgObject(String code) {
        ObjectNode org = F.objectNode();
        if (StringUtils.isNotBlank(code)) {
            String normalized = code.trim().toUpperCase(Locale.ROOT);
            // "ORG###=A市" → ORG###
            int eq = normalized.indexOf('=');
            if (eq > 0) {
                normalized = normalized.substring(0, eq).trim();
            }
            org.put("code", normalized);
        }
        return org;
    }

    private static ObjectNode canonicalizeTime(JsonNode timeNode) {
        ObjectNode time = F.objectNode();
        if (timeNode != null && timeNode.isObject()) {
            String start = firstText(timeNode, "startDate", "start", "from", "begin");
            String end = firstText(timeNode, "endDate", "end", "to");
            putDate(time, "startDate", start);
            putDate(time, "endDate", end);
            String gran = firstText(timeNode, "granularity", "grain");
            if (StringUtils.isNotBlank(gran)) {
                time.put("granularity", mapGranularity(gran));
            } else {
                time.put("granularity", "DAY");
            }
            String comparison = firstText(timeNode, "comparison", "compare");
            time.put("comparison", StringUtils.isBlank(comparison) ? "NONE"
                    : comparison.toUpperCase(Locale.ROOT));
            putDate(time, "baselineStartDate",
                    firstText(timeNode, "baselineStartDate", "baseline_start", "baselineStart"));
            putDate(time, "baselineEndDate",
                    firstText(timeNode, "baselineEndDate", "baseline_end", "baselineEnd"));
        } else {
            time.putNull("startDate");
            time.putNull("endDate");
            time.put("granularity", "DAY");
            time.put("comparison", "NONE");
        }
        return time;
    }

    private static String mapGranularity(String raw) {
        String g = raw.trim().toUpperCase(Locale.ROOT);
        if (g.contains("QUARTER") || g.contains("季度")) {
            return "QUARTER";
        }
        if (g.contains("MONTH") || g.contains("月")) {
            return "MONTH";
        }
        if (g.contains("YEAR") || g.contains("年")) {
            return "YEAR";
        }
        if (g.contains("RANGE")) {
            return "RANGE";
        }
        if (g.contains("HALF")) {
            return "HALF_YEAR";
        }
        return g;
    }

    private static ArrayNode canonicalizeFilters(JsonNode filtersNode) {
        if (filtersNode != null && filtersNode.isArray()) {
            return (ArrayNode) filtersNode.deepCopy();
        }
        return F.arrayNode();
    }

    private static ObjectNode canonicalizeCalculation(JsonNode calcNode) {
        ObjectNode calc = F.objectNode();
        if (calcNode != null && calcNode.isObject()) {
            String type = firstText(calcNode, "type");
            calc.put("type", StringUtils.isBlank(type) ? "DIRECT" : type.toUpperCase(Locale.ROOT));
            String baseline = firstText(calcNode, "baseline", "denominator");
            if (StringUtils.isNotBlank(baseline)) {
                calc.put("baseline", BankQueryPlanAliasNormalizer.canonicalizeMetric(baseline));
            } else {
                calc.putNull("baseline");
            }
        } else {
            calc.put("type", "DIRECT");
            calc.putNull("baseline");
        }
        return calc;
    }

    private static ArrayNode canonicalizeOrderBy(JsonNode orderByNode, JsonNode sortNode) {
        ArrayNode orderBy = F.arrayNode();
        JsonNode source = orderByNode != null && !orderByNode.isNull() ? orderByNode : sortNode;
        if (source == null || !source.isArray()) {
            return orderBy;
        }
        for (JsonNode item : source) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String field = firstText(item, "field", "column", "bizName");
            String direction = firstText(item, "direction", "order");
            if (StringUtils.isBlank(field)) {
                continue;
            }
            ObjectNode ob = F.objectNode();
            String metric = BankQueryPlanAliasNormalizer.canonicalizeMetric(field);
            ob.put("field", metric.equals(field)
                    ? BankQueryPlanAliasNormalizer.canonicalizeDimension(field) : metric);
            if (StringUtils.isNotBlank(direction)) {
                String dir = direction.toUpperCase(Locale.ROOT);
                ob.put("direction", dir.startsWith("ASC") ? "ASC" : "DESC");
            } else {
                ob.put("direction", "DESC");
            }
            orderBy.add(ob);
        }
        return orderBy;
    }

    private static ObjectNode canonicalizeOutput(JsonNode outputNode, ObjectNode planSoFar) {
        ObjectNode output = F.objectNode();
        ArrayNode columns = F.arrayNode();
        boolean orderSensitive = false;
        if (outputNode != null && outputNode.isObject()) {
            JsonNode cols = outputNode.get("columns");
            if (cols != null && cols.isArray()) {
                for (JsonNode col : cols) {
                    if (col != null && col.isTextual() && StringUtils.isNotBlank(col.asText())) {
                        String raw = col.asText().trim();
                        // Only map metric display names here. Dimension Chinese labels (机构) are
                        // left for AliasNormalizer which has the schema whitelist.
                        String metric = BankQueryPlanAliasNormalizer.canonicalizeMetric(raw);
                        columns.add(metric);
                    }
                }
            }
            if (outputNode.has("orderSensitive")) {
                orderSensitive = outputNode.get("orderSensitive").asBoolean(false);
            }
        }
        // If model invented non-semantic columns only, fall back to metrics/dims already parsed.
        if (columns.isEmpty() || allNonSemantic(columns)) {
            columns = F.arrayNode();
            JsonNode dims = planSoFar.get("dimensions");
            if (dims != null && dims.isArray()) {
                dims.forEach(columns::add);
            }
            JsonNode metrics = planSoFar.get("metrics");
            if (metrics != null && metrics.isArray()) {
                for (JsonNode metric : metrics) {
                    if (metric != null && metric.hasNonNull("bizName")) {
                        columns.add(metric.get("bizName").asText());
                    }
                }
            }
        }
        output.set("columns", columns);
        output.put("orderSensitive", orderSensitive);
        return output;
    }

    private static boolean allNonSemantic(ArrayNode columns) {
        for (JsonNode col : columns) {
            String c = col.asText("");
            String upper = c.toUpperCase(Locale.ROOT);
            if (upper.matches("ZB\\d{3}") || c.equals("bank_organization")
                    || c.equals("bank_data_date") || upper.startsWith("DERIVED_")) {
                return false;
            }
        }
        return columns.size() > 0;
    }

    private static void putDate(ObjectNode time, String field, String value) {
        if (StringUtils.isBlank(value)) {
            time.putNull(field);
            return;
        }
        // Preserve the illegal string "null" so Jackson fails with MALFORMED_JSON (repair signal).
        if ("null".equalsIgnoreCase(value.trim())) {
            time.put(field, "null");
            return;
        }
        time.put(field, value.trim());
    }

    private static String textOr(ObjectNode node, String field, String defaultValue) {
        String value = firstText(node, field);
        return StringUtils.isBlank(value) ? defaultValue : value;
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String field : fields) {
            JsonNode child = node.get(field);
            if (child != null && child.isTextual() && StringUtils.isNotBlank(child.asText())) {
                return child.asText().trim();
            }
            if (child != null && child.isNumber()) {
                return child.asText();
            }
        }
        return null;
    }
}
