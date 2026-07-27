package com.tencent.supersonic.auth.authentication.rest;

import com.tencent.supersonic.auth.api.authentication.pojo.UserToken;
import com.tencent.supersonic.auth.api.authentication.service.UserService;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserControllerTokenAccessTest {

    private final UserService userService = mock(UserService.class);
    private final UserController controller = new UserController(userService);

    @Test
    void rejectsAnonymousTokenReadBeforeLoadingToken() {
        when(userService.getCurrentUser(any(), any())).thenReturn(null);

        assertThrows(InvalidPermissionException.class,
                () -> controller.getUserToken(9L, null, null));

        verify(userService, never()).getUserToken(9L);
    }

    @Test
    void rejectsCrossUserTokenReadAndDelete() {
        when(userService.getCurrentUser(any(), any())).thenReturn(User.get(2L, "alice"));
        when(userService.getUserToken(9L)).thenReturn(token("bob"));

        assertThrows(InvalidPermissionException.class,
                () -> controller.getUserToken(9L, null, null));
        assertThrows(InvalidPermissionException.class,
                () -> controller.deleteUserToken(9L, null, null));

        verify(userService, never()).deleteUserToken(9L);
    }

    @Test
    void allowsOwnerToReadAndDeleteToken() {
        when(userService.getCurrentUser(any(), any())).thenReturn(User.get(2L, "alice"));
        UserToken token = token("alice");
        when(userService.getUserToken(9L)).thenReturn(token);

        assertSame(token, controller.getUserToken(9L, null, null));
        controller.deleteUserToken(9L, null, null);

        verify(userService).deleteUserToken(9L);
    }

    private UserToken token(String userName) {
        UserToken token = new UserToken();
        token.setId(9);
        token.setUserName(userName);
        token.setToken("sensitive-token");
        return token;
    }
}
