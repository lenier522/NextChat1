package cu.lenier.nextchat.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.Database;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import cu.lenier.nextchat.model.Message;
import cu.lenier.nextchat.model.Profile;

@Database(entities = { Message.class, Profile.class }, version = 5, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract MessageDao messageDao();
    public abstract ProfileDao profileDao();

    private static volatile AppDatabase INSTANCE;

    private static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN attachmentPath TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN type TEXT NOT NULL DEFAULT 'text'");
            db.execSQL("ALTER TABLE messages ADD COLUMN sendState INTEGER NOT NULL DEFAULT 2");
        }
    };

    private static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `profiles` (" +
                            "  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                            "  `name` TEXT NOT NULL," +
                            "  `email` TEXT NOT NULL," +
                            "  `phone` TEXT," +
                            "  `info` TEXT," +
                            "  `gender` TEXT," +
                            "  `birthDate` TEXT," +
                            "  `country` TEXT," +
                            "  `province` TEXT," +
                            "  `photoUri` TEXT," +
                            "  `typing` INTEGER NOT NULL DEFAULT 0," +
                            "  `online` INTEGER NOT NULL DEFAULT 0," +
                            "  `lastSeen` INTEGER NOT NULL DEFAULT 0" +
                            ")"
            );
        }
    };

    public static AppDatabase getInstance(Context ctx) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    ctx.getApplicationContext(),
                                    AppDatabase.class,
                                    "mailchat_db"
                            )
                            .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
