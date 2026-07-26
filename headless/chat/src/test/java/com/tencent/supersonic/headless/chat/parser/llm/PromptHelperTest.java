package com.tencent.supersonic.headless.chat.parser.llm;

import com.tencent.supersonic.common.service.ExemplarService;
import com.tencent.supersonic.headless.chat.parser.ParserConfig;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptHelperTest {

    @Test
    void shouldReturnOneEmptyFewShotBatchWhenNoExemplarIsAvailable() {
        ParserConfig parserConfig = mock(ParserConfig.class);
        when(parserConfig.getParameterValue(ParserConfig.PARSER_EXEMPLAR_RECALL_NUMBER))
                .thenReturn("0");
        when(parserConfig.getParameterValue(ParserConfig.PARSER_FEW_SHOT_NUMBER)).thenReturn("3");
        when(parserConfig.getParameterValue(ParserConfig.PARSER_SELF_CONSISTENCY_NUMBER))
                .thenReturn("1");
        PromptHelper helper = new PromptHelper();
        ReflectionTestUtils.setField(helper, "parserConfig", parserConfig);
        ReflectionTestUtils.setField(helper, "exemplarService", mock(ExemplarService.class));
        LLMReq request = new LLMReq();
        request.setDynamicExemplars(List.of());

        List<List<com.tencent.supersonic.common.pojo.Text2SQLExemplar>> batches =
                helper.getFewShotExemplars(request);

        assertEquals(List.of(List.of()), batches);
    }
}
