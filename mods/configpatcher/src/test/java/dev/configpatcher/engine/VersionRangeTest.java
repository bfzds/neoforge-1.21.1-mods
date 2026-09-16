package dev.configpatcher.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionRangeTest {

    @Test
    void emptyOrStarRangeAcceptsEverything() {
        assertTrue(VersionRange.satisfies(null, "1.0.0"));
        assertTrue(VersionRange.satisfies("", "1.0.0"));
        assertTrue(VersionRange.satisfies("*", "1.0.0"));
    }

    @Test
    void bracketRangeRespectsBoundaries() {
        assertFalse(VersionRange.satisfies("[1.0.0,2.0.0)", "0.9.9"));
        assertTrue(VersionRange.satisfies("[1.0.0,2.0.0)", "1.0.0"));
        assertTrue(VersionRange.satisfies("[1.0.0,2.0.0)", "1.9.9"));
        assertFalse(VersionRange.satisfies("[1.0.0,2.0.0)", "2.0.0"));
        assertTrue(VersionRange.satisfies("[1.0.0,2.0.0]", "2.0.0"));
    }

    @Test
    void openEndedRangeWorks() {
        assertTrue(VersionRange.satisfies("[1.21.1,)", "13.13.5"));
        assertFalse(VersionRange.satisfies("(,2.0.0]", "2.0.1"));
        assertTrue(VersionRange.satisfies("(,2.0.0]", "1.0.0"));
    }

    @Test
    void commaSeparatedConditionsAreCombined() {
        assertTrue(VersionRange.satisfies(">=1.0.0,<2.0.0", "1.5.0"));
        assertFalse(VersionRange.satisfies(">=1.0.0,<2.0.0", "2.0.0"));
    }

    @Test
    void versionComparisonIsNumericPerSegment() {
        assertTrue(VersionRange.compare("1.10.0", "1.9.0") > 0);
        assertEquals(0, VersionRange.compare("1.0", "1.0.0"));
        assertTrue(VersionRange.compare("1.0.0", "1.0.0-beta") > 0);
        assertTrue(VersionRange.compare("21.1.130", "21.1.9") > 0);
    }

    @Test
    void missingVersionDoesNotAccidentallyMatch() {
        assertFalse(VersionRange.satisfies("[1.0.0,)", null));
        assertTrue(VersionRange.satisfies("*", null));
    }
}
