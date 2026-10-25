package com.mkhdov.flux.ui.main;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.mkhdov.flux.data.model.DaySnapshot;
import com.mkhdov.flux.ui.calendar.DayTimelineView;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public class DayPagerAdapter extends RecyclerView.Adapter<DayPagerAdapter.Holder> {
    private static final LocalDate BASE = LocalDate.of(1900, 1, 1);
    private static final LocalDate END = LocalDate.of(2100, 1, 1);
    private static final int COUNT = (int) ChronoUnit.DAYS.between(BASE, END) + 1;

    private final DayTimelineView.ScrollState scrollState;
    private final DayTimelineView.Listener listener;
    private DaySnapshot snapshot = new DaySnapshot();

    public DayPagerAdapter(DayTimelineView.ScrollState scrollState, DayTimelineView.Listener listener) {
        this.scrollState = scrollState;
        this.listener = listener;
    }

    public static int positionFor(LocalDate date) {
        long days = ChronoUnit.DAYS.between(BASE, date);
        return (int) Math.max(0, Math.min(COUNT - 1, days));
    }

    public static LocalDate dateFor(int position) {
        return BASE.plusDays(Math.max(0, Math.min(COUNT - 1, position)));
    }

    public void setSnapshot(DaySnapshot snapshot) {
        this.snapshot = snapshot == null ? new DaySnapshot() : snapshot;
        notifyDataSetChanged();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        DayTimelineView view = new DayTimelineView(parent.getContext());
        view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        view.setScrollState(scrollState);
        view.setListener(listener);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.timeline.setDate(dateFor(position));
        holder.timeline.setSnapshot(snapshot);
    }

    @Override public int getItemCount() { return COUNT; }

    static class Holder extends RecyclerView.ViewHolder {
        final DayTimelineView timeline;
        Holder(DayTimelineView itemView) {
            super(itemView);
            timeline = itemView;
        }
    }
}
