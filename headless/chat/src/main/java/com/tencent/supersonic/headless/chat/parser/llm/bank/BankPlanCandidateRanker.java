package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import lombok.Getter;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Selects a constrained-plan candidate by semantic evidence rather than by the textual shape of
 * generated S2SQL. Invalid candidates are retained only as aggregate rejection diagnostics.
 */
public class BankPlanCandidateRanker {

    private final BankQueryPlanValidator validator;

    public BankPlanCandidateRanker() {
        this(new BankQueryPlanValidator());
    }

    BankPlanCandidateRanker(BankQueryPlanValidator validator) {
        this.validator = validator;
    }

    public Candidate evaluate(BankQueryPlan plan, SemanticIntentHints hints) {
        String fingerprint = fingerprint(plan);
        BankQueryPlanValidator.ValidationResult validation = validator.validate(plan, hints);
        if (!validation.isValid()) {
            return Candidate.rejected(fingerprint, validation.summary());
        }
        return Candidate.valid(plan, fingerprint, semanticEvidence(plan, hints));
    }

    public Selection select(List<Candidate> candidates) {
        Map<String, Candidate> unique = new LinkedHashMap<>();
        List<String> rejections = new ArrayList<>();
        int inputCandidateCount = 0;
        for (Candidate candidate : candidates == null ? List.<Candidate>of() : candidates) {
            if (candidate == null) {
                continue;
            }
            inputCandidateCount++;
            if (!candidate.valid) {
                rejections.add(candidate.rejectionReason);
                continue;
            }
            unique.merge(candidate.fingerprint, candidate,
                    (left, right) -> candidateOrder().compare(left, right) <= 0 ? left : right);
        }
        Candidate selected = unique.values().stream().min(candidateOrder())
                .orElseThrow(() -> new IllegalArgumentException(
                        "no valid constrained bank query plan candidate"));
        return new Selection(selected, inputCandidateCount, unique.size(), rejections);
    }

    private Comparator<Candidate> candidateOrder() {
        return Comparator.comparingDouble(Candidate::getSemanticScore).reversed()
                .thenComparing(Candidate::getFingerprint);
    }

    private double semanticEvidence(BankQueryPlan plan, SemanticIntentHints hints) {
        double score = 40D;
        score += plan.getMetrics().size() * 8D;
        score += plan.getOrganizations().size() * 6D;
        score += plan.getDimensions().size() * 4D;
        score += plan.getFilters().size() * 3D;
        if (plan.getTime() != null && plan.getTime().getStartDate() != null
                && plan.getTime().getEndDate() != null) {
            score += 15D;
        }
        if (plan.getOutput() != null && !plan.getOutput().getColumns().isEmpty()) {
            score += 8D;
        }
        if (hints != null && Objects.equals(plan.getIntent(), hints.getExpectedIntent())) {
            score += 10D;
        }
        return score;
    }

    private String fingerprint(BankQueryPlan plan) {
        if (plan == null) {
            return "invalid-null-plan";
        }
        String source = String.join("|", String.valueOf(plan.getIntent()),
                plan.getMetrics().stream()
                        .map(metric -> metric.getBizName() + ":" + metric.getAggregation())
                        .collect(Collectors.joining(",")),
                plan.getDimensions().toString(),
                plan.getOrganizations().stream().map(
                        organization -> organization.getCode() + ":" + organization.getBizName())
                        .sorted().collect(Collectors.joining(",")),
                String.valueOf(plan.getTime()),
                plan.getFilters().stream()
                        .map(filter -> filter.getField() + ":" + filter.getOperator() + ":"
                                + filter.getValue() + ":" + filter.getValues())
                        .sorted().collect(Collectors.joining(",")),
                String.valueOf(plan.getCalculation()), plan.getOrderBy().toString(),
                String.valueOf(plan.getLimit()), String.valueOf(plan.getOutput()));
        return DigestUtils.sha256Hex(source);
    }

    @Getter
    public static final class Candidate {
        private final String fingerprint;
        private final double semanticScore;
        private final boolean valid;
        private final String rejectionReason;
        private final BankQueryPlan plan;

        private Candidate(BankQueryPlan plan, String fingerprint, double semanticScore,
                boolean valid, String rejectionReason) {
            this.plan = plan;
            this.fingerprint = fingerprint;
            this.semanticScore = semanticScore;
            this.valid = valid;
            this.rejectionReason = rejectionReason;
        }

        public static Candidate valid(String fingerprint, double semanticScore) {
            return new Candidate(null, fingerprint, semanticScore, true, null);
        }

        private static Candidate valid(BankQueryPlan plan, String fingerprint,
                double semanticScore) {
            return new Candidate(plan, fingerprint, semanticScore, true, null);
        }

        public static Candidate rejected(String fingerprint, String rejectionReason) {
            return new Candidate(null, fingerprint, Double.NEGATIVE_INFINITY, false,
                    rejectionReason);
        }
    }

    @Getter
    public static final class Selection {
        private final Candidate selected;
        private final int inputCandidateCount;
        private final int uniqueCandidateCount;
        private final List<String> rejectionReasons;

        private Selection(Candidate selected, int inputCandidateCount, int uniqueCandidateCount,
                List<String> rejectionReasons) {
            this.selected = selected;
            this.inputCandidateCount = inputCandidateCount;
            this.uniqueCandidateCount = uniqueCandidateCount;
            this.rejectionReasons = List.copyOf(rejectionReasons);
        }

        public int getRejectedCandidateCount() {
            return rejectionReasons.size();
        }

        public Map<String, Object> diagnostics() {
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("bank.nl2sql.candidateCount", inputCandidateCount);
            diagnostics.put("bank.nl2sql.uniqueCandidateCount", uniqueCandidateCount);
            diagnostics.put("bank.nl2sql.rejectedCandidateCount", getRejectedCandidateCount());
            diagnostics.put("bank.nl2sql.semanticScore", selected.getSemanticScore());
            return diagnostics;
        }
    }
}
