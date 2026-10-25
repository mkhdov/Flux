package com.mkhdov.flux.ui.main;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.mkhdov.flux.data.model.DaySnapshot;
import com.mkhdov.flux.ui.calendar.MonthView;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

public class MonthPagerAdapter extends RecyclerView.Adapter<MonthPagerAdapter.Holder> {
    private static final YearMonth BASE = YearMonth.of(1900, 1);
    private static final YearMonth END = YearMonth.of(2100, 1);
    private static final int COUNT = (int) ChronoUnit.MONTHS.between(BASE, END) + 1;

    private final MonthView.Listener listener;
    private DaySnapshot snapshot = new DaySnapshot();
    private LocalDate selectedDate = LocalDate.now();

    public MonthPagerAdapter(MonthView.Listener listener) {
        this.listener = listener;
    }

    public static int positionFor(YearMonth month) {
        long months = ChronoUnit.MONTHS.between(BASE, month);
        return (int) Math.max(0, Math.min(COUNT - 1, months));
    }

    public static YearMonth monthFor(int position) {
        return BASE.plusMonths(Math.max(0, Math.min(COUNT - 1, position)));
    }

    public void setSnapshot(DaySnapshot snapshot) {
        this.snapshot = snapshot == null ? new DaySnapshot() : snapshot;
        notifyDataSetChanged();
    }

    public void setSelectedDate(LocalDate date) {
        if (date == null || date.equals(selectedDate)) return;
        selectedDate = date;
        notifyDataSetChanged();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        MonthView view = new MonthView(parent.getContext());
        view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        view.setListener(listener);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.month.setSelectedDate(selectedDate);
        holder.month.setMonth(monthFor(position));
        holder.month.setSnapshot(snapshot);
    }

    @Override public int getItemCount() { return COUNT; }

    static class Holder extends RecyclerView.ViewHolder {
        final MonthView month;
        Holder(MonthView itemView) {
            super(itemView);
            month = itemView;
        }
    }
}
