package com.tencent.supersonic.auth.authentication.strategy;

import com.tencent.supersonic.auth.api.authentication.constant.UserConstants;
import com.tencent.supersonic.auth.api.authentication.pojo.UserWithPassword;
import com.tencent.supersonic.auth.authentication.utils.TokenService;
import com.tencent.supersonic.common.pojo.User;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpHeaderUserStrategyTest {

    @Test
    void restoresSignedRoleAndAttributeClaimsForAuthorization() {
        Claims claims = mock(Claims.class);
        when(claims.getOrDefault(UserConstants.TOKEN_USER_ID, 0)).thenReturn(9L);
        when(claims.get(UserConstants.TOKEN_USER_NAME)).thenReturn("risk-user");
        when(claims.get(UserConstants.TOKEN_USER_EMAIL)).thenReturn("risk@example.com");
        when(claims.get(UserConstants.TOKEN_USER_DISPLAY_NAME)).thenReturn("Risk User");
        when(claims.get(UserConstants.TOKEN_IS_ADMIN)).thenReturn(0);
        when(claims.get(UserConstants.TOKEN_USER_ROLES))
                .thenReturn(List.of("risk_manager", "branch_operator"));
        when(claims.get(UserConstants.TOKEN_USER_ATTRIBUTES))
                .thenReturn(Map.of("region", "jiangsu", "clearance", "high"));
        TokenService tokenService = mock(TokenService.class);
        when(tokenService.getClaims("signed-token", "bank-app")).thenReturn(Optional.of(claims));

        User user = new HttpHeaderUserStrategy(tokenService).getUser("signed-token", "bank-app");

        assertEquals(Set.of("risk_manager", "branch_operator"), user.getRoles());
        assertEquals(Map.of("region", "jiangsu", "clearance", "high"), user.getAttributes());
    }

    @Test
    void tokenClaimsIncludeDefensiveAuthorizationSnapshots() {
        UserWithPassword user =
                UserWithPassword.get(9L, "risk-user", "Risk User", "risk@example.com", "secret", 0);
        user.setRoles(new java.util.LinkedHashSet<>(Set.of("risk_manager")));
        user.setAttributes(new java.util.LinkedHashMap<>(Map.of("region", "jiangsu")));

        Map<String, Object> claims = UserWithPassword.convert(user);
        user.getRoles().clear();
        user.getAttributes().clear();

        assertEquals(Set.of("risk_manager"), claims.get(UserConstants.TOKEN_USER_ROLES));
        assertEquals(Map.of("region", "jiangsu"), claims.get(UserConstants.TOKEN_USER_ATTRIBUTES));
    }
}
