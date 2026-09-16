package dev.configpatcher.engine;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueCoercionTest {

    private enum Mode {
        OFF, ON, AUTO
    }

    private static Object coerce(Object template, String json) {
        return ValueCoercion.coerce(template, JsonParser.parseString(json));
    }

    @Test
    void booleanAndNumbersUseTemplateType() {
        assertEquals(Boolean.TRUE, coerce(Boolean.FALSE, "true"));
        assertEquals(256, coerce(64, "256"));
        assertEquals(256L, coerce(64L, "256"));
        assertEquals(2.5D, coerce(1.0D, "2.5"));
        assertEquals("text", coerce("old", "\"text\""));
    }

    @Test
    void numericStringStillFitsNumericEntry() {
        assertEquals(256, coerce(64, "\"256\""));
    }

    @Test
    void listAcceptsArrayAndSingleValue() {
        assertEquals(List.of("a", "b"), coerce(List.of("x"), "[\"a\", \"b\"]"));
        assertEquals(List.of("a"), coerce(List.of("x"), "\"a\""));
        assertEquals(List.of(1, 2), coerce(List.of(0), "[1, 2]"));
    }

    @Test
    void enumAcceptsNameAndOrdinal() {
        assertEquals(Mode.AUTO, coerce(Mode.OFF, "\"AUTO\""));
        assertEquals(Mode.AUTO, coerce(Mode.OFF, "\"auto\""));
        assertEquals(Mode.ON, coerce(Mode.OFF, "1"));
    }

    @Test
    void incompatibleValueReportsClearError() {
        ValueCoercion.CoercionException exception = assertThrows(ValueCoercion.CoercionException.class,
                () -> coerce(Boolean.FALSE, "[1, 2]"));
        assertTrue(exception.getMessage().contains("Boolean"));
    }

    @Test
    void nullValueIsRejected() {
        assertThrows(ValueCoercion.CoercionException.class, () -> coerce(Boolean.FALSE, "null"));
    }
}
