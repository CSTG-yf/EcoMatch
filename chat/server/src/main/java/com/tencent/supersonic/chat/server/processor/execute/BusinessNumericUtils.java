package com.tencent.supersonic.chat.server.processor.execute;

import java.math.BigDecimal;

/** Parses analytical numbers while bounding representations that expand excessively. */
final class BusinessNumericUtils {

    private static final int MAX_PRECISION = 100;
    private static final int MAX_ABSOLUTE_SCALE = 100;
    private static final int MAX_NUMERIC_TEXT_LENGTH = 256;

    private BusinessNumericUtils() {}

    static BigDecimal parse(Object value) {
        try {
            if (value == null) {
                return null;
            }
            if (value instanceof CharSequence
                    && ((CharSequence) value).length() > MAX_NUMERIC_TEXT_LENGTH) {
                return null;
            }
            String text = String.valueOf(value);
            if (text.length() > MAX_NUMERIC_TEXT_LENGTH) {
                return null;
            }
            BigDecimal decimal = new BigDecimal(text);
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
