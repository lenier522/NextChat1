package cu.lenier.nextchat.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import com.sun.mail.imap.IMAPFolder;

import java.io.File;
import java.util.Properties;
import java.util.concurrent.Executors;

import javax.mail.Folder;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.Multipart;

import cu.lenier.nextchat.data.AppDatabase;
import cu.lenier.nextchat.data.MessageDao;
import cu.lenier.nextchat.model.Message;

public class MailHelper {
    private static final String TAG     = "MailHelper";
    private static final String TXT_SUBJ = "NextChat";
    private static final String AUD_SUBJ = "NextChat Audio";
    private static final String IMG_SUBJ = "NextChat Image";
    private static final String FOLDER   = "NextChat";

    public static void sendEmail(Context ctx, Message m) {
        Executors.newSingleThreadExecutor().execute(() -> {
            MessageDao dao = AppDatabase.getInstance(ctx).messageDao();
            if (m.id == 0) m.id = (int) dao.insert(m);
            else          dao.update(m);

            if (!hasNetwork(ctx)) {
                m.sendState = Message.STATE_FAILED;
                dao.update(m);
                return;
            }

            SharedPreferences prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE);
            String email = prefs.getString("email", "");
            String pass  = prefs.getString("pass", "");

            try {
                // SMTP setup
                Properties props = new Properties();
                props.put("mail.smtp.host", "smtp.nauta.cu");
                props.put("mail.smtp.port", "25");
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.starttls.enable", "false");
                Session session = Session.getInstance(props);

                MimeMessage mime = new MimeMessage(session);
                mime.setFrom(new InternetAddress(email));
                mime.setRecipient(MimeMessage.RecipientType.TO, new InternetAddress(m.toAddress));
                mime.setSubject(TXT_SUBJ);
                mime.setText(CryptoHelper.encrypt(m.body));

                // Send via SMTP
                Transport tr = session.getTransport("smtp");
                tr.connect("smtp.nauta.cu", 25, email, pass);
                tr.sendMessage(mime, mime.getAllRecipients());
                tr.close();

                // Mark as sent immediately
                m.sendState = Message.STATE_SENT;
                dao.update(m);

                // Archive in IMAP asynchronously
                Executors.newSingleThreadExecutor().execute(() -> {
                    try {
                        appendToFolder(session, email, pass, mime);
                    } catch (Exception e) {
                        Log.e(TAG, "Error guardando en IMAP", e);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error enviando texto", e);
                m.sendState = Message.STATE_FAILED;
                dao.update(m);
            }
        });
    }

    public static void sendAudioEmail(Context ctx, Message m) {
        Executors.newSingleThreadExecutor().execute(() -> {
            MessageDao dao = AppDatabase.getInstance(ctx).messageDao();
            if (m.id == 0) m.id = (int) dao.insert(m);
            else          dao.update(m);

            if (!hasNetwork(ctx)) {
                m.sendState = Message.STATE_FAILED;
                dao.update(m);
                return;
            }

            SharedPreferences prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE);
            String email = prefs.getString("email", "");
            String pass  = prefs.getString("pass", "");

            try {
                // Encrypt audio to temp file
                File in  = new File(m.attachmentPath);
                File tmp = File.createTempFile("aud_enc", ".tmp", ctx.getCacheDir());
                CryptoHelper.encryptAudio(in, tmp);

                // SMTP setup
                Properties props = new Properties();
                props.put("mail.smtp.host", "smtp.nauta.cu");
                props.put("mail.smtp.port", "25");
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.starttls.enable", "false");
                Session session = Session.getInstance(props);

                MimeMessage mime = new MimeMessage(session);
                mime.setFrom(new InternetAddress(email));
                mime.setRecipient(MimeMessage.RecipientType.TO, new InternetAddress(m.toAddress));
                mime.setSubject(AUD_SUBJ);

                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText("[Audio]");
                MimeBodyPart filePart = new MimeBodyPart();
                filePart.attachFile(tmp);
                filePart.setFileName("audio.enc");

                Multipart mp = new MimeMultipart();
                mp.addBodyPart(textPart);
                mp.addBodyPart(filePart);
                mime.setContent(mp);

                // Send
                Transport tr = session.getTransport("smtp");
                tr.connect("smtp.nauta.cu", 25, email, pass);
                tr.sendMessage(mime, mime.getAllRecipients());
                tr.close();

                // Mark as sent
                m.sendState = Message.STATE_SENT;
                dao.update(m);

                // Archive in IMAP
                Executors.newSingleThreadExecutor().execute(() -> {
                    try {
                        appendToFolder(session, email, pass, mime);
                    } catch (Exception e) {
                        Log.e(TAG, "Error guardando audio en IMAP", e);
                    }
                });

                tmp.delete();

            } catch (Exception e) {
                Log.e(TAG, "Error enviando audio", e);
                m.sendState = Message.STATE_FAILED;
                dao.update(m);
            }
        });
    }

    public static void sendImageEmail(Context ctx, Message m) {
        Executors.newSingleThreadExecutor().execute(() -> {
            MessageDao dao = AppDatabase.getInstance(ctx).messageDao();
            if (m.id == 0) m.id = (int) dao.insert(m);
            else          dao.update(m);

            if (!hasNetwork(ctx)) {
                m.sendState = Message.STATE_FAILED;
                dao.update(m);
                return;
            }

            SharedPreferences prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE);
            String email = prefs.getString("email", "");
            String pass  = prefs.getString("pass", "");

            try {
                // Encrypt image to temp file
                File in  = new File(m.attachmentPath);
                File tmp = File.createTempFile("img_enc", ".tmp", ctx.getCacheDir());
                CryptoHelper.encryptFile(in, tmp);

                // SMTP setup
                Properties props = new Properties();
                props.put("mail.smtp.host", "smtp.nauta.cu");
                props.put("mail.smtp.port", "25");
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.starttls.enable", "false");
                Session session = Session.getInstance(props);

                MimeMessage mime = new MimeMessage(session);
                mime.setFrom(new InternetAddress(email));
                mime.setRecipient(MimeMessage.RecipientType.TO, new InternetAddress(m.toAddress));
                mime.setSubject(IMG_SUBJ);

                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText("[Image File]");
                MimeBodyPart filePart = new MimeBodyPart();
                filePart.attachFile(tmp);
                filePart.setFileName("image.enc");

                Multipart mp = new MimeMultipart();
                mp.addBodyPart(textPart);
                mp.addBodyPart(filePart);
                mime.setContent(mp);

                // Send
                Transport tr = session.getTransport("smtp");
                tr.connect("smtp.nauta.cu", 25, email, pass);
                tr.sendMessage(mime, mime.getAllRecipients());
                tr.close();

                // Mark as sent
                m.sendState = Message.STATE_SENT;
                dao.update(m);

                // Archive in IMAP
                Executors.newSingleThreadExecutor().execute(() -> {
                    try {
                        appendToFolder(session, email, pass, mime);
                    } catch (Exception e) {
                        Log.e(TAG, "Error guardando imagen en IMAP", e);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error enviando imagen", e);
                m.sendState = Message.STATE_FAILED;
                dao.update(m);
            }
        });
    }

    private static void appendToFolder(Session session, String user, String pass, MimeMessage mime) {
        try {
            Store store = session.getStore("imap");
            store.connect("imap.nauta.cu", 143, user, pass);

            IMAPFolder folder = (IMAPFolder) store.getFolder(FOLDER);
            if (!folder.exists()) folder.create(Folder.HOLDS_MESSAGES);
            folder.open(Folder.READ_WRITE);
            folder.appendMessages(new javax.mail.Message[]{mime});
            folder.close(false);

            store.close();
        } catch (Exception e) {
            Log.e(TAG, "Error guardando en carpeta NextChat", e);
        }
    }

    private static boolean hasNetwork(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }
}
