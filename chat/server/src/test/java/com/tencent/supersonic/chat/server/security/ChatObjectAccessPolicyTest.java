package com.tencent.supersonic.chat.server.security;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatObjectAccessPolicyTest {

    private final ChatObjectAccessPolicy policy = new ChatObjectAccessPolicy();

    @Test
    void allowsOwnerAndSuperAdmin() {
        assertDoesNotThrow(() -> policy.checkQueryAccess(1L, "alice", User.get(2L, "alice")));
        assertDoesNotThrow(
                () -> policy.checkQueryAccess(1L, "alice", User.getDefaultUser()));
    }

    @Test
    void rejectsOtherUsersAndMissingIdentity() {
        assertThrows(InvalidPermissionException.class,
                () -> policy.checkQueryAccess(1L, "alice", User.get(3L, "bob")));
        assertThrows(InvalidPermissionException.class,
                () -> policy.checkQueryAccess(1L, "alice", null));
    }
}
