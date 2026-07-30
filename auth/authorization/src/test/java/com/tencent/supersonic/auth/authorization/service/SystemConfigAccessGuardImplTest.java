package com.tencent.supersonic.auth.authorization.service;

import com.tencent.supersonic.auth.api.authentication.service.UserService;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SystemConfigAccessGuardImplTest {

    private final AtomicReference<User> currentUser = new AtomicReference<>();
    private final UserService userService = (UserService) Proxy.newProxyInstance(
            UserService.class.getClassLoader(), new Class<?>[] {UserService.class},
            (proxy, method, args) -> method.getName().equals("getCurrentUser")
                    ? currentUser.get()
                    : null);
    private final SystemConfigAccessGuardImpl guard = new SystemConfigAccessGuardImpl(userService);

    @Test
    void rejectsMissingAndRegularUsers() {
        assertThrows(InvalidPermissionException.class,
                () -> guard.requireAdministrator(null, null));

        currentUser.set(User.get(2L, "analyst"));

        assertThrows(InvalidPermissionException.class,
                () -> guard.requireAdministrator(null, null));
    }

    @Test
    void allowsSuperAdministrator() {
        User administrator = User.get(1L, "admin");
        administrator.setIsAdmin(1);
        currentUser.set(administrator);

        assertDoesNotThrow(() -> guard.requireAdministrator(null, null));
    }
}
