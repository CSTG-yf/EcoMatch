package com.tencent.supersonic.headless.server.service.bank;

import com.tencent.supersonic.headless.server.pojo.bank.BankSemanticImportReport;

public class BankSemanticImportException extends RuntimeException {

    private final BankSemanticImportReport report;

    public BankSemanticImportException(String message, Throwable cause,
            BankSemanticImportReport report) {
        super(message, cause);
        this.report = report;
    }

    public BankSemanticImportReport getReport() {
        return report;
    }
}
