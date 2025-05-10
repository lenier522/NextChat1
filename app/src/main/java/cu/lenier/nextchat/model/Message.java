package cu.lenier.nextchat.model;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "messages",
        indices = @Index(
                value = {"fromAddress", "toAddress", "subject", "timestamp"},
                unique = true
        )
)
public class Message {
    public static final int STATE_FAILED  = 0;
    public static final int STATE_PENDING = 1;
    public static final int STATE_SENT    = 2;

    @PrimaryKey(autoGenerate = true) public int id;

    public String fromAddress;
    public String toAddress;
    public String subject;
    public String body;
    public String attachmentPath;
    public long   timestamp;
    public boolean sent;      // true = este es tu mensaje
    public boolean read;
    public String  type;      // "text" o "audio" o "imagen"
    public int     sendState; // 0=failed,1=pending,2=sent
    public long   inReplyToId;      // ID del mensaje original
    public String inReplyToBody;    // Texto del mensaje original
}
