package com.tencent.supersonic.headless.server.security.audit.model;

import java.util.List;

public record PageResult<T>(List<T> list, long pageNum, long pageSize, long total) {}
