package com.tencent.supersonic.common.util;

import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

@Slf4j
public class RetryUtils {

    private static final int RETRY_NUM = 3;

    public static <T> T exec(Supplier<T> supplier) {
        return exec(supplier, RETRY_NUM);
    }

    public static <T> T exec(Supplier<T> supplier, int retryNum) {
        T result = null;
        for (int index = 1; index <= retryNum; index++) {
            try {
                result = supplier.get();
            } catch (Exception ex) {
                if (index < retryNum) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Retry interrupted: type={}, error=[{}]",
                                e.getClass().getSimpleName(), SensitiveLogUtils.summarize(e));
                        throw new IllegalStateException("Retry interrupted");
                    }
                    log.warn("Retry attempt failed: attempt={}, type={}, error=[{}]", index,
                            ex.getClass().getSimpleName(), SensitiveLogUtils.summarize(ex));
                    continue;
                }
                log.warn("All retry attempts failed: attempts={}, type={}, error=[{}]", retryNum,
                        ex.getClass().getSimpleName(), SensitiveLogUtils.summarize(ex));
                throw ex;
            }
            break;
        }

        return result;
    }
}
