package com.tencent.supersonic.headless.server.service;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.response.ChatSnapshotExportData;

/**
 * Resolves a chat query snapshot for export. The implementation (provided by the chat module
 * in deployments that include it) is responsible for existence, ownership and permission
 * checks, and must throw InvalidArgumentException/InvalidPermissionException on violations.
 */
public interface ChatSnapshotExportResolver {

    ChatSnapshotExportData resolve(long queryId, User user);
}
