package com.tencent.supersonic.common.util;

import javax.net.ssl.HostnameVerifier;

import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HttpClientUtilsSecurityTest {

    @Test
    void sslFactoryUsesSystemTrustAndDefaultHostnameVerification() throws Exception {
        SSLConnectionSocketFactory factory = HttpClientUtils.createSslSocketFactory();
        Field verifierField = SSLConnectionSocketFactory.class.getDeclaredField("hostnameVerifier");
        verifierField.setAccessible(true);
        HostnameVerifier verifier = (HostnameVerifier) verifierField.get(factory);

        assertEquals(SSLConnectionSocketFactory.getDefaultHostnameVerifier().getClass(),
                verifier.getClass());
        assertFalse(verifier.getClass().getName().contains("Noop"));
    }
}
