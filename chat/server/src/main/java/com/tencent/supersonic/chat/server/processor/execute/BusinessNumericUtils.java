package com.tencent.supersonic.chat.server.processor.execute;

import java.math.BigDecimal;

/** Parses analytical numbers while bounding representations that expand excessively. */
final class BusinessNumericUtils {

    private static final int MAX_PRECISION = 100;
    private static final int MAX_ABSOLUTE_SCALE = 100;

    private BusinessNumericUtils() {}

    static BigDecimal parse(Object value) {
        try {
            if (value == null) {
                return null;
            }
            BigDecimal decimal = new BigDecimal(String.valueOf(value));
            if (decimal.precision() > MAX_PRECISION
                    || Math.abs((long) decimal.scale()) > MAX_ABSOLUTE_SCALE) {
                return null;
            }
            return decimal;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
