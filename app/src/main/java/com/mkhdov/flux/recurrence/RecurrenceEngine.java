package com.mkhdov.flux.recurrence;

import com.mkhdov.flux.data.local.entity.EventEntity;
import com.mkhdov.flux.data.local.entity.EventExceptionEntity;
import com.mkhdov.flux.data.model.EventInstance;
import com.mkhdov.flux.util.RRuleUtils;

import org.dmfs.rfc5545.recur.RecurrenceRule;
import org.dmfs.rfc5545.recur.RecurrenceRuleIterator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

public final class RecurrenceEngine {
    private static final int MAX_OCCURRENCES = 20000;

    private RecurrenceEngine() { }

    public static List<EventInstance> expand(List<EventEntity> events,
                                             List<EventExceptionEntity> exceptions,
                                             long rangeStart, long rangeEnd) {
        List<EventInstance> out = new ArrayList<>();
        Map<String, Map<Long, EventExceptionEntity>> exceptionMap = new HashMap<>();
        if (exceptions != null) {
            for (EventExceptionEntity ex : exceptions) {
                if (ex.deletedAt > 0) continue;
                exceptionMap.computeIfAbsent(ex.eventId, key -> new HashMap<>()).put(ex.occurrenceStart, ex);
            }
        }
        Set<String> overriddenIds = new HashSet<>();
        if (exceptions != null) {
            for (EventExceptionEntity ex : exceptions) {
                if (ex.deletedAt > 0) continue;
                if (ex.overrideEventId != null) overriddenIds.add(ex.overrideEventId);
            }
        }
        for (EventEntity event : events) {
            if (event.deletedAt > 0) continue;
            if (!event.isRecurring()) {
                if (overlaps(event.startAt, event.endAt, rangeStart, rangeEnd)) {
                    out.add(new EventInstance(event, event.startAt, event.endAt, event.startAt, false));
                }
                continue;
            }
            expandRecurring(event, exceptionMap.getOrDefault(event.id, new HashMap<>()), rangeStart, rangeEnd, out);
        }
        return out;
    }

    private static void expandRecurring(EventEntity event, Map<Long, EventExceptionEntity> exceptions,
                                        long rangeStart, long rangeEnd, List<EventInstance> out) {
        String ruleText = RRuleUtils.normalize(event.rrule);
        if (ruleText == null) {
            if (overlaps(event.startAt, event.endAt, rangeStart, rangeEnd)) {
                out.add(new EventInstance(event, event.startAt, event.endAt, event.startAt, false));
            }
            return;
        }
        ZoneId zone;
        TimeZone tz;
        try {
            zone = ZoneId.of(event.timezone == null || event.timezone.isEmpty() ? ZoneId.systemDefault().getId() : event.timezone);
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }
        tz = TimeZone.getTimeZone(zone);
        long duration = Math.max(0L, event.endAt - event.startAt);
        long searchFrom = Math.max(event.startAt, rangeStart - Math.max(60000L, duration));
        try {
            RecurrenceRule rule = new RecurrenceRule(ruleText);
            RecurrenceRuleIterator iterator = rule.iterator(event.startAt, tz);
            if (searchFrom > event.startAt) iterator.fastForward(searchFrom);
            int guard = 0;
            while (iterator.hasNext() && guard++ < MAX_OCCURRENCES) {
                long occurrence = iterator.nextMillis();
                if (occurrence > rangeEnd) break;
                long occurrenceEnd = occurrenceEnd(event, occurrence, zone, duration);
                if (occurrenceEnd <= rangeStart) continue;
                EventExceptionEntity exception = exceptions.get(occurrence);
                if (exception != null) {
                    if (exception.cancelled) continue;
                    if (exception.overrideEventId != null) continue;
                }
                out.add(new EventInstance(event, occurrence, occurrenceEnd, occurrence, true));
            }
        } catch (Exception e) {
            if (overlaps(event.startAt, event.endAt, rangeStart, rangeEnd)) {
                out.add(new EventInstance(event, event.startAt, event.endAt, event.startAt, false));
            }
        }
    }

    public static long occurrenceEnd(EventEntity event, long occurrence, ZoneId zone, long duration) {
        if (event.allDay) {
            long days = Math.max(1L, Math.round(duration / 86400000.0));
            LocalDate date = Instant.ofEpochMilli(occurrence).atZone(zone).toLocalDate();
            return date.plusDays(days).atStartOfDay(zone).toInstant().toEpochMilli();
        }
        return occurrence + duration;
    }

    public static List<Long> occurrenceStarts(EventEntity event, List<EventExceptionEntity> exceptions,
                                              long rangeStart, long rangeEnd, int limit) {
        List<Long> starts = new ArrayList<>();
        String ruleText = RRuleUtils.normalize(event.rrule);
        if (ruleText == null) {
            starts.add(event.startAt);
            return starts;
        }
        TimeZone tz = TimeZone.getTimeZone(event.timezone == null || event.timezone.isEmpty() ? ZoneId.systemDefault() : ZoneId.of(event.timezone));
        try {
            RecurrenceRule rule = new RecurrenceRule(ruleText);
            RecurrenceRuleIterator iterator = rule.iterator(event.startAt, tz);
            if (rangeStart > event.startAt) iterator.fastForward(rangeStart);
            int guard = 0;
            while (iterator.hasNext() && starts.size() < limit && guard++ < MAX_OCCURRENCES) {
                long occurrence = iterator.nextMillis();
                if (occurrence > rangeEnd) break;
                boolean cancelled = false;
                if (exceptions != null) {
                    for (EventExceptionEntity ex : exceptions) {
                        if (ex.deletedAt == 0 && ex.eventId.equals(event.id) && ex.occurrenceStart == occurrence && ex.cancelled) {
                            cancelled = true;
                            break;
                        }
                    }
                }
                if (!cancelled) starts.add(occurrence);
            }
        } catch (Exception e) {
            starts.add(event.startAt);
        }
        return starts;
    }

    private static boolean overlaps(long start, long end, long rangeStart, long rangeEnd) {
        return start < rangeEnd && end > rangeStart;
    }
}
