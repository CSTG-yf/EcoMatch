package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The only model-owned artifact in the constrained bank NL2SQL pipeline. It deliberately contains
 * semantic identifiers and calculation choices, never physical SQL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class BankQueryPlan {

    public static final String CURRENT_VERSION = "1.0";

    /** A strict JSON Schema used by the plan generation strategy in T3. */
    public static final String JSON_SCHEMA =
            """
                    {"type":"object","additionalProperties":false,"required":["version","intent",
                    "metrics","dimensions","organizations","time","calculation","orderBy","output"],
                    "properties":{"version":{"const":"1.0"},"intent":{"enum":["POINT_QUERY","COMPARISON",
                    "RANKING","TREND","CHANGE","RATIO","THRESHOLD","AGGREGATION"]},
                    "metrics":{"type":"array","items":{"type":"object","additionalProperties":false,
                    "required":["bizName","aggregation"],"properties":{"bizName":{"type":"string"},
                    "aggregation":{"enum":["DEFAULT","SUM","AVG","MAX","MIN","COUNT"]},
                    "alias":{"type":"string"}}}},"dimensions":{"type":"array","items":{"type":"string"}},
                    "organizations":{"type":"array","items":{"type":"object","additionalProperties":false,
                    "properties":{"code":{"type":"string"},"bizName":{"type":"string"}}}},
                    "time":{"type":"object","additionalProperties":false,"required":["startDate","endDate",
                    "granularity","comparison"],"properties":{"startDate":{"type":"string","format":"date"},
                    "endDate":{"type":"string","format":"date"},"granularity":{"enum":["DAY","MONTH",
                    "QUARTER","HALF_YEAR","YEAR","RANGE"]},"comparison":{"enum":["NONE","YEAR_OVER_YEAR",
                    "PERIOD_OVER_PERIOD","START_OF_YEAR","MOM_AND_YOY"]},"baselineStartDate":{"type":"string","format":"date"},
                    "baselineEndDate":{"type":"string","format":"date"}}},"filters":{"type":"array",
                    "items":{"type":"object","additionalProperties":false,"properties":{"field":{"type":"string"},
                    "operator":{"type":"string"},"value":{"type":"string"},"values":{"type":"array",
                    "items":{"type":"string"}}}}},"calculation":{"type":"object","additionalProperties":false,
                    "required":["type"],"properties":{"type":{"enum":["DIRECT","CHANGE","RATIO"]},
                    "baseline":{"type":"string"}}},"orderBy":{"type":"array","items":{"type":"object",
                    "additionalProperties":false,"required":["field","direction"],"properties":{"field":{"type":"string"},
                    "direction":{"enum":["ASC","DESC"]}}}},"limit":{"type":"integer","minimum":1},
                    "output":{"type":"object","additionalProperties":false,"required":["columns","orderSensitive"],
                    "properties":{"columns":{"type":"array","items":{"type":"string"}},
                    "orderSensitive":{"type":"boolean"}}}}}
                    """;

    @Builder.Default
    private String version = CURRENT_VERSION;
    private PlanAction action;
    private BankIntentType intent;
    @Builder.Default
    private List<Metric> metrics = new ArrayList<>();
    @Builder.Default
    private List<String> dimensions = new ArrayList<>();
    @Builder.Default
    private List<Organization> organizations = new ArrayList<>();
    private TimeRange time;
    @Builder.Default
    private List<Filter> filters = new ArrayList<>();
    private Calculation calculation;
    @Builder.Default
    private List<OrderBy> orderBy = new ArrayList<>();
    private Integer limit;
    private Output output;

    public enum PlanAction {
        EXECUTE, CLARIFY
    }

    public enum Aggregation {
        DEFAULT, SUM, AVG, MAX, MIN, COUNT
    }

    public enum TimeGranularity {
        DAY, MONTH, QUARTER, HALF_YEAR, YEAR, RANGE
    }

    public enum TimeComparison {
        NONE, YEAR_OVER_YEAR, PERIOD_OVER_PERIOD, START_OF_YEAR, MOM_AND_YOY
    }

    public enum CalculationType {
        DIRECT, CHANGE, RATIO
    }

    public enum SortDirection {
        ASC, DESC
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Metric {
        private String bizName;
        private Aggregation aggregation;
        private String alias;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Organization {
        private String code;
        private String bizName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeRange {
        private LocalDate startDate;
        private LocalDate endDate;
        private TimeGranularity granularity;
        private TimeComparison comparison;
        private LocalDate baselineStartDate;
        private LocalDate baselineEndDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Filter {
        private String field;
        private String operator;
        private String value;
        @Builder.Default
        private List<String> values = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Calculation {
        private CalculationType type;
        private String baseline;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderBy {
        private String field;
        private SortDirection direction;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Output {
        @Builder.Default
        private List<String> columns = new ArrayList<>();
        private boolean orderSensitive;
    }
}
