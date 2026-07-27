package com.tencent.supersonic.headless.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.server.service.DatabaseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DatabaseControllerAccessTest {

    private final DatabaseService databaseService = mock(DatabaseService.class);
    private final DatabaseController controller = new DatabaseController(databaseService);

    @Test
    void deniesMetadataReadBeforeCallingDatabaseAdaptor() throws Exception {
        doThrow(new InvalidPermissionException("denied")).when(databaseService).getDatabase(any(),
                any());

        try (MockedStatic<UserHolder> userHolder = mockStatic(UserHolder.class)) {
            userHolder.when(() -> UserHolder.findUser((HttpServletRequest) null,
                    (HttpServletResponse) null)).thenReturn(User.get(2L, "analyst"));
            assertThrows(InvalidPermissionException.class,
                    () -> controller.getTables(7L, null, "bank", null, null));
        }

        verify(databaseService, never()).getTables(any(), any(), any());
    }

    @Test
    void rejectsInjectedMetadataIdentifierBeforeCallingDatabaseAdaptor() throws Exception {
        try (MockedStatic<UserHolder> userHolder = mockStatic(UserHolder.class)) {
            userHolder.when(() -> UserHolder.findUser((HttpServletRequest) null,
                    (HttpServletResponse) null)).thenReturn(User.get(2L, "analyst"));
            assertThrows(InvalidArgumentException.class,
                    () -> controller.getTables(7L, null, "bank; DROP TABLE customer", null, null));
        }

        verify(databaseService, never()).getTables(any(), any(), any());
    }
}
