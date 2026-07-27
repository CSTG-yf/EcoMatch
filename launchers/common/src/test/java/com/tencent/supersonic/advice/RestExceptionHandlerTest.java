package com.tencent.supersonic.advice;

import com.tencent.supersonic.common.pojo.ResultData;
import com.tencent.supersonic.common.pojo.enums.ReturnCode;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestExceptionHandlerTest {

    private final RestExceptionHandler handler = new RestExceptionHandler();

    @Test
    void unknownExceptionDoesNotExposeUnderlyingMessage() {
        ResultData<String> result =
                handler.exception(new RuntimeException("select secret from customer_account"));

        assertEquals(ReturnCode.SYSTEM_ERROR.getCode(), result.getCode());
        assertEquals(ReturnCode.SYSTEM_ERROR.getMessage(), result.getMsg());
    }

    @Test
    void actionableInvalidArgumentMessageIsPreserved() {
        ResultData<String> result =
                handler.invalidArgumentException(new InvalidArgumentException("invalid filter"));

        assertEquals(ReturnCode.INVALID_REQUEST.getCode(), result.getCode());
        assertEquals("invalid filter", result.getMsg());
    }
}
