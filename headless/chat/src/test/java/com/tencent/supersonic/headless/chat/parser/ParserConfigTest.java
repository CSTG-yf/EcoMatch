package com.tencent.supersonic.headless.chat.parser;

import com.tencent.supersonic.common.pojo.Parameter;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

class ParserConfigTest {

    @Test
    void exposesSqlGenerationStrategyAsSystemParameter() {
        ParserConfig parserConfig = new ParserConfig();

        boolean exposed = parserConfig.getSysParameters().stream().map(Parameter::getName)
                .anyMatch(ParserConfig.PARSER_STRATEGY_TYPE.getName()::equals);

        Assert.assertTrue(exposed);
    }
}
