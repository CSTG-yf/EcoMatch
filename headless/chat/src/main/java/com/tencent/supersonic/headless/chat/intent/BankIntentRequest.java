package com.tencent.supersonic.headless.chat.intent;

import lombok.Data;

import java.time.LocalDate;

@Data
public class BankIntentRequest {

    private String queryText;
    private LocalDate referenceDate;
}
