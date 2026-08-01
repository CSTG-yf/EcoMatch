package com.tencent.supersonic.headless.server.integration;

import java.util.Map;

public interface InboundIntegrationHandler {

    String systemId();

    String operation();

    Map<String, Object> handle(IntegrationEnvelope envelope);
}
