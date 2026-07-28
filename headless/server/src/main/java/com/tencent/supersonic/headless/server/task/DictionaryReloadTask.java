package com.tencent.supersonic.headless.server.task;

import com.tencent.supersonic.common.util.SensitiveLogUtils;
import com.tencent.supersonic.headless.server.service.impl.DictWordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(2)
public class DictionaryReloadTask implements CommandLineRunner {

    @Autowired
    private DictWordService dictWordService;

    @Override
    public void run(String... args) {
        updateKnowledgeDimValue();
    }

    public void updateKnowledgeDimValue() {
        try {
            log.debug("ApplicationStartedInit start");
            dictWordService.loadDictWord();
            log.debug("ApplicationStartedInit end");
        } catch (Exception e) {
            log.error("Failed to initialize dictionary: type={}, error=[{}]",
                    e.getClass().getSimpleName(), SensitiveLogUtils.summarize(e));
        }
    }

    /** * reload knowledge task */
    @Scheduled(cron = "${reload.knowledge.corn:0 0/1 * * * ?}")
    public void reloadKnowledge() {
        log.debug("reloadKnowledge start");
        try {
            dictWordService.reloadDictWord();
        } catch (Exception e) {
            log.error("Failed to reload dictionary: type={}, error=[{}]",
                    e.getClass().getSimpleName(), SensitiveLogUtils.summarize(e));
        }
        log.debug("reloadKnowledge end");
    }
}
