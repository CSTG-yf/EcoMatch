package com.tencent.supersonic.headless.core.cache;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import com.tencent.supersonic.headless.api.pojo.request.SemanticQueryReq;
import com.tencent.supersonic.headless.core.gateway.QueryPerformanceMonitor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Slf4j
public class DefaultQueryCache implements QueryCache {

    private static final Pattern SQL_AGGREGATION =
            Pattern.compile("(?i)\\b(sum|avg|count|min|max)\\s*\\(");

    public Object query(SemanticQueryReq semanticQueryReq, String cacheKey) {
        CacheManager cacheManager = ContextUtils.getBean(CacheManager.class);
        if (isCache(semanticQueryReq)) {
            boolean hotMetricQuery = isHotMetricQuery(semanticQueryReq);
            Object result = hotMetricQuery ? cacheManager.getHotMetric(cacheKey)
                    : cacheManager.get(cacheKey);
            QueryPerformanceMonitor.recordCacheLookup(Objects.nonNull(result), hotMetricQuery);
            if (Objects.nonNull(result)) {
                log.debug("query from cache, key:{}", cacheKey);
            }
            return result;
        }
        return null;
    }

    public Boolean put(String cacheKey, Object value) {
        return put(null, cacheKey, value);
    }

    @Override
    public Boolean put(SemanticQueryReq semanticQueryReq, String cacheKey, Object value) {
        CacheManager cacheManager = ContextUtils.getBean(CacheManager.class);
        CacheCommonConfig cacheCommonConfig = ContextUtils.getBean(CacheCommonConfig.class);
        if (cacheCommonConfig.getCacheEnable() && Objects.nonNull(value)) {
            boolean hotMetricQuery = isHotMetricQuery(semanticQueryReq);
            CompletableFuture
                    .supplyAsync(() -> hotMetricQuery ? cacheManager.putHotMetric(cacheKey, value)
                            : cacheManager.put(cacheKey, value))
                    .exceptionally(exception -> {
                        log.warn("exception:", exception);
                        return null;
                    });
            log.debug("put to cache, key: {}", cacheKey);
            return true;
        }
        return false;
    }

    boolean isHotMetricQuery(SemanticQueryReq semanticQueryReq) {
        if (semanticQueryReq instanceof QueryStructReq) {
            QueryStructReq queryStructReq = (QueryStructReq) semanticQueryReq;
            return queryStructReq.getAggregators() != null
                    && !queryStructReq.getAggregators().isEmpty();
        }
        if (semanticQueryReq instanceof QuerySqlReq) {
            String sql = ((QuerySqlReq) semanticQueryReq).getSql();
            return StringUtils.isNotBlank(sql) && SQL_AGGREGATION.matcher(sql).find();
        }
        return false;
    }

    public String getCacheKey(SemanticQueryReq semanticQueryReq) {
        CacheManager cacheManager = ContextUtils.getBean(CacheManager.class);
        String commandMd5 = semanticQueryReq.generateCommandMd5();
        String keyByModelIds = getKeyByModelIds(semanticQueryReq.getModelIds());
        return cacheManager.generateCacheKey(keyByModelIds, commandMd5);
    }

    @Override
    public String getCacheKey(SemanticQueryReq semanticQueryReq, User user) {
        String baseKey = getCacheKey(semanticQueryReq);
        String userScope = securityScope(user);
        CacheManager cacheManager = ContextUtils.getBean(CacheManager.class);
        return cacheManager.generateCacheKey(baseKey, userScope);
    }

    String securityScope(User user) {
        if (user == null || user.getName() == null) {
            return DigestUtils.sha256Hex("anonymous");
        }
        String roles = Objects.requireNonNullElse(user.getRoles(), Collections.<String>emptySet())
                .stream().sorted().collect(Collectors.joining(","));
        String attributes = Objects
                .requireNonNullElse(user.getAttributes(), Collections.<String, String>emptyMap())
                .entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(","));
        return DigestUtils.sha256Hex(String.join("|", user.getName(),
                String.valueOf(user.isSuperAdmin()), roles, attributes));
    }

    private String getKeyByModelIds(List<Long> modelIds) {
        if (modelIds == null || modelIds.isEmpty()) {
            return "";
        }
        return String.join(",",
                modelIds.stream().map(Object::toString).collect(Collectors.toList()));
    }

    private boolean isCache(SemanticQueryReq semanticQueryReq) {
        CacheCommonConfig cacheCommonConfig = ContextUtils.getBean(CacheCommonConfig.class);
        if (!cacheCommonConfig.getCacheEnable()) {
            return false;
        }
        if (semanticQueryReq.getCacheInfo() != null) {
            return semanticQueryReq.getCacheInfo().getCache();
        }
        return false;
    }
}
