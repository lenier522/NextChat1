package cu.lenier.nextchat.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import com.sun.mail.imap.IMAPFolder;

import org.json.JSONException;
import org.json.JSONObject;

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

import cu.lenier.nextchat.config.AppConfig;
import cu.lenier.nextchat.data.AppDatabase;
import cu.lenier.nextchat.data.MessageDao;
import cu.lenier.nextchat.model.Message;
import cu.lenier.nextchat.ui.ChatActivity;

public class MailHelper {
    private static final String TAG = "MailHelper";
    private static final String TXT_SUBJ = "NextChat";
    private static final String AUD_SUBJ = "NextChat Audio";
    private static final String IMG_SUBJ = "NextChat Image";
    private static final String FOLDER = "NextChat";

    // Envío de texto
    public static void sendEmail(Context ctx, Message m) {
        Executors.newSingleThreadExecutor().execute(() -> {
            MessageDao dao = AppDatabase.getInstance(ctx).messageDao();
            if (m.id == 0) m.id = (int) dao.insert(m);
            else dao.update(m);

            if (!hasNetwork(ctx)) {
                m.sendState = Message.STATE_FAILED;
                dao.update(m);
                return;
            }

            SharedPreferences prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE);
            String email = prefs.getString("email", "");
            String pass = prefs.getString("pass", "");

            try {
                // ── Paso 1: armar JSON completo ──────────────────────────────────
                JSONObject jsonMsg = new JSONObject();
                jsonMsg.put("messageId", m.messageId == null ? JSONObject.NULL : m.messageId);
                jsonMsg.put("fromAddress", m.fromAddress);
                jsonMsg.put("toAddress",   m.toAddress);
                jsonMsg.put("subject",     TXT_SUBJ);
                jsonMsg.put("timestamp",   m.timestamp);
                jsonMsg.put("sent",        true);
                jsonMsg.put("read",        true);
                jsonMsg.put("type",        "text");
                jsonMsg.put("sendState",   m.sendState);

                // 1.a) campo body cifrado.
                String cuerpoEncriptado = CryptoHelper.encrypt(m.body);
                jsonMsg.put("body", cuerpoEncriptado);

                // 1.b) no hay attachment en texto
                jsonMsg.put("attachment", JSONObject.NULL);

                // 1.c) inReplyTo
                JSONObject inRep = new JSONObject();
                if (m.inReplyToId > 0) {
                    inRep.put("parentId",   m.inReplyToId);
                    inRep.put("parentType", m.inReplyToType);
                    String snippet = (m.inReplyToBody != null ? m.inReplyToBody : "");
                    if (snippet.length() > 30) snippet = snippet.substring(0,30) + "…";
                    inRep.put("parentBody", snippet);
                } else {
                    inRep.put("parentId",   0);
                    inRep.put("parentType", JSONObject.NULL);
                    inRep.put("parentBody", JSONObject.NULL);
                }
                jsonMsg.put("inReplyTo", inRep);

                // ── Paso 2: cifrar todo el JSON ─────────────────────────────────
                String jsonPlain  = jsonMsg.toString();
                String jsonCipher = CryptoHelper.encrypt(jsonPlain);

                // ── Paso 3: crear MimeMessage y setear el JSON cifrado ───────────
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

                // Opcional: headers In-Reply-To (se mantienen para compatibilidad)
                if (m.inReplyToId > 0) {
                    Message parent = dao.findById((int) m.inReplyToId);
                    if (parent != null && parent.messageId != null) {
                        mime.setHeader("In-Reply-To", parent.messageId);
                        mime.setHeader("References", parent.messageId);
                    }
                }

                // Aquí metemos el JSON cifrado como único contenido de texto
                mime.setText(jsonCipher, "utf-8", "plain");
                mime.saveChanges();

                // ── Paso 4: enviar y actualizar BD ────────────────────────────────
                Transport tr = session.getTransport("smtp");
                tr.connect("smtp.nauta.cu", 25, email, pass);
                tr.sendMessage(mime, mime.getAllRecipients());
                tr.close();

                // Capturar Message-ID real del servidor
                String[] hdr = mime.getHeader("Message-ID");
                if (hdr != null && hdr.length > 0) {
                    dao.updateMessageId(m.id, hdr[0]);
                }
                m.sendState = Message.STATE_SENT;
                dao.update(m);

                // Archivar en IMAP en segundo hilo
                Executors.newSingleThreadExecutor().execute(() -> {
                    try {
                        appendToFolder(session, email, pass, mime);
                    } catch (Exception ex) {
                        Log.e(TAG, "Error archiving in IMAP", ex);
                    }
                });

            } catch (JSONException je) {
                Log.e(TAG, "Error construyendo JSON", je);
                m.sendState = Message.STATE_FAILED;
                dao.update(m);
            } catch (Exception e) {
                Log.e(TAG, "Error sending text", e);
                m.sendState = Message.STATE_FAILED;
                dao.update(m);
            }
        });
    }



    // Envío de audio
    public static void sendAudioEmail(Context ctx, Message m) {
        Executors.newSingleThreadExecutor().execute(() -> {
            MessageDao dao = AppDatabase.getInstance(ctx).messageDao();
            if (m.id == 0) m.id = (int) dao.insert(m);
            else dao.update(m);

            if (!hasNetwork(ctx)) {
                m.sendState = Message.STATE_FAILED;
                dao.update(m);
                return;
            }
            SharedPreferences prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE);
            String email = prefs.getString("email", "");
            String pass = prefs.getString("pass", "");

            try {
                // ── 0) encriptar el fichero de audio ─────────────────────────────
                File in    = new File(m.attachmentPath);
                File tmpEnc = File.createTempFile("aud_enc", ".tmp", ctx.getCacheDir());
                CryptoHelper.encryptAudio(in, tmpEnc);

                // ── 1) construir JSON completo ────────────────────────────────────
                JSONObject jsonMsg = new JSONObject();
                jsonMsg.put("messageId", m.messageId == null ? JSONObject.NULL : m.messageId);
                jsonMsg.put("fromAddress", m.fromAddress);
                jsonMsg.put("toAddress",   m.toAddress);
                jsonMsg.put("subject",     AUD_SUBJ);
                jsonMsg.put("timestamp",   m.timestamp);
                jsonMsg.put("sent",        true);
                jsonMsg.put("read",        true);
                jsonMsg.put("type",        "audio");
                jsonMsg.put("sendState",   m.sendState);

                // audio no lleva texto, body vacío
                jsonMsg.put("body", "");

                // attachment: indicamos nombre del .tmp cifrado + mimeType
                JSONObject att = new JSONObject();
                att.put("fileName", tmpEnc.getName());
                att.put("mimeType", "audio/3gp");
                att.put("encrypted", true);
                jsonMsg.put("attachment", att);

                // inReplyTo
                JSONObject inRep = new JSONObject();
                if (m.inReplyToId > 0) {
                    inRep.put("parentId",   m.inReplyToId);
                    inRep.put("parentType", m.inReplyToType);
                    String snippet = (m.inReplyToBody != null ? m.inReplyToBody : "");
                    if (snippet.length() > 30) snippet = snippet.substring(0,30) + "…";
                    inRep.put("parentBody", snippet);
                } else {
                    inRep.put("parentId",   0);
                    inRep.put("parentType", JSONObject.NULL);
                    inRep.put("parentBody", JSONObject.NULL);
                }
                jsonMsg.put("inReplyTo", inRep);

                // ── 2) cifrar TODO el JSON ────────────────────────────────────────
                String jsonPlain  = jsonMsg.toString();
                String jsonCipher = CryptoHelper.encrypt(jsonPlain);

                // ── 3) armar correo multipart ────────────────────────────────────
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

                // headers In-Reply-To (opcional)
                if (m.inReplyToId > 0) {
                    Message parent = dao.findById((int) m.inReplyToId);
                    if (parent != null && parent.messageId != null) {
                        mime.setHeader("In-Reply-To", parent.messageId);
                        mime.setHeader("References", parent.messageId);
                    }
                }

                // ─ 3.a) primera parte: JSON cifrado como texto ──────────────────
                MimeBodyPart jsonPart = new MimeBodyPart();
                jsonPart.setText(jsonCipher, "utf-8", "plain");

                // ─ 3.b) segunda parte: adjunto cifrado (tmpEnc) ────────────────
                MimeBodyPart filePart = new MimeBodyPart();
                filePart.attachFile(tmpEnc);
                filePart.setFileName(tmpEnc.getName());
                filePart.setHeader("Content-Type", "application/octet-stream");

                Multipart mp = new MimeMultipart();
                mp.addBodyPart(jsonPart);
                mp.addBodyPart(filePart);
                mime.setContent(mp);
                mime.saveChanges();

                // ── 4) enviar y actualizar BD ───────────────────────────────────
                Transport tr = session.getTransport("smtp");
                tr.connect("smtp.nauta.cu", 25, email, pass);
                tr.sendMessage(mime, mime.getAllRecipients());
                tr.close();

                // capturar Message-ID
                String[] hdr = mime.getHeader("Message-ID");
                if (hdr != null && hdr.length > 0) {
                    dao.updateMessageId(m.id, hdr[0]);
                }
                m.sendState = Message.STATE_SENT;
                dao.update(m);

                // archivar en IMAP (segundo hilo)
                Executors.newSingleThreadExecutor().execute(() -> {
                    try {
                        appendToFolder(session, email, pass, mime);
                    } catch (Exception ex) {
                        Log.e(TAG, "Error archiving audio", ex);
                    }
                });

                // borrar temporales
                tmpEnc.delete();

            } catch (JSONException je) {
                Log.e(TAG, "Error construyendo JSON audio", je);
                m.sendState = Message.STATE_FAILED;
                dao.update(m);
            } catch (Exception e) {
                Log.e(TAG, "Error sending audio", e);
                m.sendState = Message.STATE_FAILED;
                dao.update(m);
            }
        });
    }



    // Envío de imagen
    public static void sendImageEmail(Context ctx, Message m) {
        Executors.newSingleThreadExecutor().execute(() -> {
            MessageDao dao = AppDatabase.getInstance(ctx).messageDao();
            if (m.id == 0) m.id = (int) dao.insert(m);
            else dao.update(m);

            if (!hasNetwork(ctx)) {
                m.sendState = Message.STATE_FAILED;
                dao.update(m);
                return;
            }

            SharedPreferences prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE);
            String email = prefs.getString("email", "");
            String pass = prefs.getString("pass", "");

            try {
                // 0) Encriptar fichero de imagen
                File in = new File(m.attachmentPath);
                File tmp = File.createTempFile("img_enc", ".tmp", ctx.getCacheDir());
                CryptoHelper.encryptFile(in, tmp);

                // 1) Construir JSON
                JSONObject jsonMsg = new JSONObject();
                jsonMsg.put("messageId", m.messageId == null ? JSONObject.NULL : m.messageId);
                jsonMsg.put("fromAddress", m.fromAddress);
                jsonMsg.put("toAddress",   m.toAddress);
                jsonMsg.put("subject",     IMG_SUBJ); // “NextChat Image”
                jsonMsg.put("timestamp",   m.timestamp);
                jsonMsg.put("sent",        true);
                jsonMsg.put("read",        true);
                jsonMsg.put("type",        "image");
                jsonMsg.put("sendState",   m.sendState);
                // 1.a) Body para imágenes = texto vacío
                jsonMsg.put("body", "");

                // 1.b) Attachment para imagen cifrada
                JSONObject att = new JSONObject();
                att.put("fileName", tmp.getName());       // "img_enc123456.tmp"
                att.put("mimeType", "image/jpeg");
                att.put("encrypted", true);
                jsonMsg.put("attachment", att);

                // 1.c) In-Reply-To
                JSONObject inRep = new JSONObject();
                if (m.inReplyToId > 0) {
                    inRep.put("parentId",   m.inReplyToId);
                    inRep.put("parentType", m.inReplyToType);
                    String snippet = (m.inReplyToBody != null ? m.inReplyToBody : "");
                    if (snippet.length() > 30) snippet = snippet.substring(0,30) + "…";
                    inRep.put("parentBody", snippet);
                } else {
                    inRep.put("parentId",   0);
                    inRep.put("parentType", JSONObject.NULL);
                    inRep.put("parentBody", JSONObject.NULL);
                }
                jsonMsg.put("inReplyTo", inRep);


                // ── 2) cifrar TODO el JSON ────────────────────────────────────────
                String jsonPlain  = jsonMsg.toString();
                String jsonCipher = CryptoHelper.encrypt(jsonPlain);

                // 2) Crear Multipart: parte JSON + parte adjunto
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

                // 2.a) Headers In-Reply-To
                if (m.inReplyToId > 0) {
                    Message parent = dao.findById((int) m.inReplyToId);
                    if (parent != null && parent.messageId != null) {
                        mime.setHeader("In-Reply-To", parent.messageId);
                        mime.setHeader("References", parent.messageId);
                    }
                }

                // 2.b) Partes
                // ─ 3.a) primera parte: JSON cifrado como texto ──────────────────
                MimeBodyPart jsonPart = new MimeBodyPart();
                jsonPart.setText(jsonCipher, "utf-8", "plain");

                // ─ 3.b) segunda parte: adjunto cifrado (tmpEnc) ────────────────
                MimeBodyPart filePart = new MimeBodyPart();
                filePart.attachFile(tmp);
                filePart.setFileName(tmp.getName());
                filePart.setHeader("Content-Type", "application/octet-stream");

                Multipart mp = new MimeMultipart();
                mp.addBodyPart(jsonPart);
                mp.addBodyPart(filePart);
                mime.setContent(mp);

                mime.saveChanges();
                Transport tr = session.getTransport("smtp");
                tr.connect("smtp.nauta.cu", 25, email, pass);
                tr.sendMessage(mime, mime.getAllRecipients());
                tr.close();

                // Capturar Message-ID y actualizar
                String[] hdr = mime.getHeader("Message-ID");
                if (hdr != null && hdr.length > 0) {
                    dao.updateMessageId(m.id, hdr[0]);
                }
                m.sendState = Message.STATE_SENT;
                dao.update(m);

                Executors.newSingleThreadExecutor().execute(() -> {
                    try {
                        appendToFolder(session, email, pass, mime);
                    } catch (Exception e) {
                        Log.e(TAG, "Error archiving image", e);
                    }
                });

                tmp.delete();

            } catch (JSONException ex) {
                Log.e(TAG, "Error construyendo JSON imagen", ex);
                m.sendState = Message.STATE_FAILED;
                dao.update(m);
            } catch (Exception e) {
                Log.e(TAG, "Error sending image", e);
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
            Log.e(TAG, "Error archiving in IMAP", e);
        }
    }

    private static boolean hasNetwork(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }


}
