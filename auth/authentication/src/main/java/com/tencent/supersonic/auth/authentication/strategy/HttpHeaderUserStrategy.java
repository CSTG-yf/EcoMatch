package com.tencent.supersonic.auth.authentication.strategy;

import com.tencent.supersonic.auth.api.authentication.constant.UserConstants;
import com.tencent.supersonic.auth.api.authentication.service.UserStrategy;
import com.tencent.supersonic.auth.authentication.utils.TokenService;
import com.tencent.supersonic.common.pojo.User;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class HttpHeaderUserStrategy implements UserStrategy {

    public static final String STRATEGY_NAME = "http";
    private final TokenService tokenService;

    public HttpHeaderUserStrategy(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public String getStrategyName() {
        return STRATEGY_NAME;
    }

    @Override
    public boolean accept(boolean isEnableAuthentication) {
        return isEnableAuthentication;
    }

    @Override
    public User findUser(HttpServletRequest request, HttpServletResponse response) {
        return getUser(request);
    }

    @Override
    public User findUser(String token, String appKey) {
        return getUser(token, appKey);
    }

    public User getUser(HttpServletRequest request) {
        final Optional<Claims> claimsOptional = tokenService.getClaims(request);
        return claimsOptional.map(this::getUser).orElse(User.getVisitUser());
    }

    public User getUser(String token, String appKey) {
        final Optional<Claims> claimsOptional = tokenService.getClaims(token, appKey);
        return claimsOptional.map(this::getUser).orElse(User.getVisitUser());
    }

    private User getUser(Claims claims) {
        Long userId =
                Long.parseLong(claims.getOrDefault(UserConstants.TOKEN_USER_ID, 0).toString());
        String userName = String.valueOf(claims.get(UserConstants.TOKEN_USER_NAME));
        String email = String.valueOf(claims.get(UserConstants.TOKEN_USER_EMAIL));
        String displayName = String.valueOf(claims.get(UserConstants.TOKEN_USER_DISPLAY_NAME));
        Integer isAdmin = claims.get(UserConstants.TOKEN_IS_ADMIN) == null ? 0
                : Integer.parseInt(claims.get(UserConstants.TOKEN_IS_ADMIN).toString());
        User user = User.get(userId, userName, displayName, email, isAdmin);
        user.setRoles(readRoles(claims.get(UserConstants.TOKEN_USER_ROLES)));
        user.setAttributes(readAttributes(claims.get(UserConstants.TOKEN_USER_ATTRIBUTES)));
        return user;
    }

    private Set<String> readRoles(Object claim) {
        if (!(claim instanceof Collection<?> values)) {
            return Collections.emptySet();
        }
        return values.stream().filter(value -> value != null).map(String::valueOf)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<String, String> readAttributes(Object claim) {
        if (!(claim instanceof Map<?, ?> values)) {
            return Collections.emptyMap();
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && value != null && !String.valueOf(key).isBlank()) {
                attributes.put(String.valueOf(key), String.valueOf(value));
            }
        });
        return attributes;
    }
}
