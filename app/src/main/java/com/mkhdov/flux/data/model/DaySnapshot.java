package com.mkhdov.flux.data.model;

import com.mkhdov.flux.data.local.entity.CalendarEntity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DaySnapshot {
    public final Map<LocalDate, List<EventInstance>> byDay = new HashMap<>();
    public final Map<String, CalendarEntity> calendars = new HashMap<>();
    public long generatedAt;

    public List<EventInstance> eventsFor(LocalDate date) {
        List<EventInstance> events = byDay.get(date);
        return events == null ? Collections.emptyList() : events;
    }

    public void put(EventInstance event, LocalDate date) {
        byDay.computeIfAbsent(date, key -> new ArrayList<>()).add(event);
    }

    public CalendarEntity calendarFor(String id) {
        return calendars.get(id);
    }
}
