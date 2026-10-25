package com.mkhdov.flux;

import com.mkhdov.flux.util.QuickAddParser;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QuickAddParserTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final LocalDate REFERENCE = LocalDate.of(2026, 10, 21);

    @Test public void parsesTomorrowAndTime() {
        QuickAddParser.Result result = QuickAddParser.parse("Dentist tomorrow 3pm", REFERENCE, UTC);

        assertEquals("Dentist", result.title);
        assertEquals(epoch(2026, 10, 22, 15, 0), result.startAt);
        assertEquals(epoch(2026, 10, 22, 16, 0), result.endAt);
        assertTrue(result.matchedTime);
    }

    @Test public void parsesWeekdayAbbreviationAndRange() {
        QuickAddParser.Result result = QuickAddParser.parse("Lunch Fri 12:30-13:30", REFERENCE, UTC);

        assertEquals("Lunch", result.title);
        assertEquals(epoch(2026, 10, 23, 12, 30), result.startAt);
        assertEquals(epoch(2026, 10, 23, 13, 30), result.endAt);
        assertTrue(result.matchedTime);
    }

    @Test public void parsesAmPmRange() {
        QuickAddParser.Result result = QuickAddParser.parse("Review tomorrow 3pm-4:30pm", REFERENCE, UTC);

        assertEquals("Review", result.title);
        assertEquals(epoch(2026, 10, 22, 15, 0), result.startAt);
        assertEquals(epoch(2026, 10, 22, 16, 30), result.endAt);
    }

    @Test public void explicitDurationOverridesDefault() {
        QuickAddParser.Result result = QuickAddParser.parse("Study tomorrow at 4 PM for 90 minutes", REFERENCE, UTC);

        assertEquals("Study", result.title);
        assertEquals(epoch(2026, 10, 22, 16, 0), result.startAt);
        assertEquals(90 * 60_000L, result.endAt - result.startAt);
    }

    @Test public void crossesMidnightForOvernightRange() {
        QuickAddParser.Result result = QuickAddParser.parse("Party tomorrow 11pm-1am", REFERENCE, UTC);

        assertEquals("Party", result.title);
        assertEquals(epoch(2026, 10, 22, 23, 0), result.startAt);
        assertEquals(epoch(2026, 10, 23, 1, 0), result.endAt);
    }

    @Test public void fallsBackWhenNoTimeIsGiven() {
        QuickAddParser.Result result = QuickAddParser.parse("Call mom tomorrow", REFERENCE, UTC);

        assertEquals("Call mom", result.title);
        assertFalse(result.matchedTime);
        assertTrue(result.endAt > result.startAt);
        assertEquals(60 * 60_000L, result.endAt - result.startAt);
    }

    @Test public void parsesIsoDate() {
        QuickAddParser.Result result = QuickAddParser.parse("Flight 2026-11-05 09:15", REFERENCE, UTC);

        assertEquals("Flight", result.title);
        assertEquals(epoch(2026, 11, 5, 9, 15), result.startAt);
    }

    @Test public void emptyInputYieldsDefaults() {
        QuickAddParser.Result result = QuickAddParser.parse("  ", REFERENCE, UTC);

        assertEquals("New event", result.title);
        assertFalse(result.matchedTime);
    }

    private static long epoch(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, UTC).toInstant().toEpochMilli();
    }
}
