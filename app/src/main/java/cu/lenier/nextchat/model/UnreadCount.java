package cu.lenier.nextchat.model;

import androidx.room.ColumnInfo;

public class UnreadCount {
    @ColumnInfo(name = "contact")
    public String contact;

    @ColumnInfo(name = "unread")
    public int unread;
}
