package com.tencent.supersonic.common.config;

import com.tencent.supersonic.common.pojo.Parameter;
import com.tencent.supersonic.common.service.SystemConfigService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public abstract class ParameterConfig {
    public static final String DEMO = "demo";
    @Autowired
    private SystemConfigService sysConfigService;

    @Autowired
    private Environment environment;

    /** @return system parameters to be set with user interface */
    protected List<Parameter> getSysParameters() {
        return Collections.EMPTY_LIST;
    }

    /**
     * Parameter value will be derived in the following order: 1. `system property` set with
     * `-D` on the command line (explicit per-process operator override, as documented on each
     * Parameter) 2. `system config` set with user interface 3. `default value` set with
     * parameter declaration
     *
     * <p>The system property must be consulted before the system config: {@link
     * SystemConfig#getParameters()} back-fills every registered parameter with its default
     * value, so a name missing from the stored config still resolves non-blank and would
     * otherwise shadow the `-D` override.
     *
     * @param parameter instance
     * @return parameter value
     */
    public String getParameterValue(Parameter parameter) {
        String paramName = parameter.getName();
        String sysProperty = System.getProperty(paramName);
        if (StringUtils.isNotBlank(sysProperty)) {
            return sysProperty;
        }
        String value = sysConfigService.getSystemConfig().getParameterByName(paramName);
        if (StringUtils.isBlank(value)) {
            if (environment.containsProperty(paramName)) {
                value = environment.getProperty(paramName);
            } else {
                value = parameter.getDefaultValue();
            }
        }

        return value;
    }

    protected static List<Parameter.Dependency> getDependency(String dependencyParameterName,
            List<String> includesValue, Map<String, String> setDefaultValue) {

        Parameter.Dependency.Show show = new Parameter.Dependency.Show();
        show.setIncludesValue(includesValue);

        Parameter.Dependency dependency = new Parameter.Dependency();
        dependency.setName(dependencyParameterName);
        dependency.setShow(show);
        dependency.setSetDefaultValue(setDefaultValue);
        List<Parameter.Dependency> dependencies = new ArrayList<>();
        dependencies.add(dependency);
        return dependencies;
    }
}
