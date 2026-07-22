package com.tencent.supersonic.headless.chat.mapper;

import com.tencent.supersonic.headless.api.pojo.DataSetSchema;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SchemaElementMatch;
import com.tencent.supersonic.headless.api.pojo.SchemaElementType;
import com.tencent.supersonic.headless.api.pojo.SchemaValueMap;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.intent.BankFinancialIntentRecognizer;
import com.tencent.supersonic.headless.chat.intent.BankIntentResult;
import com.tencent.supersonic.headless.chat.knowledge.builder.BaseWordBuilder;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Maps bank aliases to semantic elements before the generic keyword and embedding mappers. */
public class BankFinancialMapper extends BaseMapper {

    private final BankFinancialIntentRecognizer recognizer = new BankFinancialIntentRecognizer();

    @Override
    public boolean accept(ChatQueryContext context) {
        return context != null && context.getRequest() != null
                && StringUtils.isNotBlank(context.getRequest().getQueryText())
                && context.getSemanticSchema() != null
                && context.getSemanticSchema().getDataSetSchemaMap() != null;
    }

    @Override
    public void doMap(ChatQueryContext context) {
        BankIntentResult intent =
                recognizer.recognize(context.getRequest().getQueryText(), LocalDate.now());
        context.setBankIntentResult(intent);
        context.getRequest().setQueryText(intent.getNormalizedText());
        for (Map.Entry<Long, DataSetSchema> entry : context.getSemanticSchema()
                .getDataSetSchemaMap().entrySet()) {
            mapMetrics(context, entry.getKey(), entry.getValue(), intent);
            mapOrganizations(context, entry.getKey(), entry.getValue(), intent);
        }
    }

    private void mapMetrics(ChatQueryContext context, Long dataSetId, DataSetSchema schema,
            BankIntentResult intent) {
        for (BankIntentResult.MetricCandidate candidate : intent.getMetrics()) {
            SchemaElement metric =
                    schema.getMetrics().stream()
                            .filter(item -> candidate.getCode().equalsIgnoreCase(item.getBizName())
                                    || candidate.getName().equals(item.getName()))
                            .findFirst().orElse(null);
            if (metric == null) {
                continue;
            }
            SchemaElementMatch match = SchemaElementMatch.builder().element(metric)
                    .frequency(BaseWordBuilder.DEFAULT_FREQUENCY).word(metric.getName())
                    .detectWord(candidate.getMatchedText()).similarity(candidate.getConfidence())
                    .build();
            addToSchemaMap(context.getMapInfo(), dataSetId, match);
        }
    }

    private void mapOrganizations(ChatQueryContext context, Long dataSetId, DataSetSchema schema,
            BankIntentResult intent) {
        for (BankIntentResult.OrganizationSlot organization : intent.getOrganizations()) {
            for (SchemaElement valueElement : schema.getDimensionValues()) {
                SchemaValueMap valueMap =
                        findValue(valueElement.getSchemaValueMaps(), organization);
                if (valueMap == null) {
                    continue;
                }
                SchemaElement element = getSchemaElement(dataSetId, SchemaElementType.VALUE,
                        valueElement.getId(), context.getSemanticSchema());
                if (element == null) {
                    continue;
                }
                SchemaElementMatch match = SchemaElementMatch.builder().element(element)
                        .frequency(BaseWordBuilder.DEFAULT_FREQUENCY).word(valueMap.getTechName())
                        .detectWord(organization.getMatchedText())
                        .similarity(organization.getConfidence()).build();
                addToSchemaMap(context.getMapInfo(), dataSetId, match);
            }
        }
    }

    private SchemaValueMap findValue(List<SchemaValueMap> values,
            BankIntentResult.OrganizationSlot organization) {
        if (values == null) {
            return null;
        }
        return values.stream()
                .filter(value -> organization.getCode().equalsIgnoreCase(value.getTechName())
                        || organization.getName().equals(value.getBizName())
                        || containsIgnoreCase(value.getAlias(), organization.getMatchedText()))
                .findFirst().orElse(null);
    }

    private boolean containsIgnoreCase(List<String> values, String expected) {
        if (values == null || expected == null) {
            return false;
        }
        String normalized = expected.toLowerCase(Locale.ROOT);
        return values.stream().filter(StringUtils::isNotBlank)
                .anyMatch(value -> value.toLowerCase(Locale.ROOT).equals(normalized));
    }
}
