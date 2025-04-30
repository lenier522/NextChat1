// src/main/java/cu/lenier/nextchat/exceptions/ExceptionHandler.java
package cu.lenier.nextchat.exceptions;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

public class ExceptionHandler implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "NextChatCrash";
    private static final String REPORT_TO = "6248904613953536@telegram-email.appspotmail.com";

    private final Context context;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    public ExceptionHandler(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        StringBuilder report = new StringBuilder();
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            report.append("App: ").append(pi.packageName)
                    .append(" v").append(pi.versionName)
                    .append(" (").append(pi.versionCode).append(")\n");
        } catch (PackageManager.NameNotFoundException ignored) {}

        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date());
        report.append("Fecha/Hora: ").append(ts).append("\n");
        report.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        report.append("Dispositivo: ").append(Build.BRAND)
                .append(" ").append(Build.MODEL).append("\n\n");

        report.append("Stacktrace:\n");
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        report.append(sw.toString());

        saveToFile(report.toString());
        sendByEmail(report.toString());
        showToast();

        if (defaultHandler != null) {
            defaultHandler.uncaughtException(t, e);
        } else {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(1);
        }
    }

    private void saveToFile(String report) {
        File log = new File(context.getFilesDir(), "crash_log.txt");
        try (PrintWriter pw = new PrintWriter(new FileWriter(log, false))) {
            pw.write(report);
        } catch (IOException io) {
            Log.e(TAG, "Error escribiendo crash_log", io);
        }
    }

    private void showToast() {
        Toast.makeText(context,
                "La aplicación se cerrará por un error inesperado",
                Toast.LENGTH_LONG).show();
    }

    private void sendByEmail(String body) {
        new Thread(() -> {
            SharedPreferences prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE);
            final String user = prefs.getString("email", "");
            final String pass = prefs.getString("pass", "");

            if (user.isEmpty() || pass.isEmpty()) {
                Log.e(TAG, "Credenciales no configuradas para enviar reporte");
                return;
            }

            Properties props = new Properties();
            props.put("mail.smtp.host", "smtp.nauta.cu");
            props.put("mail.smtp.port", "25");
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(user, pass);
                }
            });

            try {
                MimeMessage msg = new MimeMessage(session);
                msg.setFrom(new InternetAddress(user));
                msg.setRecipient(Message.RecipientType.TO, new InternetAddress(REPORT_TO));
                msg.setSubject("NextChat Crash Report");
                msg.setText(body, "utf-8");
                Transport.send(msg);
                Log.d(TAG, "Reporte de error enviado exitosamente");
            } catch (MessagingException me) {
                Log.e(TAG, "Error enviando reporte", me);
            }
        }).start();
    }
}