package com.tencent.supersonic.headless.core.cache;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.request.SemanticQueryReq;

public interface QueryCache {

    Object query(SemanticQueryReq semanticQueryReq, String cacheKey);

    Boolean put(String cacheKey, Object value);

    default Boolean put(SemanticQueryReq semanticQueryReq, String cacheKey, Object value) {
        return put(cacheKey, value);
    }

    String getCacheKey(SemanticQueryReq semanticQueryReq);

    default String getCacheKey(SemanticQueryReq semanticQueryReq, User user) {
        return getCacheKey(semanticQueryReq);
    }
}
