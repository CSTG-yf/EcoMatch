package com.tencent.supersonic;

import com.tencent.supersonic.chat.server.processor.execute.BankFinalAnswerProcessor;
import com.tencent.supersonic.chat.server.processor.execute.BankResultProjectionHandler;
import com.tencent.supersonic.chat.server.processor.execute.BusinessInsightProcessor;
import com.tencent.supersonic.chat.server.processor.execute.ExecuteResultProcessor;
import com.tencent.supersonic.chat.server.util.ComponentFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecuteProcessorOrderTest {

    @Test
    void projectsBankResultsBeforeGeneratingTheFinalAnswerAndBusinessInsight() {
        List<String> processorTypes = ComponentFactory.getExecuteProcessors().stream()
                .map(ExecuteResultProcessor::getClass).map(Class::getName).toList();

        int projection = processorTypes.indexOf(BankResultProjectionHandler.class.getName());
        int finalAnswer = processorTypes.indexOf(BankFinalAnswerProcessor.class.getName());
        int businessInsight = processorTypes.indexOf(BusinessInsightProcessor.class.getName());

        assertTrue(projection >= 0 && finalAnswer > projection && businessInsight > finalAnswer,
                () -> "unexpected processor order: " + processorTypes);
    }
}
