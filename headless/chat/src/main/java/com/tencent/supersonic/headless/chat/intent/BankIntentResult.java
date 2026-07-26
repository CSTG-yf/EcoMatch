package com.tencent.supersonic.headless.chat.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
public class BankIntentResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String originalText;
    private String normalizedText;
    private BankBusinessScene scene;
    private BankIntentType intent;
    private double confidence;
    private boolean clarificationRequired;
    private List<IntentCandidate> intentCandidates = new ArrayList<>();
    private List<MetricCandidate> metrics = new ArrayList<>();
    private List<OrganizationSlot> organizations = new ArrayList<>();
    private TimeSlot time;
    private List<FilterSlot> filters = new ArrayList<>();
    private List<Clarification> clarifications = new ArrayList<>();
    private List<String> reasons = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntentCandidate implements Serializable {
        private static final long serialVersionUID = 1L;

        private BankIntentType intent;
        private double confidence;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricCandidate implements Serializable {
        private static final long serialVersionUID = 1L;

        private String code;
        private String name;
        private String matchedText;
        private double confidence;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrganizationSlot implements Serializable {
        private static final long serialVersionUID = 1L;

        private String code;
        private String name;
        private String matchedText;
        private double confidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeSlot implements Serializable {
        private static final long serialVersionUID = 1L;

        private String expression;
        private LocalDate startDate;
        private LocalDate endDate;
        private String granularity;
        private boolean ambiguous;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilterSlot implements Serializable {
        private static final long serialVersionUID = 1L;

        private String field;
        private String operator;
        private String value;
        private String sourceText;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Clarification implements Serializable {
        private static final long serialVersionUID = 1L;

        private String type;
        private String question;
        private List<String> options;
        private String reason;
    }
}
