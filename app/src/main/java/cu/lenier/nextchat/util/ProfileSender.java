package cu.lenier.nextchat.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

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
    private static final String TAG            = "ProfileSender";
    private static final String SMTP_HOST      = "smtp.nauta.cu";
    private static final String SMTP_PORT      = "25";
    private static final String TO_EMAIL       = "youchattofi@gmail.com";

    public static final String ACTION_PROFILE_SENT   = "cu.lenier.nextchat.PROFILE_SENT";
    public static final String ACTION_PROFILE_FAILED = "cu.lenier.nextchat.PROFILE_FAILED";

    /**
     * Envía el perfil en texto JSON (cuerpo del email) y adjunta la imagen real.
     * Asunto: "NextChat/Profile"
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

                // 4) Construye el mensaje
                MimeMessage msg = new MimeMessage(session);
                msg.setFrom(new InternetAddress(userEmail));
                msg.setRecipient(MimeMessage.RecipientType.TO, new InternetAddress(TO_EMAIL));
                msg.setSubject("NextChat/Profile");

                // 5) Parte JSON como texto
                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText(json, "utf-8");

                // 6) Multipart contenedor
                MimeMultipart multipart = new MimeMultipart();
                multipart.addBodyPart(textPart);

                // 7) Adjunta la imagen reducida
                if (profile.photoUri != null && !profile.photoUri.isEmpty()) {
                    Uri uri = Uri.parse(profile.photoUri);

                    // Leer el stream original
                    try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                        Bitmap original = BitmapFactory.decodeStream(is);

                        // ---- OPCIÓN 1: pequeño (200x200) ----
//                        Bitmap bmp = Bitmap.createScaledBitmap(original, 200, 200, true);

                        // ---- OPCIÓN 2: mediano (600x600) ----
                        Bitmap bmp = Bitmap.createScaledBitmap(original, 600, 600, true);

                        // ---- OPCIÓN 3: grande (1200x1200) ----
//                        Bitmap bmp = Bitmap.createScaledBitmap(original, 1200, 1200, true);

                        // Convertir a byte[]
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos);
                        byte[] data = bos.toByteArray();

                        String mimeType = "image/jpeg";
                        ByteArrayDataSource ds = new ByteArrayDataSource(data, mimeType);

                        MimeBodyPart imgPart = new MimeBodyPart();
                        imgPart.setDataHandler(new DataHandler(ds));

                        // Construye el nombre: localpart_timestamp.jpg
                        String email = profile.email != null ? profile.email : userEmail;
                        String localPart = email.contains("@")
                                ? email.substring(0, email.indexOf('@'))
                                : email;
                        String filename = localPart + "_" + System.currentTimeMillis() + ".jpg";
                        imgPart.setFileName(filename);
                        imgPart.setDisposition(MimeBodyPart.ATTACHMENT);

                        multipart.addBodyPart(imgPart);
                    }
                }

                msg.setContent(multipart);

                // 8) Envío
                Transport tr = session.getTransport("smtp");
                tr.connect(SMTP_HOST, Integer.parseInt(SMTP_PORT), userEmail, userPass);
                tr.sendMessage(msg, msg.getAllRecipients());
                tr.close();

                Log.d(TAG, "Perfil enviado correctamente");
                LocalBroadcastManager.getInstance(ctx)
                        .sendBroadcast(new Intent(ACTION_PROFILE_SENT));

            } catch (Exception e) {
                Log.e(TAG, "Error enviando perfil", e);
                LocalBroadcastManager.getInstance(ctx)
                        .sendBroadcast(new Intent(ACTION_PROFILE_FAILED));
            }
        });
    }
}
