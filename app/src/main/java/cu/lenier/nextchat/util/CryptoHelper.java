package cu.lenier.nextchat.util;

import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class CryptoHelper {
    private static final String KEY  = "0123456789abcdef";
    private static final String ALGO = "AES/CBC/PKCS5Padding";

    // Encripción/Decripción de texto (Base64)
    public static String encrypt(String plain) throws Exception {
        byte[] key = KEY.getBytes("UTF-8");
        SecretKeySpec ks = new SecretKeySpec(key, "AES");
        Cipher c = Cipher.getInstance(ALGO);
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        c.init(Cipher.ENCRYPT_MODE, ks, new IvParameterSpec(iv));
        byte[] enc = c.doFinal(plain.getBytes("UTF-8"));
        byte[] all = new byte[iv.length + enc.length];
        System.arraycopy(iv,0,all,0,iv.length);
        System.arraycopy(enc,0,all,iv.length,enc.length);
        return Base64.encodeToString(all, Base64.NO_WRAP);
    }

    public static String decrypt(String cipherText) throws Exception {
        byte[] all = Base64.decode(cipherText, Base64.NO_WRAP);
        byte[] iv  = new byte[16];
        System.arraycopy(all,0,iv,0,iv.length);
        byte[] enc = new byte[all.length - iv.length];
        System.arraycopy(all,iv.length,enc,0,enc.length);
        SecretKeySpec ks = new SecretKeySpec(KEY.getBytes("UTF-8"), "AES");
        Cipher c = Cipher.getInstance(ALGO);
        c.init(Cipher.DECRYPT_MODE, ks, new IvParameterSpec(iv));
        byte[] dec = c.doFinal(enc);
        return new String(dec, "UTF-8");
    }

    // Encripción y decripción de audio (streaming con IV prepended)
    public static void encryptAudio(File in, File out) throws Exception {
        byte[] key = KEY.getBytes("UTF-8");
        SecretKeySpec ks = new SecretKeySpec(key, "AES");
        Cipher c = Cipher.getInstance(ALGO);
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        c.init(Cipher.ENCRYPT_MODE, ks, new IvParameterSpec(iv));

        try (FileOutputStream fos = new FileOutputStream(out);
             CipherOutputStream cos = new CipherOutputStream(fos, c);
             FileInputStream fis = new FileInputStream(in)) {
            // Escribimos el IV primero
            fos.write(iv);
            byte[] buf = new byte[4096];
            int r;
            while ((r = fis.read(buf)) != -1) cos.write(buf,0,r);
        }
    }

    public static void decryptAudio(File in, File out) throws Exception {
        byte[] key = KEY.getBytes("UTF-8");
        SecretKeySpec ks = new SecretKeySpec(key, "AES");
        try (FileInputStream fis = new FileInputStream(in)) {
            byte[] iv = new byte[16];
            if (fis.read(iv) != iv.length)
                throw new IllegalArgumentException("Invalid encrypted audio");
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.DECRYPT_MODE, ks, new IvParameterSpec(iv));
            try (CipherInputStream cis = new CipherInputStream(fis,c);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[4096];
                int r;
                while ((r = cis.read(buf)) != -1) fos.write(buf,0,r);
            }
        }
    }

    /** Cifra cualquier fichero (incluyendo imágenes) **/
    public static void encryptFile(File in, File out) throws Exception {
        byte[] key = KEY.getBytes("UTF-8");
        SecretKeySpec ks = new SecretKeySpec(key, "AES");
        Cipher c = Cipher.getInstance(ALGO);
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        c.init(Cipher.ENCRYPT_MODE, ks, new IvParameterSpec(iv));

        try (FileOutputStream fos = new FileOutputStream(out);
             CipherOutputStream cos = new CipherOutputStream(fos, c);
             FileInputStream fis = new FileInputStream(in)) {
            // primero escribimos la IV
            fos.write(iv);
            byte[] buf = new byte[4096];
            int r;
            while ((r = fis.read(buf)) != -1) {
                cos.write(buf, 0, r);
            }
        }
    }

    /** Descifra cualquier fichero cifrado con encryptFile **/
    public static void decryptFile(File in, File out) throws Exception {
        byte[] key = KEY.getBytes("UTF-8");
        SecretKeySpec ks = new SecretKeySpec(key, "AES");
        try (FileInputStream fis = new FileInputStream(in)) {
            byte[] iv = new byte[16];
            if (fis.read(iv) != iv.length) {
                throw new IllegalArgumentException("Invalid encrypted file");
            }
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.DECRYPT_MODE, ks, new IvParameterSpec(iv));
            try (CipherInputStream cis = new CipherInputStream(fis, c);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[4096];
                int r;
                while ((r = cis.read(buf)) != -1) {
                    fos.write(buf, 0, r);
                }
            }
        }
    }
}