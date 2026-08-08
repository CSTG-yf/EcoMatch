package com.tencent.supersonic.headless.chat.query.llm.s2sql;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.chat.intent.BankFinancialIntentRecognizer;
import com.tencent.supersonic.headless.chat.intent.BankIntentResult;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Immutable mapper and schema evidence supplied to the BankQueryPlan validator. */
@Value
public class SemanticIntentHints {

    public static final int DEFAULT_MAX_LIMIT = 1000;

    BankIntentType expectedIntent;
    Set<String> allowedMetrics;
    Set<String> allowedDimensions;
    Set<String> requiredMetrics;
    Set<String> requiredOrganizationCodes;
    List<DerivedMetricSpec> requiredDerivedMetrics;
    LocalDate requiredStartDate;
    LocalDate requiredEndDate;
    List<RequiredFilter> requiredFilters;
    Integer requiredLimit;
    int maxLimit;

    @Builder
    public SemanticIntentHints(BankIntentType expectedIntent, Set<String> allowedMetrics,
            Set<String> allowedDimensions, Set<String> requiredMetrics,
            Set<String> requiredOrganizationCodes, List<DerivedMetricSpec> requiredDerivedMetrics,
            LocalDate requiredStartDate, LocalDate requiredEndDate,
            List<RequiredFilter> requiredFilters, Integer requiredLimit, int maxLimit) {
        this.expectedIntent = expectedIntent;
        this.allowedMetrics = immutableSet(allowedMetrics);
        this.allowedDimensions = immutableSet(allowedDimensions);
        this.requiredMetrics = immutableSet(requiredMetrics);
        this.requiredOrganizationCodes = immutableSet(requiredOrganizationCodes);
        this.requiredDerivedMetrics = requiredDerivedMetrics == null ? Collections.emptyList()
                : List.copyOf(requiredDerivedMetrics);
        this.requiredStartDate = requiredStartDate;
        this.requiredEndDate = requiredEndDate;
        this.requiredFilters =
                requiredFilters == null ? Collections.emptyList() : List.copyOf(requiredFilters);
        this.requiredLimit = requiredLimit;
        this.maxLimit = maxLimit > 0 ? maxLimit : DEFAULT_MAX_LIMIT;
    }

    public static SemanticIntentHints from(BankIntentResult intent, LLMReq.LLMSchema schema) {
        Set<String> requiredMetrics =
                intent == null ? Collections.emptySet()
                        : immutableSet(intent.getMetrics().stream()
                                .map(BankIntentResult.MetricCandidate::getCode)
                                .map(code -> canonicalMetricIdentifier(code, schema)));
        Set<String> requiredOrganizations = intent == null ? Collections.emptySet()
                : immutableSet(intent.getOrganizations().stream()
                        .map(BankIntentResult.OrganizationSlot::getCode));
        BankIntentResult.TimeSlot time = intent == null ? null : intent.getTime();
        return builder()
                .expectedIntent(intent == null ? BankIntentType.UNKNOWN : intent.getIntent())
                .allowedMetrics(schemaElementNames(schema == null ? null : schema.getMetrics()))
                .allowedDimensions(
                        schemaElementNames(schema == null ? null : schema.getDimensions(),
                                schema == null ? null : schema.getPartitionTime()))
                .requiredMetrics(requiredMetrics).requiredOrganizationCodes(requiredOrganizations)
                .requiredDerivedMetrics(requiredDerivedMetrics(intent, schema))
                .requiredStartDate(time == null ? null : time.getStartDate())
                .requiredEndDate(time == null ? null : time.getEndDate())
                .requiredFilters(requiredFilters(intent)).requiredLimit(requiredLimit(intent))
                .build();
    }

    /**
     * Derived metric specifications are carried verbatim from the recognizer in stable insertion
     * order; only the base operands are resolved to their schema canonical identifiers so the
     * validator can match them against the plan deterministically.
     */
    private static List<DerivedMetricSpec> requiredDerivedMetrics(BankIntentResult intent,
            LLMReq.LLMSchema schema) {
        if (intent == null || intent.getDerivedMetrics() == null) {
            return Collections.emptyList();
        }
        return intent.getDerivedMetrics().stream().filter(Objects::nonNull)
                .map(candidate -> new DerivedMetricSpec(candidate.getCode(),
                        canonicalMetricIdentifier(candidate.getNumerator(), schema),
                        canonicalMetricIdentifier(candidate.getDenominator(), schema),
                        candidate.getName()))
                .collect(Collectors.toList());
    }

    private static String canonicalMetricIdentifier(String code, LLMReq.LLMSchema schema) {
        if (code == null || schema == null || schema.getMetrics() == null) {
            return code;
        }
        return schema.getMetrics().stream().filter(Objects::nonNull).map(SchemaElement::getBizName)
                .filter(Objects::nonNull).filter(identifier -> identifier.equalsIgnoreCase(code))
                .findFirst().orElse(code);
    }

    /**
     * Build evidence for the LLM phase even when a selected rule parse has already populated the
     * mapper result. In that path the normal mapper stage is intentionally skipped, so the bank
     * recognizer must be invoked here to preserve immutable metric, organization, and time hints.
     */
    public static SemanticIntentHints fromQuery(String queryText, BankIntentResult mappedIntent,
            LLMReq.LLMSchema schema, LocalDate referenceDate) {
        BankIntentResult recognizedIntent =
                new BankFinancialIntentRecognizer().recognize(queryText, referenceDate);
        BankIntentResult intent = hasCompleteQueryEvidence(recognizedIntent) ? recognizedIntent
                : mappedIntent == null ? recognizedIntent : mappedIntent;
        return from(intent, schema);
    }

    /**
     * The mapper can already contain a partial rule parse. For a fully specified bank question,
     * however, the query-text recognizer is the authoritative source for immutable metric, date,
     * organization and threshold evidence. Falling back preserves mapper behaviour for questions
     * the recognizer cannot resolve safely.
     */
    private static boolean hasCompleteQueryEvidence(BankIntentResult intent) {
        return intent != null && intent.getIntent() != null
                && intent.getIntent() != BankIntentType.UNKNOWN && intent.getMetrics() != null
                && !intent.getMetrics().isEmpty() && intent.getTime() != null
                && intent.getTime().getStartDate() != null && intent.getTime().getEndDate() != null;
    }

    private static Set<String> schemaElementNames(Collection<SchemaElement> elements) {
        return schemaElementNames(elements, null);
    }

    private static Set<String> schemaElementNames(Collection<SchemaElement> elements,
            SchemaElement additionalElement) {
        Stream<SchemaElement> source = elements == null ? Stream.empty() : elements.stream();
        if (additionalElement != null) {
            source = Stream.concat(source, Stream.of(additionalElement));
        }
        return immutableSet(source.filter(Objects::nonNull)
                .flatMap(element -> Stream.of(element.getBizName(), element.getName())));
    }

    private static Set<String> immutableSet(Stream<String> values) {
        Set<String> copy = values.filter(Objects::nonNull).filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableSet(copy);
    }

    private static Set<String> immutableSet(Collection<String> values) {
        return values == null ? Collections.emptySet() : immutableSet(values.stream());
    }

    private static List<RequiredFilter> requiredFilters(BankIntentResult intent) {
        if (intent == null || intent.getFilters() == null) {
            return Collections.emptyList();
        }
        return intent.getFilters().stream()
                // A growth ranking is a CHANGE calculation whose answer may be narrated as TopN.
                // The released evaluation contract needs the complete endpoint-change evidence, so
                // a
                // presentational rank filter must not turn it into a ranking execution contract.
                .filter(filter -> intent.getIntent() != BankIntentType.CHANGE
                        || !("rank".equals(filter.getField())
                                || "rank_from_bottom".equals(filter.getField())))
                .map(filter -> new RequiredFilter(filter.getField(), filter.getOperator(),
                        filter.getValue()))
                .collect(Collectors.toList());
    }

    private static Integer requiredLimit(BankIntentResult intent) {
        if (intent == null || intent.getFilters() == null
                || intent.getIntent() == BankIntentType.CHANGE) {
            return null;
        }
        return intent.getFilters().stream()
                .filter(filter -> "rank".equals(filter.getField())
                        || "rank_from_bottom".equals(filter.getField()))
                .map(BankIntentResult.FilterSlot::getValue)
                .map(SemanticIntentHints::positiveInteger).filter(Objects::nonNull).findFirst()
                .orElse(null);
    }

    private static Integer positiveInteger(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public record RequiredFilter(String field, String operator, String value) {}

    /**
     * Immutable derived metric specification carried from intent recognition into plan validation.
     * Operands are schema canonical identifiers; order matches the recognizer output.
     */
    public record DerivedMetricSpec(String code, String numerator, String denominator,
            String name) {}
}
