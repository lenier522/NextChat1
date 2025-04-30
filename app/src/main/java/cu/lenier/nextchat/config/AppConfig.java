// src/main/java/cu/lenier/nextchat/config/AppConfig.java
package cu.lenier.nextchat.config;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import cu.lenier.nextchat.exceptions.ExceptionHandler;
import cu.lenier.nextchat.work.MailSyncWorker;

public class AppConfig extends Application {
    private static AppConfig instance;
    private static final String CHANNEL_SYNC = "MailSyncChannel";
    private static final String CHANNEL_NEWMSG = "NewMsgChannel";

    private static final Map<String, String> VERIFIED_NAMES;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("leniercruz02@nauta.cu", "Lenier");
        m.put("martha.avila56@nauta.cu", "Martha");
        VERIFIED_NAMES = Collections.unmodifiableMap(m);
    }

    private static String currentChatContact = null;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        Thread.setDefaultUncaughtExceptionHandler(new ExceptionHandler(this));
        createNotificationChannels();
        scheduleWorkerIfNeeded();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel syncChannel = new NotificationChannel(
                    CHANNEL_SYNC, "NextChat Sync", NotificationManager.IMPORTANCE_LOW
            );

            NotificationChannel msgChannel = new NotificationChannel(
                    CHANNEL_NEWMSG, "Notificaciones", NotificationManager.IMPORTANCE_DEFAULT
            );

            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(syncChannel);
            nm.createNotificationChannel(msgChannel);
        }
    }

    private void scheduleWorkerIfNeeded() {
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        String email = prefs.getString("email", "");
        String pass = prefs.getString("pass", "");

        if (!email.isEmpty() && !pass.isEmpty()) {
            MailSyncWorker.schedulePeriodicSync(this);
        }
    }

    public static AppConfig getInstance() {
        return instance;
    }

    public static void setCurrentChat(String contact) {
        currentChatContact = contact;
    }

    public static String getCurrentChat() {
        return currentChatContact;
    }

    public static boolean isVerified(String email) {
        return email != null && VERIFIED_NAMES.containsKey(email.trim().toLowerCase());
    }

    public static String getDisplayName(String email) {
        if (email == null) return "";
        String key = email.trim().toLowerCase();
        return VERIFIED_NAMES.getOrDefault(key, email);
    }
}