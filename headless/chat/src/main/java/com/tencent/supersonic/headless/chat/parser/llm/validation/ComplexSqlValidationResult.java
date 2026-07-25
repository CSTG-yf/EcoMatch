package com.tencent.supersonic.headless.chat.parser.llm.validation;

import com.tencent.supersonic.headless.api.pojo.SqlEvaluation;
import lombok.Builder;
import lombok.Data;

import java.util.EnumSet;

@Data
@Builder
public class ComplexSqlValidationResult {
    private SqlEvaluation evaluation;
    private double rankingScore;
    @Builder.Default
    private EnumSet<ComplexSqlFeature> features = EnumSet.noneOf(ComplexSqlFeature.class);
}
