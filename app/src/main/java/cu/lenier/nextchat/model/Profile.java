// src/main/java/cu/lenier/nextchat/model/Profile.java
package cu.lenier.nextchat.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "profiles")
public class Profile {
    @PrimaryKey
    @NonNull
    public String email;    // ahora email es PK

    public String name;
    public String phone;
    public String info;
    public String gender;
    public String birthDate;
    public String country;
    public String province;
    public String photoUri;

    public boolean typing;
    public boolean online;
    public long lastSeen;

    public Profile(@NonNull String name,
                   @NonNull String email,
                   String phone,
                   String info,
                   String gender,
                   String birthDate,
                   String country,
                   String province,
                   String photoUri) {
        this.email     = email.trim().toLowerCase();
        this.name      = name;
        this.phone     = phone;
        this.info      = info;
        this.gender    = gender;
        this.birthDate = birthDate;
        this.country   = country;
        this.province  = province;
        this.photoUri  = photoUri;
        this.typing    = false;
        this.online    = false;
        this.lastSeen  = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "Profile{" +
                "email='" + email + '\'' +
                ", name='" + name + '\'' +
                ", phone='" + phone + '\'' +
                ", info='" + info + '\'' +
                ", gender='" + gender + '\'' +
                ", birthDate='" + birthDate + '\'' +
                ", country='" + country + '\'' +
                ", province='" + province + '\'' +
                ", photoUri='" + photoUri + '\'' +
                ", typing=" + typing +
                ", online=" + online +
                ", lastSeen=" + lastSeen +
                '}';
    }
}
