package com.tencent.supersonic.headless.server.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MapperRegistrationTest {

    @Test
    void infrastructureMappersAreDiscoverableByTheLauncher() {
        assertMapper(DashboardMapper.class);
        assertMapper(ExportTaskMapper.class);
        assertMapper(ShareMapper.class);
    }

    private void assertMapper(Class<?> mapperType) {
        assertTrue(mapperType.isAnnotationPresent(Mapper.class),
                () -> mapperType.getSimpleName() + " must be annotated with @Mapper");
    }
}
