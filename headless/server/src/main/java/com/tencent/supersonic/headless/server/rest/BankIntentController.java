package com.tencent.supersonic.headless.server.rest;

import com.tencent.supersonic.headless.chat.intent.BankIntentRequest;
import com.tencent.supersonic.headless.chat.intent.BankIntentResult;
import com.tencent.supersonic.headless.chat.intent.BankIntentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/semantic/bank/intent")
public class BankIntentController {

    private final BankIntentService intentService;

    public BankIntentController(BankIntentService intentService) {
        this.intentService = intentService;
    }

    @PostMapping("/recognize")
    public BankIntentResult recognize(@RequestBody BankIntentRequest request) {
        return intentService.recognize(request);
    }

    @PostMapping("/recognize/batch")
    public List<BankIntentResult> recognizeBatch(@RequestBody List<BankIntentRequest> requests) {
        return intentService.recognizeBatch(requests);
    }
}
