// src/main/java/cu/lenier/nextchat/data/ProfileDao.java
package cu.lenier.nextchat.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

import cu.lenier.nextchat.model.Profile;

@Dao
public interface ProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(Profile profile);

    @Update
    void update(Profile profile);

    @Query("SELECT * FROM profiles WHERE email = :email LIMIT 1")
    Profile getByEmailSync(String email);

    @Query("SELECT * FROM profiles")
    List<Profile> getAllProfilesSync();

    @Query("DELETE FROM profiles")
    void clearAll();
}
