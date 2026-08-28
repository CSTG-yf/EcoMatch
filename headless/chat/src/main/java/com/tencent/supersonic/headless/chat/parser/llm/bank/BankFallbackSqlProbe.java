package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;

import java.util.List;

/**
 * Publish-gate probe for the controlled free-SQL fallback (W1): trial-executes a whitelisted
 * candidate read-only against the semantic layer with a tiny row cap BEFORE the candidate is
 * published to the parse pipeline, so an execution failure becomes repairable feedback inside the
 * fallback budget instead of a terminal execute-stage error.
 *
 * <p>The execution facilities live in headless-server, so this interface is implemented there;
 * the chat-side strategy resolves the bean opportunistically through the Spring context and skips
 * the gate (legacy behavior) when no implementation is present.
 */
public interface BankFallbackSqlProbe {

    /** Candidate S2SQL could not be translated into physical SQL by the semantic compiler. */
    String ERROR_TRANSLATE_FAILED = "TRANSLATE_FAILED";

    /** Physical trial execution failed (safety policy, gateway rejection, JDBC error, ...). */
    String ERROR_EXECUTION_FAILED = "EXECUTION_FAILED";

    /** Anything else: missing dataset id, non-SELECT/WITH shape, unexpected probe failure. */
    String ERROR_OTHER = "OTHER";

    /**
     * Trial-executes the whitelisted candidate S2SQL read-only with a small row cap. Never
     * throws: any failure — including infrastructure faults of the probe itself — is reported as
     * a {@link ProbeReport} with {@code ok=false} so the caller can fail closed.
     */
    ProbeReport probe(LLMReq llmReq, String s2sql);

    /** Immutable outcome of a probe trial execution. */
    record ProbeReport(boolean ok, String errorCode, String message,
            List<String> resultColumns, int resultRowCount) {

        public static ProbeReport pass(List<String> resultColumns, int resultRowCount) {
            return new ProbeReport(true, null, null,
                    resultColumns == null ? List.of() : List.copyOf(resultColumns), resultRowCount);
        }

        public static ProbeReport fail(String errorCode, String message) {
            return new ProbeReport(false, errorCode, message, List.of(), 0);
        }
    }
}
