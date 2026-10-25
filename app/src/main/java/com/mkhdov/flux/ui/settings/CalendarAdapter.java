package com.mkhdov.flux.ui.settings;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.mkhdov.flux.R;
import com.mkhdov.flux.data.local.entity.CalendarEntity;
import com.mkhdov.flux.ui.common.UiUtils;
import com.mkhdov.flux.util.TimeUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CalendarAdapter extends RecyclerView.Adapter<CalendarAdapter.Holder> {
    public interface Listener {
        void onCalendarClick(CalendarEntity calendar);
        void onToggleVisible(CalendarEntity calendar);
        void onMakeDefault(CalendarEntity calendar);
        void onMore(CalendarEntity calendar, View anchor);
    }

    private final List<CalendarEntity> items = new ArrayList<>();
    private final Map<String, Integer> counts = new HashMap<>();
    private final Listener listener;

    public CalendarAdapter(Listener listener) { this.listener = listener; }

    public void setItems(List<CalendarEntity> calendars, Map<String, Integer> eventCounts) {
        items.clear();
        if (calendars != null) items.addAll(calendars);
        counts.clear();
        if (eventCounts != null) counts.putAll(eventCounts);
        notifyDataSetChanged();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_calendar, parent, false);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        CalendarEntity calendar = items.get(position);
        holder.name.setText(calendar.name);
        UiUtils.colorDot(holder.dot, calendar.color);
        StringBuilder subtitle = new StringBuilder();
        int count = counts.containsKey(calendar.id) ? counts.get(calendar.id) : 0;
        subtitle.append(holder.itemView.getContext().getResources()
                .getQuantityString(R.plurals.calendar_events_count, count, count));
        if (calendar.archived) subtitle.append(" · ").append(holder.itemView.getContext().getString(R.string.calendar_archived));
        else if (!calendar.visible) subtitle.append(" · ").append(holder.itemView.getContext().getString(R.string.calendar_hidden));
        if (calendar.isDefault) subtitle.append(" · ").append(holder.itemView.getContext().getString(R.string.calendar_default));
        holder.subtitle.setText(subtitle.toString());
        holder.defaultButton.setImageResource(calendar.isDefault ? R.drawable.ic_star_filled : R.drawable.ic_star);
        holder.defaultButton.setAlpha(calendar.isDefault ? 1f : 0.5f);
        holder.visibility.setImageResource(calendar.visible ? R.drawable.ic_visibility : R.drawable.ic_visibility_off);
        holder.visibility.setAlpha(calendar.visible ? 1f : 0.5f);
        holder.itemView.setOnClickListener(v -> listener.onCalendarClick(calendar));
        holder.defaultButton.setOnClickListener(v -> listener.onMakeDefault(calendar));
        holder.visibility.setOnClickListener(v -> listener.onToggleVisible(calendar));
        holder.more.setOnClickListener(v -> listener.onMore(calendar, holder.more));
    }

    @Override public int getItemCount() { return items.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        final View dot;
        final TextView name;
        final TextView subtitle;
        final ImageButton defaultButton;
        final ImageButton visibility;
        final ImageButton more;
        Holder(View view) {
            super(view);
            dot = view.findViewById(R.id.calendar_dot);
            name = view.findViewById(R.id.calendar_name);
            subtitle = view.findViewById(R.id.calendar_subtitle);
            defaultButton = view.findViewById(R.id.calendar_default);
            visibility = view.findViewById(R.id.calendar_visibility);
            more = view.findViewById(R.id.calendar_more);
        }
    }
}
