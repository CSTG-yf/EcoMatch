package com.tencent.supersonic.chat.server.config;

import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.headless.chat.parser.llm.bank.BankPlanGenStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Restores the local bank-plan prefix as soon as a new chat is persisted. */
@Slf4j
@Component
@RequiredArgsConstructor
public class BankPlanSessionWarmupCoordinator {

    private final BankPlanGenStrategy bankPlanGenStrategy;
    private final ConcurrentMap<String, CompletableFuture<Boolean>> inFlight =
            new ConcurrentHashMap<>();

    public void warmAsync(long chatId, Agent agent) {
        ChatModelConfig config = BankPlanPrefixWarmupRunner.resolveEnabledModel(agent);
        if (config == null) {
            return;
        }
        String key = modelKey(config);
        CompletableFuture<Boolean> created = new CompletableFuture<>();
        CompletableFuture<Boolean> active = inFlight.putIfAbsent(key, created);
        if (active == null) {
            active = created;
            CompletableFuture.runAsync(() -> {
                try {
                    created.complete(bankPlanGenStrategy.refreshLocalPrefixForSession(config));
                } catch (RuntimeException error) {
                    created.completeExceptionally(error);
                } finally {
                    inFlight.remove(key, created);
                }
            });
        }
        active.whenComplete((verified, error) -> {
            if (error != null) {
                log.warn("Bank plan new-chat prefix warm-up failed: chatId={}, type={}", chatId,
                        error.getClass().getSimpleName());
                return;
            }
            log.info("Bank plan new-chat prefix warm-up complete: chatId={}, verified={}", chatId,
                    verified);
        });
    }

    private static String modelKey(ChatModelConfig config) {
        return String.valueOf(config.getProvider()) + '\n' + String.valueOf(config.getBaseUrl())
                + '\n' + String.valueOf(config.getModelName());
    }
}
