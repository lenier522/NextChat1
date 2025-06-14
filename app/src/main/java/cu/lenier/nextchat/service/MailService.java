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

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.Executors;

import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Transport;
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
            Executors.newSingleThreadExecutor().execute(this::listenPersonalIMAP);
        }
        return START_STICKY;
    }

    private void listenPersonalIMAP() {
        while (running) {
            IMAPStore store = null;
            IMAPFolder inbox = null;
            IMAPFolder chatFolder = null;
            try {
                String email = getSharedPreferences("prefs", MODE_PRIVATE).getString("email", "");
                String pass = getSharedPreferences("prefs", MODE_PRIVATE).getString("pass", "");

                store = (IMAPStore) session.getStore("imap");
                store.connect("imap.nauta.cu", 143, email, pass);

                inbox = (IMAPFolder) store.getFolder("INBOX");
                inbox.open(Folder.READ_WRITE);

                chatFolder = (IMAPFolder) store.getFolder(FOLDER_NAME);
                if (!chatFolder.exists()) chatFolder.create(Folder.HOLDS_MESSAGES);
                chatFolder.open(Folder.READ_WRITE);

                // Procesar mensajes no vistos
                FlagTerm ft = new FlagTerm(new Flags(Flags.Flag.SEEN), false);
                for (javax.mail.Message mm : inbox.search(ft)) {
                    handleIncoming(mm, email);
                    chatFolder.appendMessages(new javax.mail.Message[]{(MimeMessage) mm});
                    mm.setFlag(Flags.Flag.DELETED, true);
                }
                inbox.expunge();

                // Listener IDLE
                IMAPFolder finalChatFolder = chatFolder;
                IMAPFolder finalInbox = inbox;
                inbox.addMessageCountListener(new MessageCountAdapter() {
                    @Override
                    public void messagesAdded(MessageCountEvent ev) {
                        for (javax.mail.Message mm : ev.getMessages()) {
                            try {
                                handleIncoming(mm, email);
                                finalChatFolder.appendMessages(new javax.mail.Message[]{(MimeMessage) mm});
                                mm.setFlag(Flags.Flag.DELETED, true);
                            } catch (Exception ignored) {}
                        }
                        try { finalInbox.expunge(); } catch (Exception ignored) {}
                    }
                });

                // Loop IDLE
                while (running && inbox.isOpen()) {
                    try {
                        inbox.idle();
                    } catch (Exception idleEx) {
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "imapIdleLoop fallo, durmiendo 5s y reintentando", e);
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            } finally {
                try { if (inbox != null && inbox.isOpen()) inbox.close(false); } catch (Exception ignored) {}
                try { if (chatFolder != null && chatFolder.isOpen()) chatFolder.close(false); } catch (Exception ignored) {}
                try { if (store != null && store.isConnected()) store.close(); } catch (Exception ignored) {}
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
            String jsonCipher = null;
            File encFile = null;
            if (content instanceof String) {
                jsonCipher = (String) content;
            } else if (content instanceof Multipart) {
                Multipart mp = (Multipart) content;
                BodyPart part0 = mp.getBodyPart(0);
                if (part0.getContentType().toLowerCase().startsWith("text/plain")) {
                    jsonCipher = part0.getContent().toString();
                }
                if (mp.getCount() > 1) {
                    BodyPart part1 = mp.getBodyPart(1);
                    File baseDir = new File(getExternalFilesDir(null),
                            subj.equals(AUD_SUBJ) ? "audios_recibidos" : "images_recibidas");
                    if (!baseDir.exists()) baseDir.mkdirs();
                    File tmpEnc = new File(baseDir, System.currentTimeMillis() + "_" + part1.getFileName());
                    try (InputStream is = part1.getInputStream();
                         FileOutputStream fos = new FileOutputStream(tmpEnc)) {
                        byte[] buf = new byte[4096]; int r;
                        while ((r = is.read(buf)) > 0) fos.write(buf, 0, r);
                    }
                    encFile = tmpEnc;
                }
            } else {
                return;
            }
            if (jsonCipher == null) return;

            String jsonPlain;
            try { jsonPlain = CryptoHelper.decrypt(jsonCipher); }
            catch (Exception ex) { Log.e(TAG, "No se pudo descifrar JSON entrante", ex); return; }

            JSONObject jsonMsg = new JSONObject(jsonPlain);
            String type = jsonMsg.getString("type");
            msg.type = type;
            if ("text".equals(type)) {
                msg.body = CryptoHelper.decrypt(jsonMsg.getString("body"));
            } else {
                msg.body = "";
            }

            if ("audio".equals(type) && encFile != null) {
                File decFile = new File(encFile.getParentFile(),
                        encFile.getName().replace(".tmp", ".3gp"));
                CryptoHelper.decryptAudio(encFile, decFile);
                encFile.delete();
                msg.attachmentPath = decFile.exists() ? decFile.getAbsolutePath() : null;
            } else if ("image".equals(type) && encFile != null) {
                File decFile = new File(encFile.getParentFile(),
                        encFile.getName().replace(".tmp", ".jpg"));
                CryptoHelper.decryptFile(encFile, decFile);
                encFile.delete();
                msg.attachmentPath = decFile.exists() ? decFile.getAbsolutePath() : null;
            } else {
                msg.attachmentPath = null;
            }

            JSONObject inRep = jsonMsg.getJSONObject("inReplyTo");
            int parentId = inRep.getInt("parentId");
            if (parentId > 0) {
                msg.inReplyToId = parentId;
                msg.inReplyToType = inRep.optString("parentType", null);
                msg.inReplyToBody = inRep.optString("parentBody", null);
            }
            if (!jsonMsg.isNull("messageId")) {
                msg.messageId = jsonMsg.getString("messageId");
            }

            dao.insert(msg);

            String curr = AppConfig.getCurrentChat();
            if (curr == null || !curr.equalsIgnoreCase(from.trim())) {
                String textoNotif = "text".equals(type) ? msg.body : type + " recibido";
                NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CH_NEWMSG)
                        .setContentTitle("Mensaje de " + from)
                        .setContentText(textoNotif)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT);
                if (Build.VERSION.SDK_INT < 33 ||
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                                == PackageManager.PERMISSION_GRANTED) {
                    NotificationManagerCompat.from(this).notify((int) msg.timestamp, nb.build());
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
