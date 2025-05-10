package cu.lenier.nextchat.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

import cu.lenier.nextchat.R;
import cu.lenier.nextchat.adapter.MessageAdapter;
import cu.lenier.nextchat.config.AppConfig;
import cu.lenier.nextchat.data.AppDatabase;
import cu.lenier.nextchat.emoji.EmojiPickerManager;
import cu.lenier.nextchat.model.Message;
import cu.lenier.nextchat.model.Profile;
import cu.lenier.nextchat.util.MailHelper;
import cu.lenier.nextchat.util.PermissionHelper;
import cu.lenier.nextchat.util.SimpleTextWatcher;
import cu.lenier.nextchat.work.MailSyncWorker;

public class ChatActivity extends AppCompatActivity {
    private static final int REQ_AUDIO = 1001;

    private Toolbar toolbar;
    private RecyclerView rv;
    private EditText et;
    private FloatingActionButton fab;
    private MessageAdapter adapter;
    private String contact, me;
    private MediaRecorder recorder;
    private String audioPath;

    private EmojiPickerManager emojiPickerManager;

    private Message replyTo = null;
    private View replyPreviewContainer;
    private TextView tvReplyPreview;
    private ImageButton btnCancelReply;

    private FloatingActionButton fabScrollToBottom;


    private long lastIncomingTs = 0;
    private final Handler handler = new Handler();
    private final Runnable offlineRunnable = this::showLastSeen;

    private final Handler syncHandler = new Handler();
    private final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
            MailSyncWorker.forceSyncNow(ChatActivity.this);
            syncHandler.postDelayed(this, 5000);
        }
    };

    private ConnectivityManager.NetworkCallback netCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        // Recupera emails
        contact = getIntent().getStringExtra("contact");
        me = getSharedPreferences("prefs", MODE_PRIVATE)
                .getString("email", "");

        // Toolbar
        toolbar = findViewById(R.id.toolbar_chat);
        setSupportActionBar(toolbar);
        contact = getIntent().getStringExtra("contact");
        me = getSharedPreferences("prefs", MODE_PRIVATE).getString("email", "");

        // Inicialmente ponemos el email
        getSupportActionBar().setTitle(contact);
        getSupportActionBar().setSubtitle("Esperando conexión...");

        // Ahora buscamos nombre de perfil (si existe) y lo aplicamos
        Executors.newSingleThreadExecutor().execute(() -> {
            Profile p = AppDatabase
                    .getInstance(this)
                    .profileDao()
                    .getByEmailSync(contact.trim().toLowerCase());
            if (p != null && p.name != null && !p.name.isEmpty()) {
                runOnUiThread(() -> getSupportActionBar().setTitle(p.name));
            }
        });

        // RecyclerView
        rv = findViewById(R.id.rvMessages);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MessageAdapter();
        rv.setAdapter(adapter);


        // Reply preview
        replyPreviewContainer = findViewById(R.id.replyPreviewContainer);
        tvReplyPreview        = findViewById(R.id.tvReplyPreview);
        btnCancelReply        = findViewById(R.id.btnCancelReply);
        btnCancelReply.setOnClickListener(v -> exitReplyMode());






        // Inputs
        et = findViewById(R.id.etMessage);
        ImageButton btnEmoji = findViewById(R.id.btnEmoji);
        fab = findViewById(R.id.fabSend);
        ImageButton btnCamera = findViewById(R.id.btnCamera);

        // ③ Inicializa tu EmojiPickerManager
        //    rootView es la ConstraintLayout con id chat_root
        View rootView = findViewById(R.id.chat_root);
        emojiPickerManager = new EmojiPickerManager(this, rootView, et);

        // Observador de mensajes
        AppDatabase.getInstance(this)
                .messageDao()
                .getByContact(contact)
                .observe(this, (Observer<List<Message>>) msgs -> {
                    adapter.setMessages(msgs);
                    rv.scrollToPosition(adapter.getItemCount() - 1);
                    Executors.newSingleThreadExecutor().execute(() ->
                            AppDatabase.getInstance(ChatActivity.this)
                                    .messageDao().markAsRead(contact, me)
                    );
                    // Actualizar timestamp de última entrada
                    for (int i = msgs.size() - 1; i >= 0; i--) {
                        Message m = msgs.get(i);
                        if (!m.sent) {
                            lastIncomingTs = m.timestamp;
                            break;
                        }
                    }
                    updateOnlineStatus();
                });

        // Swipe to reply
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            @Override public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder t) { return false; }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
                replyTo = adapter.getMessageAt(vh.getAdapterPosition());
                enterReplyMode(replyTo);
                adapter.notifyItemChanged(vh.getAdapterPosition());
            }
        }).attachToRecyclerView(rv);


        // Cambiar icono del FAB
        et.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                fab.setImageResource(
                        s.toString().trim().isEmpty()
                                ? R.mipmap.ic_mic
                                : R.mipmap.ic_send
                );
            }
        });

        // ④ Override del click para alternar el panel de emojis
// Dentro de onCreate(), justo después de inicializar emojiPickerManager:
        btnEmoji.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);

            if (emojiPickerManager.isShowing()) {
                // 1) Si ahora está abierto el panel de emojis, lo cerramos...
                emojiPickerManager.dismiss();
                // 2) ...y volvemos a abrir el teclado
                et.requestFocus();
                imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
                // 3) Cambiamos el icono a emoji
                btnEmoji.setImageResource(R.drawable.ic_emoji_24);
            } else {
                // 1) Si el panel de emojis está cerrado, ocultamos teclado
                imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
                et.clearFocus();
                // 2) Abrimos el panel de emojis
                //    Con un pequeño delay para que no pelee con el teclado:
                et.postDelayed(() -> {
                    emojiPickerManager.toggle();
                    // 3) Cambiamos el icono a teclado
                    btnEmoji.setImageResource(R.drawable.ic_keyboard);
                }, 100);
            }
        });

        fabScrollToBottom = findViewById(R.id.fabScrollToBottom);
        // 1) Listener de scroll: muestra/oculta el FAB
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                // dy>0 cuando bajas, dy<0 cuando subes
                boolean atBottom = !recyclerView.canScrollVertically(1);
                fabScrollToBottom.setVisibility(atBottom ? View.GONE : View.VISIBLE);
            }
        });
        // 2) Al hacer clic, desplázate al último mensaje
        fabScrollToBottom.setOnClickListener(v -> {
            int last = adapter.getItemCount() - 1;
            if (last >= 0) {
                rv.smoothScrollToPosition(last);
            }
        });


        // Lanzar cámara
        btnCamera.setOnClickListener(v -> {
            String[] missing = PermissionHelper.missingPermissions(this);
            boolean needsCamera = false;
            for (String p : missing) {
                if (Manifest.permission.CAMERA.equals(p)) {
                    needsCamera = true;
                    break;
                }
            }
            if (needsCamera) {
                PermissionHelper.requestPermissionsIfNeeded(this);
            } else {
                cu.lenier.nextchat.BottomSheet.CameraBottomSheet
                        .newInstance(contact)
                        .show(getSupportFragmentManager(), "camera_sheet");
            }
        });

        // Enviar texto
        fab.setOnClickListener(v -> {
            String txt = et.getText().toString().trim();
            if (!txt.isEmpty()) {
                et.setText("");
                Message m = new Message();
                m.fromAddress   = me;
                m.toAddress     = contact;
                m.subject       = "NextChat";
                // ---- INICIA LÓGICA DE REPLY ----
                if (replyTo != null) {
                    m.inReplyToId   = replyTo.id;
                    m.inReplyToBody = replyTo.body;

                    String snippet;
                    // Verifica si el mensaje original es texto y tiene cuerpo
                    if ("text".equals(replyTo.type) && replyTo.body != null && !replyTo.body.isEmpty()) {
                        snippet = replyTo.body.length() > 30
                                ? replyTo.body.substring(0, 30) + "…"
                                : replyTo.body;
                    } else {
                        // Mensaje original es audio/imagen (o texto vacío)
                        switch (replyTo.type) {
                            case "audio":
                                snippet = "[Audio]";
                                break;
                            case "image":
                                snippet = "[Imagen]";
                                break;
                            default:
                                snippet = "[Mensaje]";
                        }
                    }
                    m.body = "↳ " + snippet + "\n" + txt;
                } else {
                    m.body = txt;
                }
                exitReplyMode();
                // ---- FIN LÓGICA DE REPLY ----
                m.attachmentPath = null;
                m.timestamp      = System.currentTimeMillis();
                m.sent           = true;
                m.read           = true;
                m.type           = "text";
                m.sendState      = Message.STATE_PENDING;
                MailHelper.sendEmail(this, m);
            } else {
                Toast.makeText(this,
                        "Mantén presionado para grabar audio",
                        Toast.LENGTH_SHORT).show();
            }
        });


        // Grabación de audio
        fab.setOnLongClickListener(v -> {
            if (checkAudioPerm()) startRecording();
            return true;
        });
        fab.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP && recorder != null) {
                stopRecordingAndSend();
            }
            return false;
        });

        // NetworkCallback: online/offline
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                runOnUiThread(() -> updateOnlineStatus());
            }

            @Override
            public void onLost(@NonNull Network network) {
                runOnUiThread(() -> {
                    if (getSupportActionBar() != null)
                        getSupportActionBar().setSubtitle("Sin conexión");
                });
            }
        };

        // Iniciar loop de sincronización
        syncHandler.post(syncRunnable);
    }

    private void enterReplyMode(Message original) {
        replyTo = original;
        String snippet;

        // Verifica si el mensaje original es de tipo texto y tiene cuerpo
        if ("text".equals(original.type) && original.body != null && !original.body.isEmpty()) {
            snippet = original.body.length() > 30
                    ? original.body.substring(0, 30) + "…"
                    : original.body;
        } else {
            // Mensaje es audio/imagen (o texto vacío)
            switch (original.type) {
                case "audio":
                    snippet = "[Audio]";
                    break;
                case "image":
                    snippet = "[Imagen]";
                    break;
                default:
                    snippet = "[Mensaje]"; // Tipo desconocido
            }
        }

        tvReplyPreview.setText("↳ " + snippet);
        replyPreviewContainer.setVisibility(View.VISIBLE);
    }
    private void exitReplyMode() {
        replyTo = null;
        replyPreviewContainer.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppConfig.setCurrentChat(contact);
        ConnectivityManager cm = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.registerDefaultNetworkCallback(netCallback);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ConnectivityManager cm = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.unregisterNetworkCallback(netCallback);
        syncHandler.removeCallbacks(syncRunnable);
        AppConfig.setCurrentChat(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(offlineRunnable);
        syncHandler.removeCallbacks(syncRunnable);
        // ⑤ evita fugas cerrando el popup
        if (emojiPickerManager != null) {
            emojiPickerManager.dismiss();
        }
    }

    private boolean checkAudioPerm() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_AUDIO
            );
            return false;
        }
        return true;
    }

    private void startRecording() {
        try {
            File dir = new File(getExternalFilesDir(null), "audios_enviados");
            if (!dir.exists()) dir.mkdirs();
            audioPath = new File(dir,
                    "audio_" + System.currentTimeMillis() + ".3gp")
                    .getAbsolutePath();
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setOutputFile(audioPath);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.prepare();
            recorder.start();
            Toast.makeText(this, "Grabando audio...", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this,
                    "Error al grabar audio", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecordingAndSend() {
        recorder.stop();
        recorder.release();
        recorder = null;
        Toast.makeText(this,
                "Enviando audio...", Toast.LENGTH_SHORT).show();
        Message m = new Message();
        m.fromAddress = me;
        m.toAddress = contact;
        m.subject = "NextChat Audio";
        m.body = "";
        m.attachmentPath = audioPath;
        m.timestamp = System.currentTimeMillis();
        m.sent = true;
        m.read = true;
        m.type = "audio";
        m.sendState = Message.STATE_PENDING;
        MailHelper.sendAudioEmail(this, m);
    }

    private void updateOnlineStatus() {
        ConnectivityManager cm = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        boolean connected = ni != null && ni.isConnected();
        if (!connected) {
            if (getSupportActionBar() != null)
                getSupportActionBar().setSubtitle("Sin conexión");
            return;
        }
        handler.removeCallbacks(offlineRunnable);
        long now = System.currentTimeMillis();
        if (now < lastIncomingTs + 120_000) {
            if (getSupportActionBar() != null)
                getSupportActionBar().setSubtitle("en línea");
            handler.postDelayed(offlineRunnable,
                    (lastIncomingTs + 120_000) - now);
        } else {
            showLastSeen();
        }
    }

    private void showLastSeen() {
        if (getSupportActionBar() == null) return;
        String date = new SimpleDateFormat("d 'de' MMM",
                new Locale("es")).format(new Date(lastIncomingTs));
        String time = new SimpleDateFormat("hh:mm a",
                new Locale("es")).format(new Date(lastIncomingTs));
        getSupportActionBar().setSubtitle("últ. vez " + date + ", " + time);
    }
}