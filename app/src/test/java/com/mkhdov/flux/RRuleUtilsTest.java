package com.mkhdov.flux;

import com.mkhdov.flux.util.RRuleUtils;

import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RRuleUtilsTest {
    @Test public void stripsRrulePrefix() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO", RRuleUtils.stripPrefix("RRULE:FREQ=WEEKLY;BYDAY=MO"));
        assertEquals("FREQ=WEEKLY", RRuleUtils.normalize("  FREQ=WEEKLY  "));
        assertNull(RRuleUtils.normalize("  "));
    }

    @Test public void validatesRules() {
        assertTrue(RRuleUtils.isValid("FREQ=DAILY;INTERVAL=2"));
        assertTrue(RRuleUtils.isValid("RRULE:FREQ=MONTHLY;BYMONTHDAY=15"));
        assertFalse(RRuleUtils.isValid("FREQ=NOPE"));
        assertFalse(RRuleUtils.isValid(null));
        assertFalse(RRuleUtils.isValid(""));
    }

    @Test public void formatsUntilInUtc() {
        long epoch = Instant.parse("2026-10-22T18:30:00Z").toEpochMilli();
        assertEquals("20261022T183000Z", RRuleUtils.untilFormatted(epoch));
    }

    @Test public void limitUntilReplacesCountAndUntil() {
        String limited = RRuleUtils.limitUntil("FREQ=WEEKLY;COUNT=5;BYDAY=MO", 1792694400000L);

        assertFalse(limited.contains("COUNT="));
        assertTrue(limited.startsWith("FREQ=WEEKLY;BYDAY=MO"));
        assertTrue(limited.endsWith(";UNTIL=" + RRuleUtils.untilFormatted(1792694400000L)));
    }

    @Test public void withUntilCountReplacesExistingLimits() {
        String counted = RRuleUtils.withUntilCount("FREQ=DAILY;UNTIL=20261022T000000Z", 3);

        assertEquals("FREQ=DAILY;COUNT=3", counted);
        assertEquals("FREQ=DAILY;COUNT=1", RRuleUtils.withUntilCount("FREQ=DAILY", 0));
    }

    @Test public void buildsSimpleRules() {
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;UNTIL=20261101T235959Z",
                RRuleUtils.build("weekly", 2, "MO,WE", "2026-11-01", null));
        assertEquals("FREQ=DAILY;COUNT=4",
                RRuleUtils.build("daily", 1, null, null, 4));
    }

    @Test public void describesRules() {
        assertEquals("Daily", RRuleUtils.describe("FREQ=DAILY"));
        assertEquals("Every 2 weeks", RRuleUtils.describe("FREQ=WEEKLY;INTERVAL=2"));
        assertEquals("Does not repeat", RRuleUtils.describe(null));
        assertEquals("Custom repeat", RRuleUtils.describe("FREQ=NOPE"));
    }
}
