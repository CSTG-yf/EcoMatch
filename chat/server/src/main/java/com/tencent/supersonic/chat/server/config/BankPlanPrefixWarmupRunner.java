package com.tencent.supersonic.chat.server.config;

import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.headless.chat.parser.llm.OnePassSCSqlGenStrategy;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanGenStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Completes local bank-prefix warm-up before Spring reports the application ready. */
@Slf4j
@Component
@RequiredArgsConstructor
public class BankPlanPrefixWarmupRunner implements ApplicationRunner {

    private final AgentService agentService;
    private final BankPlanGenStrategy bankPlanGenStrategy;

    @Override
    public void run(ApplicationArguments args) {
        Map<String, ChatModelConfig> candidates = new LinkedHashMap<>();
        for (Agent agent : agentService.getAgents()) {
            ChatModelConfig config = resolveEnabledModel(agent);
            if (config != null) {
                candidates.putIfAbsent(modelKey(config), config);
            }
        }
        int warmed = 0;
        for (ChatModelConfig config : candidates.values()) {
            if (bankPlanGenStrategy.warmLocalPrefixAtStartup(config)) {
                warmed++;
            }
        }
        log.info("Bank plan local prefix startup warm-up complete: warmedModels={}", warmed);
    }

    static ChatModelConfig resolveEnabledModel(Agent agent) {
        if (agent == null || agent.getStatus() == null || agent.getStatus() != 1
                || agent.getChatAppConfig() == null) {
            return null;
        }
        ChatApp dedicated = agent.getChatAppConfig().get(BankPlanGenStrategy.APP_KEY);
        if (dedicated != null && dedicated.isEnable() && dedicated.getChatModelConfig() != null) {
            return dedicated.getChatModelConfig();
        }
        ChatApp fallback = agent.getChatAppConfig().get(OnePassSCSqlGenStrategy.APP_KEY);
        return fallback != null && fallback.isEnable() ? fallback.getChatModelConfig() : null;
    }

    private static String modelKey(ChatModelConfig config) {
        return String.valueOf(config.getProvider()) + '\n' + String.valueOf(config.getBaseUrl())
                + '\n' + String.valueOf(config.getModelName());
    }
}
