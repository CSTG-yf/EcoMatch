package com.tencent.supersonic.auth.authorization.rest;

import com.tencent.supersonic.auth.api.authentication.service.UserService;
import com.tencent.supersonic.auth.api.authorization.pojo.AuthGroup;
import com.tencent.supersonic.auth.api.authorization.service.AuthService;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerAccessTest {

    private final AuthService authService = mock(AuthService.class);
    private final UserService userService = mock(UserService.class);
    private final AuthController controller = new AuthController(authService, userService);

    @Test
    void rejectsAuthorizationGroupReadForNonAdmin() {
        when(userService.getCurrentUser(any(), any())).thenReturn(User.get(2L, "analyst"));

        assertThrows(InvalidPermissionException.class,
                () -> controller.queryAuthGroup("1", null, null, null));

        verify(authService, never()).queryAuthGroups(any(), any());
    }

    @Test
    void allowsAuthorizationGroupMutationForSuperAdmin() {
        User admin = User.get(1L, "admin");
        admin.setIsAdmin(1);
        when(userService.getCurrentUser(any(), any())).thenReturn(admin);
        AuthGroup group = new AuthGroup();

        controller.newAuthGroup(group, null, null);

        verify(authService).addOrUpdateAuthGroup(group);
    }
}
