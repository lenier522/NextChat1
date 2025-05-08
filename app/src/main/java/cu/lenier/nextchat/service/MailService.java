package cu.lenier.nextchat.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.Executors;

import javax.mail.Address;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.event.MessageCountAdapter;
import javax.mail.event.MessageCountEvent;
import javax.mail.search.FlagTerm;
import javax.mail.internet.MimeMessage;

import cu.lenier.nextchat.R;
import cu.lenier.nextchat.config.AppConfig;
import cu.lenier.nextchat.data.AppDatabase;
import cu.lenier.nextchat.data.MessageDao;
import cu.lenier.nextchat.model.Message;
import cu.lenier.nextchat.util.CryptoHelper;

public class MailService extends Service {
    private static final String TAG = "MailService";
    private static final String TXT_SUBJ = "NextChat";
    private static final String AUD_SUBJ = "NextChat Audio";
    private static final String IMG_SUBJ = "NextChat Image";
    private static final String FOLDER_NAME = "NextChat";
    private static final String CH_SYNC = "MailSyncChannel";
    private static final String CH_NEWMSG = "NewMsgChannel";
    private static final int NOTIF_SYNC = 1;

    private MessageDao dao;
    private Session session;
    private volatile boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        dao = AppDatabase.getInstance(this).messageDao();

        Properties props = new Properties();
        props.put("mail.imap.host", "imap.nauta.cu");
        props.put("mail.imap.port", "143");
        props.put("mail.imap.ssl.enable", "false");
        session = Session.getInstance(props);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CH_SYNC, "Sync", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CH_NEWMSG, "Nuevos", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_SYNC)
                .setContentTitle("NextChat")
                .setContentText("Esperando mensajes…")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN);
        startForeground(NOTIF_SYNC, b.build());

        if (!running) {
            running = true;
            Executors.newSingleThreadExecutor().execute(this::imapIdleLoop);
        }
        return START_STICKY;
    }

    private void imapIdleLoop() {
        while (running) {
            IMAPStore store = null;
            IMAPFolder inbox = null;
            IMAPFolder chatFolder = null;
            try {
                String email = getSharedPreferences("prefs", MODE_PRIVATE).getString("email", "");
                String pass = getSharedPreferences("prefs", MODE_PRIVATE).getString("pass", "");

                store = (IMAPStore) session.getStore("imap");
                store.connect("imap.nauta.cu", 143, email, pass);

                // 1) INBOX
                inbox = (IMAPFolder) store.getFolder("INBOX");
                inbox.open(Folder.READ_WRITE);

                // 2) Carpeta NextChat
                chatFolder = (IMAPFolder) store.getFolder(FOLDER_NAME);
                if (!chatFolder.exists()) chatFolder.create(Folder.HOLDS_MESSAGES);
                chatFolder.open(Folder.READ_WRITE);

                // 3) Procesar inicialmente no vistos y moverlos
                FlagTerm ft = new FlagTerm(new Flags(Flags.Flag.SEEN), false);
                for (javax.mail.Message m : inbox.search(ft)) {
                    handleIncoming(m, email);
                    chatFolder.appendMessages(new javax.mail.Message[]{(MimeMessage) m});
                    m.setFlag(Flags.Flag.DELETED, true);
                }
                inbox.expunge();

                // 4) Listener IDLE en INBOX
                IMAPFolder finalChatFolder = chatFolder;
                IMAPFolder finalInbox = inbox;
                inbox.addMessageCountListener(new MessageCountAdapter() {
                    @Override
                    public void messagesAdded(MessageCountEvent ev) {
                        for (javax.mail.Message m : ev.getMessages()) {
                            try {
                                handleIncoming(m, email);
                                finalChatFolder.appendMessages(new javax.mail.Message[]{(MimeMessage) m});
                                m.setFlag(Flags.Flag.DELETED, true);
                            } catch (Exception ignored) {
                            }
                        }
                        try {
                            finalInbox.expunge();
                        } catch (Exception ignored) {
                        }
                    }
                });

                // 5) Esperar IDLE
                while (running && inbox.isOpen()) {
                    try {
                        inbox.idle();
                    } catch (Exception idleEx) {
                        break;
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "imapIdleLoop fallo", e);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                }
            } finally {
                try {
                    if (inbox != null && inbox.isOpen()) inbox.close(false);
                } catch (Exception ignored) {
                }
                try {
                    if (chatFolder != null && chatFolder.isOpen()) chatFolder.close(false);
                } catch (Exception ignored) {
                }
                try {
                    if (store != null && store.isConnected()) store.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void handleIncoming(javax.mail.Message m, String myEmail) {
        try {
            Address[] fromArr = m.getFrom();
            if (fromArr == null || fromArr.length == 0) return;
            String from = fromArr[0].toString();
            if (from.equalsIgnoreCase(myEmail)) return;

            String subj = m.getSubject();
            long ts = m.getReceivedDate().getTime();
            if (!TXT_SUBJ.equals(subj) && !AUD_SUBJ.equals(subj) && !IMG_SUBJ.equals(subj)) return;
            if (dao.countExisting(from, myEmail, subj, ts) > 0) return;

            Message msg = new Message();
            msg.fromAddress = from;
            msg.toAddress = myEmail;
            msg.subject = subj;
            msg.timestamp = ts;
            msg.sent = false;
            msg.read = false;

            Object content = m.getContent();
            if (TXT_SUBJ.equals(subj) && !(content instanceof Multipart)) {
                msg.type = "text";
                msg.body = CryptoHelper.decrypt(content.toString());
            } else {
                Multipart mp = (Multipart) content;
                msg.body = "";
                for (int i = 0; i < mp.getCount(); i++) {
                    Part part = mp.getBodyPart(i);
                    if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
                        File dir = new File(getExternalFilesDir(null),
                                AUD_SUBJ.equals(subj) ? "audios_recibidos" : "images_recibidas");
                        if (!dir.exists()) dir.mkdirs();
                        File enc = new File(dir, System.currentTimeMillis() + "_" + part.getFileName());
                        try (InputStream is = part.getInputStream();
                             FileOutputStream fos = new FileOutputStream(enc)) {
                            byte[] buf = new byte[4096];
                            int r;
                            while ((r = is.read(buf)) > 0) fos.write(buf, 0, r);
                        }
                        File dec = new File(dir,
                                enc.getName().replace(".enc",
                                        AUD_SUBJ.equals(subj) ? ".3gp" : ".jpg"));
                        if (AUD_SUBJ.equals(subj)) {
                            CryptoHelper.decryptAudio(enc, dec);
                        } else {
                            CryptoHelper.decryptFile(enc, dec);
                        }
                        enc.delete();
                        msg.attachmentPath = dec.getAbsolutePath();
                        msg.type = AUD_SUBJ.equals(subj) ? "audio" : "image";
                        break;
                    }
                }
            }

            Executors.newSingleThreadExecutor().execute(() -> dao.insert(msg));

            String curr = AppConfig.getCurrentChat();
            if (curr == null || !curr.equalsIgnoreCase(from.trim())) {
                NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CH_NEWMSG)
                        .setContentTitle("Mensaje de " + from)
                        .setContentText(msg.type.equals("text") ? msg.body : msg.type + " recibido")
                        .setSmallIcon(R.drawable.ic_notification)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT);
                if (Build.VERSION.SDK_INT < 33 ||
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                                == PackageManager.PERMISSION_GRANTED) {
                    NotificationManagerCompat.from(this).notify((int) ts, nb.build());
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "handleIncoming error", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }
}
