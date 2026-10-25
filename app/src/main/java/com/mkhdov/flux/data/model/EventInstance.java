package com.mkhdov.flux.data.model;

import com.mkhdov.flux.data.local.entity.EventEntity;

public class EventInstance {
    public final EventEntity source;
    public final long startAt;
    public final long endAt;
    public final long occurrenceStart;
    public final boolean recurringOccurrence;
    public final String instanceId;

    public EventInstance(EventEntity source, long startAt, long endAt, long occurrenceStart, boolean recurringOccurrence) {
        this.source = source;
        this.startAt = startAt;
        this.endAt = endAt;
        this.occurrenceStart = occurrenceStart;
        this.recurringOccurrence = recurringOccurrence;
        this.instanceId = source.id + "@" + occurrenceStart;
    }

    public boolean isAllDay() { return source.allDay; }
    public long duration() { return Math.max(0L, endAt - startAt); }
    public EventInstance shifted(long newStart, long newEnd) {
        return new EventInstance(source, newStart, newEnd, occurrenceStart, recurringOccurrence);
    }
}
