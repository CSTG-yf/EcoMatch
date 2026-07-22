package com.tencent.supersonic.headless.chat.intent;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class BankIntentService {

    private final BankFinancialIntentRecognizer recognizer = new BankFinancialIntentRecognizer();

    public BankIntentResult recognize(BankIntentRequest request) {
        if (request == null) {
            return recognizer.recognize(null, LocalDate.now());
        }
        return recognizer.recognize(request.getQueryText(), request.getReferenceDate());
    }

    public List<BankIntentResult> recognizeBatch(List<BankIntentRequest> requests) {
        if (requests == null) {
            return List.of();
        }
        return requests.stream().map(this::recognize).collect(Collectors.toList());
    }
}
