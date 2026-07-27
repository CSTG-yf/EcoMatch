package com.tencent.supersonic.headless.server.security.audit.model;

import lombok.Data;

@Data
public class AlertDispositionRequest {
    private AlertStatus status;
    private String comment;
    private Integer version;
}
