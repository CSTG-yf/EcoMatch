package com.tencent.supersonic.chat.server.security;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;

import java.util.Objects;

/** Enforces ownership for persisted chat queries and their derived results. */
public class ChatObjectAccessPolicy {

    public void checkQueryAccess(Long queryId, String owner, User user) {
        if (user == null || user.getName() == null) {
            throw new InvalidPermissionException("User identity is required");
        }
        if (!user.isSuperAdmin() && !Objects.equals(owner, user.getName())) {
            throw new InvalidPermissionException(
                    String.format("No permission to access query %s", queryId));
        }
    }
}
