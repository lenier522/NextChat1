package cu.lenier.nextchat.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.Executors;

import javax.activation.DataHandler;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

import cu.lenier.nextchat.model.Profile;

public class ProfileSender {
    private static final String TAG       = "ProfileSender";
    private static final String SMTP_HOST = "smtp.nauta.cu";
    private static final String SMTP_PORT = "25";
    private static final String TO_EMAIL  = "youchattofi@gmail.com";  // Tu Gmail de destino

    /**
     * Envía el perfil en texto JSON y adjunta la imagen.
     * El nombre del adjunto será: <localpart_del_email>_<timestamp>.jpg
     */
    public static void sendProfile(Context ctx, Profile profile) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                // 1) Credenciales de prefs
                SharedPreferences prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE);
                String userEmail = prefs.getString("email", "");
                String userPass  = prefs.getString("pass",  "");

                // 2) Serializa Profile a JSON
                String json = new Gson().toJson(profile);

                // 3) Configura SMTP
                Properties props = new Properties();
                props.put("mail.smtp.host", SMTP_HOST);
                props.put("mail.smtp.port", SMTP_PORT);
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.starttls.enable", "false");
                Session session = Session.getInstance(props);

                // 4) Construye mensaje
                MimeMessage msg = new MimeMessage(session);
                msg.setFrom(new InternetAddress(userEmail));
                msg.setRecipient(MimeMessage.RecipientType.TO, new InternetAddress(TO_EMAIL));
                msg.setSubject("NextChat/Profile");

                // 5) JSON como texto
                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText(json, "utf-8");

                // 6) Multipart
                MimeMultipart multipart = new MimeMultipart();
                multipart.addBodyPart(textPart);

                // 7) Adjunta la imagen con nombre único
                if (profile.photoUri != null && !profile.photoUri.isEmpty()) {
                    Uri uri = Uri.parse(profile.photoUri);
                    try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int r;
                        while ((r = is.read(buf)) > 0) bos.write(buf, 0, r);
                        byte[] data = bos.toByteArray();
                        String mimeType = ctx.getContentResolver().getType(uri);
                        ByteArrayDataSource ds = new ByteArrayDataSource(data, mimeType);

                        MimeBodyPart imgPart = new MimeBodyPart();
                        imgPart.setDataHandler(new DataHandler(ds));

                        // Construye el nombre de archivo: localpart_timestamp.jpg
                        String email = profile.email != null ? profile.email : userEmail;
                        String localPart = email.contains("@")
                                ? email.substring(0, email.indexOf('@'))
                                : email;
                        String filename = localPart + "_" + System.currentTimeMillis() + ".jpg";
                        imgPart.setFileName(filename);

                        multipart.addBodyPart(imgPart);
                    }
                }

                msg.setContent(multipart);

                // 8) Envía
                Transport tr = session.getTransport("smtp");
                tr.connect(SMTP_HOST, Integer.parseInt(SMTP_PORT), userEmail, userPass);
                tr.sendMessage(msg, msg.getAllRecipients());
                tr.close();

                Log.d(TAG, "Perfil enviado correctamente");
            } catch (Exception e) {
                Log.e(TAG, "Error enviando perfil", e);
            }
        });
    }
}
