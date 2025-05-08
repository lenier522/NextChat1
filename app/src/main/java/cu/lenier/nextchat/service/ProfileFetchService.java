// src/main/java/cu/lenier/nextchat/service/ProfileFetchService.java
package cu.lenier.nextchat.service;

import android.app.IntentService;
import android.content.Intent;
import android.os.Environment;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import javax.mail.BodyPart;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.search.SubjectTerm;
import javax.mail.internet.InternetAddress;
import javax.mail.Address;

import cu.lenier.nextchat.data.AppDatabase;
import cu.lenier.nextchat.model.Profile;

public class ProfileFetchService extends IntentService {
    public static final String ACTION_PROFILES_SYNCED = "cu.lenier.nextchat.PROFILES_SYNCED";
    public static final String EXTRA_COUNT           = "profile_count";

    private static final String TAG           = "ProfileFetchService";
    private static final String IMAP_EMAIL    = "youchattofi@gmail.com";
    private static final String IMAP_PASSWORD = "stclcxzcmqoorctw";
    private static final String IMAP_HOST     = "imap.gmail.com";
    private static final String IMAP_PORT     = "993";
    private static final String TO_SUBJECT    = "NextChat/Profile";

    public ProfileFetchService() {
        super("ProfileFetchService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        AtomicInteger countInsertedOrUpdated = new AtomicInteger();
        try {
            // 1) Configura IMAP
            Properties props = new Properties();
            props.put("mail.imap.host", IMAP_HOST);
            props.put("mail.imap.port", IMAP_PORT);
            props.put("mail.imap.ssl.enable", "true");
            Session session = Session.getInstance(props);

            // 2) Conecta
            Store store = session.getStore("imap");
            store.connect(IMAP_HOST, IMAP_EMAIL, IMAP_PASSWORD);

            // 3) Abre INBOX y filtra por asunto
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            Message[] all = inbox.search(new SubjectTerm(TO_SUBJECT));

            // 4) Solo el más reciente por remitente
            Map<String, Message> latest = new HashMap<>();
            for (Message m : all) {
                Address[] from = m.getFrom();
                if (from == null||from.length==0) continue;
                String email = ((InternetAddress)from[0]).getAddress().toLowerCase();
                Message prev = latest.get(email);
                if (prev == null || m.getReceivedDate().after(prev.getReceivedDate()))
                    latest.put(email, m);
            }

            // 5) Procesa cada mensaje filtrado
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            Gson gson = new Gson();

            for (Message m : latest.values()) {
                try {
                    // extrae JSON + ruta imagen
                    String json = null, imagePath = null;
                    Object c = m.getContent();
                    if (c instanceof Multipart) {
                        Multipart mp = (Multipart)c;
                        for (int i=0;i<mp.getCount();i++){
                            BodyPart bp = mp.getBodyPart(i);
                            String disp = bp.getDisposition();
                            if (disp==null && bp.isMimeType("text/plain")) {
                                json = bp.getContent().toString();
                            } else if (disp!=null && disp.equalsIgnoreCase(BodyPart.ATTACHMENT)) {
                                String fn = bp.getFileName();
                                File dir = new File(
                                        getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                                        "profile_images"
                                );
                                if (!dir.exists()) dir.mkdirs();
                                File out = new File(dir, fn);
                                if (out.exists()) out.delete();
                                try (InputStream is = bp.getInputStream();
                                     FileOutputStream fos = new FileOutputStream(out)) {
                                    byte[] buf = new byte[4096]; int r;
                                    while ((r=is.read(buf))>0) fos.write(buf,0,r);
                                }
                                imagePath = out.getAbsolutePath();
                            }
                        }
                    } else {
                        json = c.toString();
                    }
                    if (json==null) continue;

                    // parsea
                    Profile p = gson.fromJson(json, Profile.class);
                    p.photoUri = imagePath;
                    String keyEmail = p.email.trim().toLowerCase();

                    // busca en BD
                    Profile existing = db.profileDao().getByEmailSync(keyEmail);
                    if (existing == null) {
                        // no existía: inserta
                        db.profileDao().insert(p);
                        countInsertedOrUpdated.incrementAndGet();
                        Log.d(TAG,"Insertado nuevo perfil: "+keyEmail);
                    } else {
                        // existía: compara campos
                        boolean changed = false;
                        if (!p.name.equals(existing.name)) { existing.name = p.name; changed=true; }
                        if (!p.phone.equals(existing.phone)) { existing.phone = p.phone; changed=true; }
                        if (!p.info.equals(existing.info)) { existing.info = p.info; changed=true; }
                        if (!p.gender.equals(existing.gender)) { existing.gender = p.gender; changed=true; }
                        if (!p.birthDate.equals(existing.birthDate)) { existing.birthDate=p.birthDate; changed=true; }
                        if (!p.country.equals(existing.country)) { existing.country=p.country; changed=true; }
                        if (!p.province.equals(existing.province)) { existing.province=p.province; changed=true; }
                        if (p.photoUri != null && !p.photoUri.equals(existing.photoUri)) {
                            existing.photoUri = p.photoUri; changed=true;
                        }
                        if (changed) {
                            db.profileDao().update(existing);
                            countInsertedOrUpdated.incrementAndGet();
                            Log.d(TAG,"Actualizado perfil: "+keyEmail);
                        }
                    }

                } catch(Exception ex){
                    Log.e(TAG,"Error procesando perfil",ex);
                }
            }

            inbox.close(false);
            store.close();

        } catch (Exception e) {
            Log.e(TAG,"Error fetch perfiles",e);
        } finally {
            int total = countInsertedOrUpdated.get();
            Log.d(TAG,"Total perfiles insertados/actualizados: "+total);
            Intent b = new Intent(ACTION_PROFILES_SYNCED);
            b.putExtra(EXTRA_COUNT,total);
            LocalBroadcastManager.getInstance(this).sendBroadcast(b);
        }
    }
}