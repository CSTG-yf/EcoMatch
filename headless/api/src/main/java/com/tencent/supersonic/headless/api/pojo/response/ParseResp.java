package com.tencent.supersonic.headless.api.pojo.response;

import com.google.common.collect.Lists;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.enums.SqlErrorType;
import lombok.Data;

import java.io.Serializable;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Data
public class ParseResp implements Serializable {
    private final String queryText;
    private ParseState state = ParseState.PENDING;
    private String errorMsg;
    private List<SemanticParseInfo> selectedParses = Lists.newArrayList();
    private BankIntentResult bankIntentResult;
    private BankRoutingAttemptTelemetry bankRoutingAttemptTelemetry;
    private ParseTimeCostResp parseTimeCost = new ParseTimeCostResp();

    public enum ParseState {
        COMPLETED, PENDING, FAILED
    }

    @Data
    public static class BankRoutingAttemptTelemetry implements Serializable {
        private final boolean bankConstrainedPlanEnabled;
        private final boolean bankDatasetQualified;
        private final BankRoutingSqlGenType selectedSqlGenType;
        private final boolean llmCandidateCreated;
        private final BankCandidateRejectionState candidateRejectionState;
        private final SqlErrorType candidateValidationErrorType;
        private final BankCandidateCompilerReason candidateCompilerReason;

        public BankRoutingAttemptTelemetry(boolean bankConstrainedPlanEnabled,
                boolean bankDatasetQualified, BankRoutingSqlGenType selectedSqlGenType,
                boolean llmCandidateCreated) {
            this(bankConstrainedPlanEnabled, bankDatasetQualified, selectedSqlGenType,
                    llmCandidateCreated, null, null);
        }

        public BankRoutingAttemptTelemetry(boolean bankConstrainedPlanEnabled,
                boolean bankDatasetQualified, BankRoutingSqlGenType selectedSqlGenType,
                boolean llmCandidateCreated, BankCandidateRejectionState candidateRejectionState,
                SqlErrorType candidateValidationErrorType) {
            this(bankConstrainedPlanEnabled, bankDatasetQualified, selectedSqlGenType,
                    llmCandidateCreated, candidateRejectionState, candidateValidationErrorType,
                    null);
        }

        public BankRoutingAttemptTelemetry(boolean bankConstrainedPlanEnabled,
                boolean bankDatasetQualified, BankRoutingSqlGenType selectedSqlGenType,
                boolean llmCandidateCreated, BankCandidateRejectionState candidateRejectionState,
                SqlErrorType candidateValidationErrorType,
                BankCandidateCompilerReason candidateCompilerReason) {
            this.bankConstrainedPlanEnabled = bankConstrainedPlanEnabled;
            this.bankDatasetQualified = bankDatasetQualified;
            this.selectedSqlGenType = selectedSqlGenType;
            this.llmCandidateCreated = llmCandidateCreated;
            this.candidateRejectionState = llmCandidateCreated ? null : candidateRejectionState;
            this.candidateValidationErrorType = !llmCandidateCreated
                    && candidateRejectionState == BankCandidateRejectionState.VALIDATION_REJECTED
                    ? candidateValidationErrorType : null;
            this.candidateCompilerReason = !llmCandidateCreated
                    && candidateRejectionState == BankCandidateRejectionState.COMPILER_EXCEPTION
                    ? candidateCompilerReason : null;
        }
    }

    public enum BankRoutingSqlGenType {
        ONE_PASS_SELF_CONSISTENCY, BANK_CONSTRAINED_PLAN, FREE_SQL
    }

    public enum BankCandidateRejectionState {
        NO_RESPONSE, PLAN_EXCEPTION, COMPILER_EXCEPTION, VALIDATION_REJECTED, NO_CANDIDATE
    }

    public enum BankCandidateCompilerReason {
        INVALID_PLAN,
        CLARIFICATION_REQUIRED,
        SCHEMA_REQUIRED,
        DATASET_REQUIRED,
        METRIC_UNAVAILABLE,
        DIMENSION_UNAVAILABLE,
        ORGANIZATION_DIMENSION_UNAVAILABLE,
        TIME_DIMENSION_UNAVAILABLE,
        OUTPUT_ORDER_MISMATCH,
        ORDER_FIELD_NOT_SELECTED,
        UNSUPPORTED_FILTER,
        UNSUPPORTED_CALCULATION,
        S2SQL_RENDER_FAILED,
        UNSUPPORTED_QUERY_SHAPE
    }

    public ParseResp(String queryText) {
        this.queryText = queryText;
        parseTimeCost.setParseStartTime(System.currentTimeMillis());
    }

    public List<SemanticParseInfo> getSelectedParses() {
        selectedParses = selectedParses.stream()
                .sorted(Comparator.comparingDouble(SemanticParseInfo::getScore).reversed())
                .collect(Collectors.toList());
        generateParseInfoId(selectedParses);
        return selectedParses;
    }

    private void generateParseInfoId(List<SemanticParseInfo> selectedParses) {
        for (int i = 0; i < selectedParses.size(); i++) {
            SemanticParseInfo parseInfo = selectedParses.get(i);
            parseInfo.setId(i + 1);
        }
    }
}
