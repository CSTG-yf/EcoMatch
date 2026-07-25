package com.tencent.supersonic.headless.api.pojo;

import com.tencent.supersonic.headless.api.pojo.enums.SqlErrorType;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class SqlEvaluation implements Serializable {
    private static final long serialVersionUID = 1L;

    private Boolean isValidated;
    private String validateMsg;
    private SqlErrorType errorType = SqlErrorType.NONE;
    private Boolean retryable = false;
    private Double semanticScore = 0D;
    private List<String> features = new ArrayList<>();
}
