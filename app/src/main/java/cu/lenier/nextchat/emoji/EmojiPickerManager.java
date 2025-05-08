package cu.lenier.nextchat.emoji;

import android.app.Activity;
import android.view.View;
import android.widget.EditText;

import com.vanniktech.emoji.EmojiManager;
import com.vanniktech.emoji.EmojiPopup;
import com.vanniktech.emoji.google.GoogleEmojiProvider;

public class EmojiPickerManager {
    private final EmojiPopup emojiPopup;

    public EmojiPickerManager(Activity activity, View rootView, EditText messageInput) {

            EmojiManager.install(new GoogleEmojiProvider());

        // Usar el nuevo constructor directamente
        emojiPopup = new EmojiPopup(rootView, messageInput);
    }

    public void toggle() {
        emojiPopup.toggle();
    }

    public void dismiss() {
        emojiPopup.dismiss();
    }

    public boolean isShowing() {
        return emojiPopup.isShowing();
    }
}
