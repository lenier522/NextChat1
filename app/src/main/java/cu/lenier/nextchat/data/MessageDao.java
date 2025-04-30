package cu.lenier.nextchat.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

import cu.lenier.nextchat.model.Message;
import cu.lenier.nextchat.model.UnreadCount;

@Dao
public interface MessageDao {
    /** Inserta un nuevo mensaje y devuelve su ID */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(Message msg);

    /** Actualiza un mensaje existente (p. ej. para cambiar sendState) */
    @Update
    void update(Message msg);

    /** Elimina un mensaje específico */
    @Delete
    void delete(Message msg);

    /** Mensajes de un contacto, incluyendo imágenes */
    @Query("SELECT * FROM messages " +
            "WHERE subject IN ('NextChat','NextChat Audio','NextChat Image') " +
            "  AND (fromAddress = :addr OR toAddress = :addr) " +
            "ORDER BY timestamp ASC")
    LiveData<List<Message>> getByContact(String addr);

    /** Lista de contactos con los que has chateado (cualquier tipo) */
    @Query("SELECT DISTINCT CASE WHEN sent=1 THEN toAddress ELSE fromAddress END " +
            "FROM messages " +
            "WHERE subject IN ('NextChat','NextChat Audio','NextChat Image')")
    LiveData<List<String>> getContacts();

    /** Recuentos de no leídos, agrupa también imágenes */
    @Query("SELECT CASE WHEN sent=1 THEN toAddress ELSE fromAddress END AS contact, " +
            "       COUNT(*) AS unread " +
            "FROM messages " +
            "WHERE subject IN ('NextChat','NextChat Audio','NextChat Image') " +
            "  AND sent=0 AND read=0 " +
            "GROUP BY contact")
    LiveData<List<UnreadCount>> getUnreadCounts();

    @Query("UPDATE messages SET read=1 " +
            "WHERE subject IN ('NextChat','NextChat Audio','NextChat Image') " +
            "  AND fromAddress=:contact AND toAddress=:me AND read=0")
    void markAsRead(String contact, String me);

    @Query("DELETE FROM messages " +
            "WHERE subject IN ('NextChat','NextChat Audio','NextChat Image') " +
            "  AND (fromAddress=:contact OR toAddress=:contact)")
    void deleteByContact(String contact);

    @Query("DELETE FROM messages WHERE id = :id")
    void deleteById(int id);

    @Query("SELECT * FROM messages " +
            "WHERE subject IN ('NextChat','NextChat Audio','NextChat Image') " +
            "  AND (fromAddress = :contact OR toAddress = :contact) " +
            "ORDER BY timestamp DESC " +
            "LIMIT 1")
    Message getLastMessageSync(String contact);

    /** Cuenta cuántas veces ya existe un mensaje con esos datos */
    @Query("SELECT COUNT(*) FROM messages " +
            "WHERE fromAddress = :from AND toAddress = :to " +
            "  AND subject = :subj AND timestamp = :ts")
    int countExisting(String from, String to, String subj, long ts);
}
