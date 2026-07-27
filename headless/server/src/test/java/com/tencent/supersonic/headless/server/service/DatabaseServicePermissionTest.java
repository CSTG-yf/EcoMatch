package com.tencent.supersonic.headless.server.service;

import com.alibaba.fastjson.JSONObject;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.request.DatabaseReq;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.core.pojo.ConnectInfo;
import com.tencent.supersonic.headless.server.persistence.dataobject.DatabaseDO;
import com.tencent.supersonic.headless.server.service.impl.DatabaseServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

class DatabaseServicePermissionTest {

    @Test
    void viewerCanUseDatabaseMetadataWithoutReceivingPassword() {
        DatabaseServiceImpl service = spy(new DatabaseServiceImpl());
        doReturn(database()).when(service).getById(1L);

        DatabaseResp result = service.getDatabase(1L, User.get(2L, "alice"));

        assertEquals("jdbc:h2:mem:bank", result.getUrl());
        assertNull(result.getPassword());
    }

    @Test
    void databaseAdministratorCanReceiveStoredCredential() {
        DatabaseServiceImpl service = spy(new DatabaseServiceImpl());
        doReturn(database()).when(service).getById(1L);

        DatabaseResp result = service.getDatabase(1L, User.get(3L, "db-admin"));

        assertEquals("encrypted-password", result.getPassword());
    }

    @Test
    void nonSuperAdministratorCannotTestOrMutateDatabaseConnections() {
        DatabaseServiceImpl service = spy(new DatabaseServiceImpl());
        User user = User.get(2L, "alice");

        assertThrows(InvalidPermissionException.class,
                () -> service.testConnect(new DatabaseReq(), user));
        assertThrows(InvalidPermissionException.class,
                () -> service.createOrUpdateDatabase(new DatabaseReq(), user));
    }

    private DatabaseDO database() {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setUrl("jdbc:h2:mem:bank");
        connectInfo.setUserName("bank-user");
        connectInfo.setPassword("encrypted-password");
        connectInfo.setDatabase("bank");
        DatabaseDO database = new DatabaseDO();
        database.setId(1L);
        database.setName("bank");
        database.setType("h2");
        database.setCreatedBy("owner");
        database.setAdmin("db-admin");
        database.setViewer("alice");
        database.setIsOpen(0);
        database.setConfig(JSONObject.toJSONString(connectInfo));
        return database;
    }
}
