package com.tencent.supersonic.headless.chat.parser.llm.bank;

import org.apache.commons.codec.digest.DigestUtils;

/** Produces a stable, data-free identity for a compiled semantic query. */
public final class BankPlanFingerprint {

    private BankPlanFingerprint() {}

    public static String of(BankQueryPlanCompiler.CompiledQuery compiled) {
        String source = compiled.getRoute() + "|" + compiled.getOutputColumns() + "|"
                + (compiled.getStructReq() == null ? compiled.getS2sql()
                        : compiled.getStructReq().toCustomizedString());
        return DigestUtils.sha256Hex(source);
    }
}
