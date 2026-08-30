package com.tencent.supersonic.common.config;

import com.tencent.supersonic.common.pojo.Parameter;
import com.tencent.supersonic.common.service.SystemConfigService;
import com.tencent.supersonic.common.util.ContextUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks the resolution order of {@link ParameterConfig#getParameterValue}: an explicit
 * {@code -D} system property must override the stored system config, because
 * {@link SystemConfig#getParameters()} back-fills every registered parameter with its default
 * value and would otherwise shadow the {@code -D} override.
 */
class ParameterConfigParameterValueTest {

    private static final String PARAM_NAME = "s2.test.parameter.enable";

    private final SystemConfigService sysConfigService = mock(SystemConfigService.class);
    private final ApplicationContext applicationContext = mock(ApplicationContext.class);
    private final ParameterConfig config = new ParameterConfig() {
        @Override
        protected List<Parameter> getSysParameters() {
            return List.of(parameter());
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        inject(ParameterConfig.class, config, "sysConfigService", sysConfigService);
        Environment environment = mock(Environment.class);
        when(environment.containsProperty(PARAM_NAME)).thenReturn(false);
        inject(ParameterConfig.class, config, "environment", environment);
        inject(ContextUtils.class, null, "context", applicationContext);
        when(applicationContext.getBeansOfType(ParameterConfig.class))
                .thenReturn(Map.of("testConfig", config));
        System.clearProperty(PARAM_NAME);
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty(PARAM_NAME);
        inject(ContextUtils.class, null, "context", null);
    }

    @Test
    void systemPropertyOverridesStoredConfigValue() {
        System.setProperty(PARAM_NAME, "true");
        when(sysConfigService.getSystemConfig()).thenReturn(configWithStoredValue());

        assertEquals("true", config.getParameterValue(parameter()));
    }

    @Test
    void systemPropertyOverridesConfigDefaultBackFill() {
        // Reproduces the production shadowing: the stored config has no such key, so
        // getParameterByName resolves the registered default "false"; the explicit -D
        // must win regardless.
        System.setProperty(PARAM_NAME, "true");
        when(sysConfigService.getSystemConfig()).thenReturn(new SystemConfig());

        assertEquals("true", config.getParameterValue(parameter()));
    }

    @Test
    void storedConfigValueWinsWithoutSystemProperty() {
        when(sysConfigService.getSystemConfig()).thenReturn(configWithStoredValue());

        assertEquals("adminSet", config.getParameterValue(parameter()));
    }

    @Test
    void declaredDefaultAppliesWhenConfigAndSystemPropertyAbsent() {
        when(sysConfigService.getSystemConfig()).thenReturn(new SystemConfig());

        assertEquals("false", config.getParameterValue(parameter()));
    }

    private Parameter parameter() {
        return new Parameter(PARAM_NAME, "false", "test", "test", "bool", "test");
    }

    private SystemConfig configWithStoredValue() {
        Parameter adminSet = new Parameter(PARAM_NAME, "adminSet", "test", "test", "bool",
                "test");
        SystemConfig systemConfig = new SystemConfig();
        systemConfig.setParameters(Collections.singletonList(adminSet));
        return systemConfig;
    }

    private static void inject(Class<?> targetClass, Object target, String fieldName,
            Object value) throws Exception {
        Field field = targetClass.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
