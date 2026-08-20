package com.tencent.supersonic.auth.api.authorization.pojo;

/** Access mode for a metric or dimension after a policy has matched. */
public enum ColumnAccessMode {
    DENY,
    MASKED,
    RAW
}
