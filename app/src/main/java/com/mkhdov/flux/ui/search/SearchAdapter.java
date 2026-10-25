package com.mkhdov.flux.ui.search;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.mkhdov.flux.R;
import com.mkhdov.flux.data.local.entity.CalendarEntity;
import com.mkhdov.flux.data.local.entity.EventEntity;
import com.mkhdov.flux.ui.common.UiUtils;
import com.mkhdov.flux.util.EventPalette;
import com.mkhdov.flux.util.TimeUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SearchAdapter extends RecyclerView.Adapter<SearchAdapter.Holder> {
    public interface Listener { void onEventClick(EventEntity event); }

    private final List<EventEntity> items = new ArrayList<>();
    private final Map<String, CalendarEntity> calendars;
    private final Listener listener;

    public SearchAdapter(List<CalendarEntity> calendars, Listener listener) {
        this.calendars = new java.util.HashMap<>();
        if (calendars != null) for (CalendarEntity c : calendars) this.calendars.put(c.id, c);
        this.listener = listener;
    }

    public void setItems(List<EventEntity> events) {
        items.clear();
        if (events != null) items.addAll(events);
        notifyDataSetChanged();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_search_event, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        EventEntity event = items.get(position);
        CalendarEntity calendar = calendars.get(event.calendarId);
        String hex = EventPalette.isValid(event.color) ? event.color : (calendar == null ? EventPalette.COLORS.get(0).hex : calendar.color);
        holder.bar.setBackgroundColor(EventPalette.parse(hex, holder.bar.getContext().getColor(R.color.accent)));
        holder.title.setText(event.title == null || event.title.trim().isEmpty()
                ? holder.itemView.getContext().getString(R.string.untitled) : event.title);
        ZoneId zone = TimeUtils.zoneOf(event);
        String date = TimeUtils.shortDate(TimeUtils.dateOf(event.startAt, zone));
        String time = event.allDay ? holder.itemView.getContext().getString(R.string.all_day) : TimeUtils.formatTimeRange(event);
        holder.time.setText(date + " · " + time);
        if (event.location != null && !event.location.trim().isEmpty()) {
            holder.location.setVisibility(View.VISIBLE);
            holder.location.setText(event.location);
        } else {
            holder.location.setVisibility(View.GONE);
        }
        holder.title.setAlpha(event.completed ? 0.55f : 1f);
        holder.itemView.setOnClickListener(v -> listener.onEventClick(event));
    }

    @Override public int getItemCount() { return items.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        final View bar;
        final TextView title;
        final TextView time;
        final TextView location;
        Holder(View view) {
            super(view);
            bar = view.findViewById(R.id.search_event_bar);
            title = view.findViewById(R.id.search_event_title);
            time = view.findViewById(R.id.search_event_time);
            location = view.findViewById(R.id.search_event_location);
        }
    }
}
