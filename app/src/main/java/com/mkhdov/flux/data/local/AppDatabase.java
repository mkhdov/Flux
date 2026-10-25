package com.mkhdov.flux.data.local;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.mkhdov.flux.data.local.dao.AttachmentDao;
import com.mkhdov.flux.data.local.dao.CalendarDao;
import com.mkhdov.flux.data.local.dao.EventDao;
import com.mkhdov.flux.data.local.dao.EventExceptionDao;
import com.mkhdov.flux.data.local.dao.OutboxDao;
import com.mkhdov.flux.data.local.dao.ReminderDao;
import com.mkhdov.flux.data.local.dao.ScheduledAlarmDao;
import com.mkhdov.flux.data.local.dao.SyncStateDao;
import com.mkhdov.flux.data.local.entity.AttachmentEntity;
import com.mkhdov.flux.data.local.entity.CalendarEntity;
import com.mkhdov.flux.data.local.entity.EventEntity;
import com.mkhdov.flux.data.local.entity.EventExceptionEntity;
import com.mkhdov.flux.data.local.entity.OutboxEntity;
import com.mkhdov.flux.data.local.entity.ReminderEntity;
import com.mkhdov.flux.data.local.entity.ScheduledAlarmEntity;
import com.mkhdov.flux.data.local.entity.SyncStateEntity;

@Database(entities = {
        CalendarEntity.class, EventEntity.class, ReminderEntity.class,
        EventExceptionEntity.class, AttachmentEntity.class, OutboxEntity.class,
        SyncStateEntity.class, ScheduledAlarmEntity.class
}, version = 1, exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    public abstract CalendarDao calendarDao();
    public abstract EventDao eventDao();
    public abstract ReminderDao reminderDao();
    public abstract EventExceptionDao exceptionDao();
    public abstract AttachmentDao attachmentDao();
    public abstract OutboxDao outboxDao();
    public abstract SyncStateDao syncStateDao();
    public abstract ScheduledAlarmDao scheduledAlarmDao();

    public static AppDatabase get(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, "flux.db")
                            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
