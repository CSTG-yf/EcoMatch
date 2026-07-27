package com.tencent.supersonic.headless.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.server.utils.ModelConfigHelper;
import dev.langchain4j.provider.ModelProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class ChatModelControllerSecurityTest {

    private final ChatModelController controller = new ChatModelController();
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final ChatModelConfig config =
            ChatModelConfig.builder().baseUrl("https://model.example").build();

    @Test
    void connectionTestRequiresSuperAdministrator() {
        try (MockedStatic<UserHolder> userHolder = mockStatic(UserHolder.class);
                MockedStatic<ModelConfigHelper> helper = mockStatic(ModelConfigHelper.class)) {
            userHolder.when(() -> UserHolder.findUser(request, response))
                    .thenReturn(User.get(2L, "alice"));

            assertThrows(InvalidPermissionException.class,
                    () -> controller.testConnection(config, request, response));
            helper.verifyNoInteractions();
        }
    }

    @Test
    void superAdministratorCanRunConnectionTest() {
        try (MockedStatic<UserHolder> userHolder = mockStatic(UserHolder.class);
                MockedStatic<ModelConfigHelper> helper = mockStatic(ModelConfigHelper.class)) {
            userHolder.when(() -> UserHolder.findUser(request, response))
                    .thenReturn(User.getDefaultUser());
            helper.when(() -> ModelConfigHelper.testConnection(config)).thenReturn(true);

            assertTrue(controller.testConnection(config, request, response));
        }
    }

    @Test
    void connectionFailureDoesNotExposeProviderMessage() {
        try (MockedStatic<ModelProvider> provider = mockStatic(ModelProvider.class)) {
            provider.when(() -> ModelProvider.getChatModel(config))
                    .thenThrow(new RuntimeException("api-key=secret internal-host=10.0.0.8"));

            InvalidArgumentException error = assertThrows(InvalidArgumentException.class,
                    () -> ModelConfigHelper.testConnection(config));

            assertEquals("Chat model connection failed", error.getMessage());
        }
    }
}
