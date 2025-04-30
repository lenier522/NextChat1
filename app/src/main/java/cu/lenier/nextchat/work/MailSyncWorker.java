// app/src/main/java/cu/lenier/nextchat/work/MailSyncWorker.java
package cu.lenier.nextchat.work;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.sun.mail.imap.IMAPFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import javax.mail.Folder;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;

import cu.lenier.nextchat.R;
import cu.lenier.nextchat.data.AppDatabase;
import cu.lenier.nextchat.data.MessageDao;
import cu.lenier.nextchat.model.Message;
import cu.lenier.nextchat.util.CryptoHelper;

public class MailSyncWorker extends Worker {
    private static final String TAG           = "MailSyncWorker";
    private static final String PREFS         = "prefs";
    private static final String KEY_LAST_SYNC = "last_sync";
    private static final String TXT_SUBJ      = "NextChat";
    private static final String AUD_SUBJ      = "NextChat Audio";
    private static final String NOTIF_CHANNEL = "NewMsgChannel";

    public MailSyncWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @Override
    public Result doWork() {
        try {
            Context ctx = getApplicationContext();
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long lastSync = prefs.getLong(KEY_LAST_SYNC, 0);
            String email = prefs.getString("email", "");
            String pass  = prefs.getString("pass", "");

            if (email.isEmpty() || pass.isEmpty()) {
                return Result.success();
            }

            // Configurar IMAP
            Properties props = new Properties();
            props.put("mail.imap.host", "imap.nauta.cu");
            props.put("mail.imap.port", "143");
            props.put("mail.imap.ssl.enable", "false");
            Session session = Session.getInstance(props);
            Store store = session.getStore("imap");
            store.connect(email, pass);

            IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            javax.mail.Message[] msgs = inbox.getMessages();
            long newLast = lastSync;
            MessageDao dao = AppDatabase.getInstance(ctx).messageDao();

            for (javax.mail.Message m : msgs) {
                long rts = m.getReceivedDate().getTime();
                if (rts <= lastSync) continue;
                newLast = Math.max(newLast, rts);

                Message msg = new Message();
                msg.fromAddress    = m.getFrom()[0].toString();
                msg.toAddress      = email;
                msg.timestamp      = rts;
                msg.sent           = false;
                msg.read           = false;
                msg.subject        = m.getSubject();

                Object content = m.getContent();
                if (TXT_SUBJ.equals(m.getSubject()) && !(content instanceof javax.mail.Multipart)) {
                    msg.type = "text";
                    msg.body = CryptoHelper.decrypt(content.toString());
                    msg.attachmentPath = null;
                } else if (AUD_SUBJ.equals(m.getSubject()) && content instanceof javax.mail.Multipart) {
                    msg.type = "audio";
                    msg.body = "";
                    javax.mail.Multipart mp = (javax.mail.Multipart) content;
                    for (int i = 0; i < mp.getCount(); i++) {
                        Part part = mp.getBodyPart(i);
                        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
                            File dir = new File(ctx.getExternalFilesDir(null), "audios_recibidos");
                            if (!dir.exists()) dir.mkdirs();
                            File enc = new File(dir, System.currentTimeMillis() + "_" + part.getFileName());
                            try (InputStream is = part.getInputStream();
                                 FileOutputStream fos = new FileOutputStream(enc)) {
                                byte[] buf = new byte[4096]; int r;
                                while ((r = is.read(buf)) > 0) fos.write(buf, 0, r);
                            }
                            File dec = new File(dir, enc.getName().replace(".enc", ".3gp"));
                            CryptoHelper.decryptAudio(enc, dec);
                            enc.delete();
                            msg.attachmentPath = dec.getAbsolutePath();
                            break;
                        }
                    }
                } else {
                    continue;
                }
                // SALTO si ya existe
                if (dao.countExisting(msg.fromAddress, msg.toAddress, msg.subject, msg.timestamp) > 0) {
                    continue;
                }

                dao.insert(msg);

                // Notificación
                NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("Nuevo mensaje de " + msg.fromAddress)
                        .setContentText(msg.type.equals("text") ? msg.body : "Audio recibido")
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true);

                NotificationManagerCompat.from(ctx).notify((int) rts, nb.build());
            }

            inbox.close(false);
            store.close();

            prefs.edit().putLong(KEY_LAST_SYNC, newLast).apply();
            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "Error en sincronización", e);
            return Result.retry();
        }
    }

    /** Llama este método tras login para programar el worker periódico */
    public static void schedulePeriodicSync(Context ctx) {
        Constraints cons = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                MailSyncWorker.class, 15, TimeUnit.SECONDS)
                .setConstraints(cons)
                .build();

        WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(
                        "mail_sync",
                        ExistingPeriodicWorkPolicy.KEEP,
                        req
                );
    }
    /** Fuerza un disparo inmediato del worker. */
    public static void forceSyncNow(Context ctx) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(MailSyncWorker.class).build();
        WorkManager.getInstance(ctx).enqueue(req);
    }
}
