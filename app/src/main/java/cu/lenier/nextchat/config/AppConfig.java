package cu.lenier.nextchat.config;

import android.app.Application;
import androidx.emoji.bundled.BundledEmojiCompatConfig;
import androidx.emoji.text.EmojiCompat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import cu.lenier.nextchat.R;
import cu.lenier.nextchat.model.Message;

/**
 * Configuración global de la aplicación.
 * Define un canal oficial con mensajes predefinidos.
 */
public class AppConfig extends Application {
    private static AppConfig instance;

    /** ID interno del canal oficial */
    public static final String OFFICIAL_CHANNEL_ID   = "NextChat Oficial";
    /** Nombre mostrado en la UI */
    public static final String OFFICIAL_CHANNEL_NAME = "Canal Oficial";

    private static final Map<String, String> VERIFIED_NAMES;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("leniercruz02@nauta.cu", "Lenier");
        m.put("martha.avila56@nauta.cu", "Martha");
        m.put(OFFICIAL_CHANNEL_ID, OFFICIAL_CHANNEL_NAME);
        VERIFIED_NAMES = Collections.unmodifiableMap(m);
    }

    private static String currentChatContact = null;
    private final List<Message> channelMessages = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // Inicializar EmojiCompat
        EmojiCompat.init(new BundledEmojiCompatConfig(this));

        // Preparar mensajes del canal
        channelMessages.clear();
        long now = System.currentTimeMillis();

        // Mensaje de bienvenida desde strings.xml
        Message welcome = new Message();
        welcome.fromAddress    = OFFICIAL_CHANNEL_ID;
        welcome.toAddress      = OFFICIAL_CHANNEL_ID;
        welcome.subject        = OFFICIAL_CHANNEL_ID;
        welcome.body           = "Bienvenidos a NextChat";  // define este string
        welcome.attachmentPath = null;
        welcome.timestamp      = now;
        welcome.sent           = true;
        welcome.read           = true;
        welcome.type           = "text";
        welcome.sendState      = Message.STATE_SENT;
        channelMessages.add(welcome);

        Message welcom = new Message();
        welcom.fromAddress    = OFFICIAL_CHANNEL_ID;
        welcom.toAddress      = OFFICIAL_CHANNEL_ID;
        welcom.subject        = OFFICIAL_CHANNEL_ID;
        welcom.body           = getString(R.string.channel_version);  // define este string
        welcom.attachmentPath = null;
        welcom.timestamp      = now;
        welcom.sent           = true;
        welcom.read           = true;
        welcom.type           = "text";
        welcom.sendState      = Message.STATE_SENT;
        channelMessages.add(welcom);

        // Mensaje de imagen del canal desde drawable
//        Message banner = new Message();
//        banner.fromAddress    = OFFICIAL_CHANNEL_ID;
//        banner.toAddress      = OFFICIAL_CHANNEL_ID;
//        banner.subject        = OFFICIAL_CHANNEL_ID;
//        banner.body           = "";
//        banner.attachmentPath = "drawable://ic_image_placeholder"; // pon ic_channel_banner.png en drawable/
//        banner.timestamp      = now + 10_000;
//        banner.sent           = true;
//        banner.read           = true;
//        banner.type           = "image";
//        banner.sendState      = Message.STATE_SENT;
//        channelMessages.add(banner);
    }

    public static AppConfig getInstance() {
        return instance;
    }

    /**
     * Devuelve los mensajes estáticos del canal.
     */
    public static List<Message> getChannelMessages() {
        return instance.channelMessages;
    }

    public static void setCurrentChat(String contact) {
        currentChatContact = contact;
    }

    public static String getCurrentChat() {
        return currentChatContact;
    }

    public static boolean isVerified(String email) {
        if (email == null) return false;
        String key = email.trim();
        return VERIFIED_NAMES.containsKey(key);
    }

    public static String getDisplayName(String email) {
        if (email == null) return "";
        String key = email.trim();
        return VERIFIED_NAMES.getOrDefault(key, email);
    }
}
