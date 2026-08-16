package com.tencent.supersonic.headless.chat.corrector;

import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.Text2SQLExemplar;
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

class LLMSqlCorrectorTest {

    @Test
    void nullChatAppConfigReturnsEarlyWithoutModelCallOrParseInfoChange() {
        LLMSqlCorrector corrector = new LLMSqlCorrector();
        ChatQueryContext context = new ChatQueryContext(new QueryNLReq());
        SemanticParseInfo parseInfo = parseInfo();

        try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class)) {
            corrector.doCorrect(context, parseInfo);
            modelProvider.verifyNoInteractions();
        }

        assertEquals("SELECT 1 FROM t", parseInfo.getSqlInfo().getCorrectedS2SQL());
    }

    @Test
    void enabledChatAppStillFollowsExistingCorrectionPath() {
        LLMSqlCorrector corrector = new LLMSqlCorrector();
        QueryNLReq request = new QueryNLReq();
        request.setQueryText("查询各机构指标");
        ChatApp chatApp = ChatApp.builder().enable(true)
                .prompt("#Question:{{question}} #Schema:{{schema}} #InputSQL:{{sql}}")
                .chatModelConfig(ChatModelConfig.builder().build()).build();
        request.setChatAppConfig(Map.of(LLMSqlCorrector.APP_KEY, chatApp));
        ChatQueryContext context = new ChatQueryContext(request);
        SemanticParseInfo parseInfo = parseInfo();
        parseInfo.getProperties().put(Text2SQLExemplar.PROPERTY_KEY,
                Text2SQLExemplar.builder().dbSchema("CREATE TABLE t").build());

        ChatLanguageModel model = mock(ChatLanguageModel.class);
        LLMSqlCorrector.SemanticSqlExtractor extractor =
                mock(LLMSqlCorrector.SemanticSqlExtractor.class);
        LLMSqlCorrector.SemanticSql corrected = new LLMSqlCorrector.SemanticSql();
        corrected.setOpinion("NEGATIVE");
        corrected.setSql("SELECT fixed FROM t");
        when(extractor.generateSemanticSql(anyString())).thenReturn(corrected);

        try (MockedStatic<ModelProvider> modelProvider = mockStatic(ModelProvider.class);
                MockedStatic<AiServices> aiServices = mockStatic(AiServices.class)) {
            modelProvider.when(() -> ModelProvider.getChatModel(any(ChatModelConfig.class)))
                    .thenReturn(model);
            aiServices.when(() -> AiServices.create(
                    LLMSqlCorrector.SemanticSqlExtractor.class, model)).thenReturn(extractor);
            corrector.doCorrect(context, parseInfo);
        }

        verify(extractor).generateSemanticSql(anyString());
        assertEquals("SELECT fixed FROM t", parseInfo.getSqlInfo().getCorrectedS2SQL());
    }

    private SemanticParseInfo parseInfo() {
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        SqlInfo sqlInfo = new SqlInfo();
        sqlInfo.setParsedS2SQL("SELECT 1 FROM t");
        sqlInfo.setCorrectedS2SQL("SELECT 1 FROM t");
        parseInfo.setSqlInfo(sqlInfo);
        return parseInfo;
    }
}
