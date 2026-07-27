package com.tencent.supersonic.auth.authentication.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tencent.supersonic.auth.api.authentication.config.AuthenticationConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenServiceTest {

    private static final String TEST_SECRET =
            "WIaO9YRRVt+7QtpPvyWsARFngnEcbaKBk783uGFwMrbJBaochsqCH62L4Kijcb0sZCYoSsiKGV/zPml5MnZ3uQ==";

    @Test
    void invalidTokenLogContainsOnlySafeMetadata() {
        AuthenticationConfig config = new AuthenticationConfig();
        config.setTokenAppSecret("supersonic:" + TEST_SECRET);
        TokenService tokenService = new TokenService(config);
        String rawToken = "Bearer definitely-not-a-valid-jwt-token";

        Logger logger = (Logger) LoggerFactory.getLogger(TokenService.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            assertTrue(tokenService.getClaims(rawToken, "supersonic").isEmpty());
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }

        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        assertFalse(messages.isEmpty());
        assertFalse(messages.stream().anyMatch(message -> message.contains(rawToken)));
        assertTrue(messages.stream().anyMatch(message -> message.contains("sha256=")));
        assertTrue(messages.stream()
                .anyMatch(message -> message.contains("chars=" + rawToken.length())));
    }
}
