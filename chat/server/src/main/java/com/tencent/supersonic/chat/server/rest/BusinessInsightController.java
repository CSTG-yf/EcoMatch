package com.tencent.supersonic.chat.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.chat.api.pojo.request.BusinessInsightReq;
import com.tencent.supersonic.chat.api.pojo.response.BusinessExplanation;
import com.tencent.supersonic.chat.api.pojo.response.ChartInsightResp;
import com.tencent.supersonic.chat.server.service.BusinessInsightService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat/insight")
public class BusinessInsightController {

    private final BusinessInsightService insightService;

    public BusinessInsightController(BusinessInsightService insightService) {
        this.insightService = insightService;
    }

    @PostMapping("/recommend")
    public ChartInsightResp recommend(@RequestBody BusinessInsightReq insightReq,
            HttpServletRequest request, HttpServletResponse response) {
        UserHolder.findUser(request, response);
        return insightService.recommend(insightReq);
    }

    @PostMapping("/explain")
    public BusinessExplanation explain(@RequestBody BusinessInsightReq insightReq,
            HttpServletRequest request, HttpServletResponse response) {
        UserHolder.findUser(request, response);
        return insightService.explain(insightReq);
    }
}
