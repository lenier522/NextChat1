package cu.lenier.nextchat.util;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class PermissionHelper {
    public static final int REQ_PERMS = 101;

    /** Devuelve el array de permisos que faltan por aceptar */
    public static String[] missingPermissions(Activity act) {
        List<String> list = new ArrayList<>();

        // Cámara
        if (ContextCompat.checkSelfPermission(act,
                android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            list.add(android.Manifest.permission.CAMERA);
        }
        // Micrófono
        if (ContextCompat.checkSelfPermission(act,
                android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            list.add(android.Manifest.permission.RECORD_AUDIO);
        }
        // Notificaciones (solo Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(act,
                        android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            list.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        return list.toArray(new String[0]);
    }

    /** Lanza el diálogo de petición de permisos faltantes */
    public static void requestPermissionsIfNeeded(Activity act) {
        String[] missing = missingPermissions(act);
        if (missing.length > 0) {
            ActivityCompat.requestPermissions(act, missing, REQ_PERMS);
        }
    }
}
