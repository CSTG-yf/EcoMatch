package com.tencent.supersonic.headless.chat.corrector;

import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.SqlInfo;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.provider.ModelProvider;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LLMPhysicalSqlCorrectorTest {

    @Test
    void nullChatAppConfigReturnsEarlyWithoutModelCallOrParseInfoChange() {
        LLMPhysicalSqlCorrector corrector = new LLMPhysicalSqlCorrector();
        ChatQueryContext context = new ChatQueryContext(new QueryNLReq());
        SemanticParseInfo parseInfo = parseInfo();

        try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
            corrector.doCorrect(context, parseInfo);
            modelProvider.verifyNoInteractions();
        }

        assertEquals("SELECT 1 FROM t", parseInfo.getSqlInfo().getCorrectedS2SQL());
        assertEquals("SELECT 1 FROM t", parseInfo.getSqlInfo().getQuerySQL());
        assertEquals("SELECT 1 FROM t", parseInfo.getSqlInfo().getCorrectedQuerySQL());
    }

    @Test
    void enabledChatAppStillFollowsExistingCorrectionPath() {
        LLMPhysicalSqlCorrector corrector = new LLMPhysicalSqlCorrector();
        QueryNLReq request = new QueryNLReq();
        request.setQueryText("查询各机构指标");
        ChatApp chatApp = ChatApp.builder().enable(true)
                .prompt("#Question: {{question}}\n#OriginalSQL: {{sql}}")
                .chatModelConfig(ChatModelConfig.builder().build()).build();
        request.setChatAppConfig(Map.of(LLMPhysicalSqlCorrector.APP_KEY, chatApp));
        ChatQueryContext context = new ChatQueryContext(request);
        SemanticParseInfo parseInfo = parseInfo();

        ChatLanguageModel model = mock(ChatLanguageModel.class);
        LLMPhysicalSqlCorrector.PhysicalSqlExtractor extractor =
                mock(LLMPhysicalSqlCorrector.PhysicalSqlExtractor.class);
        LLMPhysicalSqlCorrector.PhysicalSql optimized = new LLMPhysicalSqlCorrector.PhysicalSql();
        optimized.setOpinion("NEGATIVE");
        optimized.setSql("SELECT optimized FROM t");
        when(extractor.generatePhysicalSql(anyString())).thenReturn(optimized);

        try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
                MockedStatic<AiServices> aiServices = mockStatic(AiServices.class)) {
            modelProvider.when(() -> ModelProvider.getChatModel(any(ChatModelConfig.class)))
                    .thenReturn(model);
            aiServices.when(() -> AiServices.create(
                    LLMPhysicalSqlCorrector.PhysicalSqlExtractor.class, model)).thenReturn(extractor);
            corrector.doCorrect(context, parseInfo);
        }

        verify(extractor).generatePhysicalSql(anyString());
        assertEquals("SELECT optimized FROM t",
                parseInfo.getSqlInfo().getCorrectedQuerySQL());
    }

    private SemanticParseInfo parseInfo() {
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        SqlInfo sqlInfo = new SqlInfo();
        sqlInfo.setParsedS2SQL("SELECT 1 FROM t");
        sqlInfo.setCorrectedS2SQL("SELECT 1 FROM t");
        sqlInfo.setQuerySQL("SELECT 1 FROM t");
        sqlInfo.setCorrectedQuerySQL("SELECT 1 FROM t");
        parseInfo.setSqlInfo(sqlInfo);
        return parseInfo;
    }
}
