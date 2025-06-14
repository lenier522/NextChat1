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

import cu.lenier.nextchat.BottomSheet.CameraBottomSheet;
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
    private TextView tvReplyPreview, tvReplyType;
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


        // Recover contact & current user
        contact = getIntent().getStringExtra("contact");
        me = getSharedPreferences("prefs", MODE_PRIVATE).getString("email", "");

        // Toolbar setup
        toolbar = findViewById(R.id.toolbar_chat);
        setSupportActionBar(toolbar);
        getSupportActionBar().setTitle(contact);
        getSupportActionBar().setSubtitle("Esperando conexión...");

        // Load saved profile name if available
        Executors.newSingleThreadExecutor().execute(() -> {
            Profile p = AppDatabase
                    .getInstance(this)
                    .profileDao()
                    .getByEmailSync(contact.trim().toLowerCase());
            if (p != null && p.name != null && !p.name.isEmpty()) {
                runOnUiThread(() -> getSupportActionBar().setTitle(p.name));
            }
        });

        // RecyclerView & adapter
        rv = findViewById(R.id.rvMessages);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MessageAdapter();
        rv.setAdapter(adapter);

        // Reply preview UI
        replyPreviewContainer = findViewById(R.id.replyPreviewContainer);
        tvReplyPreview = findViewById(R.id.tvReplyPreview);
        tvReplyType = findViewById(R.id.tvReplyType);
        btnCancelReply = findViewById(R.id.btnCancelReply);
        btnCancelReply.setOnClickListener(v -> exitReplyMode());

        // Inputs: text, emoji, camera, send FAB
        et = findViewById(R.id.etMessage);
        ImageButton btnEmoji = findViewById(R.id.btnEmoji);
        fab = findViewById(R.id.fabSend);
        ImageButton btnCamera = findViewById(R.id.btnCamera);

        // Emoji picker
        View rootView = findViewById(R.id.chat_root);
        emojiPickerManager = new EmojiPickerManager(this, rootView, et);

        // 7) Si es canal: mostrar mensajes estáticos y bloquear UI
        if (AppConfig.OFFICIAL_CHANNEL_ID.equals(contact)) {
            List<Message> staticMsgs = AppConfig.getChannelMessages();
            adapter.setMessages(staticMsgs);
            rv.scrollToPosition(adapter.getItemCount() - 1);
            // Deshabilitar completamente la caja de texto y mostrar hint especial
            et.setEnabled(false);
            et.setHint("Canal oficial - no se permiten mensajes");
            fab.setVisibility(View.GONE);
            btnCamera.setVisibility(View.GONE);
            btnEmoji.setVisibility(View.GONE);
            replyPreviewContainer.setVisibility(View.GONE);
        }


        // Observe messages for this contact
        // Canal vs chat normal
        if (AppConfig.OFFICIAL_CHANNEL_ID.equals(contact)) {
            // Canal: mensajes estáticos
            adapter.setMessages(AppConfig.getChannelMessages());
            rv.scrollToPosition(adapter.getItemCount() - 1);
        } else {
            // Chat normal
            AppDatabase.getInstance(this)
                    .messageDao()
                    .getByContact(contact)
                    .observe(this, (Observer<List<Message>>) msgs -> {
                        adapter.setMessages(msgs);
                        rv.scrollToPosition(adapter.getItemCount() - 1);
                        // Marcar como leído
                        Executors.newSingleThreadExecutor().execute(() ->
                                AppDatabase.getInstance(this)
                                        .messageDao()
                                        .markAsRead(contact, me)
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
        }


        // Swipe to reply
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder t) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
                replyTo = adapter.getMessageAt(vh.getAdapterPosition());
                enterReplyMode(replyTo);
                adapter.notifyItemChanged(vh.getAdapterPosition());
            }
        }).attachToRecyclerView(rv);

        // Change FAB icon when typing
        et.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                fab.setImageResource(
                        s.toString().trim().isEmpty() ? R.mipmap.ic_mic : R.mipmap.ic_send
                );
            }
        });

        // Emoji toggle
        btnEmoji.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (emojiPickerManager.isShowing()) {
                emojiPickerManager.dismiss();
                et.requestFocus();
                imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
                btnEmoji.setImageResource(R.drawable.ic_emoji_24);
            } else {
                imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
                et.clearFocus();
                et.postDelayed(() -> {
                    emojiPickerManager.toggle();
                    btnEmoji.setImageResource(R.drawable.ic_keyboard);
                }, 100);
            }
        });

        // Scroll-to-bottom FAB
        fabScrollToBottom = findViewById(R.id.fabScrollToBottom);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                fabScrollToBottom.setVisibility(
                        view.canScrollVertically(1) ? View.VISIBLE : View.GONE
                );
            }
        });
        fabScrollToBottom.setOnClickListener(v -> {
            int last = adapter.getItemCount() - 1;
            if (last >= 0) rv.smoothScrollToPosition(last);
        });

        // Camera
        btnCamera.setOnClickListener(v -> {
            if (PermissionHelper.missingPermissions(this).length > 0) {
                PermissionHelper.requestPermissionsIfNeeded(this);
            } else {
                Long rid = replyTo != null ? (long) replyTo.id : null;
                CameraBottomSheet.newInstance(contact, rid,
                                replyTo == null ? null : replyTo.body,
                                replyTo == null ? null : replyTo.type)
                        .show(getSupportFragmentManager(), "camera_sheet");
            }
        });

        // Send text
        fab.setOnClickListener(v -> {
            String txt = et.getText().toString().trim();
            if (txt.isEmpty()) {
                Toast.makeText(this, "Mantén presionado para grabar audio", Toast.LENGTH_SHORT).show();
                return;
            }
            et.setText("");
            Message m = new Message();
            m.fromAddress = me;
            m.toAddress = contact;
            m.subject = "NextChat";
            if (replyTo != null) {
                m.inReplyToId = replyTo.id;
                m.inReplyToBody = replyTo.body;
                m.inReplyToType = replyTo.type;
                exitReplyMode();
            }
            m.body = txt;
            m.attachmentPath = null;
            m.timestamp = System.currentTimeMillis();
            m.sent = true;
            m.read = true;
            m.type = "text";
            m.sendState = Message.STATE_PENDING;
            MailHelper.sendEmail(this, m);
        });

        // Record audio
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

        // Network status callbacks
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                updateOnlineStatus();
            }

            @Override
            public void onLost(@NonNull Network network) {
                if (getSupportActionBar() != null)
                    getSupportActionBar().setSubtitle("Sin conexión");
            }
        };

        // Kick off sync loop
        syncHandler.post(syncRunnable);
    }

    private void enterReplyMode(Message original) {
        replyTo = original;

        // Configurar vista de preview
        if (replyPreviewContainer != null && tvReplyPreview != null && tvReplyType != null) {
            String typeLabel;
            int iconRes = R.drawable.ic_quote; // Icono por defecto

            switch (original.type) {
                case "text":
                    typeLabel = "Respondiendo a un mensaje";
                    iconRes = R.drawable.ic_text_quote;
                    if (original.body != null && !original.body.isEmpty()) {
                        String preview = original.body.length() > 40
                                ? original.body.substring(0, 40) + "…"
                                : original.body;
                        tvReplyPreview.setText(preview);
                    }
                    break;
                case "audio":
                    typeLabel = "Respondiendo a un audio";
                    iconRes = R.drawable.ic_audio_wave;
                    tvReplyPreview.setText("Toca para escuchar");
                    break;
                case "image":
                    typeLabel = "Respondiendo a una imagen";
                    iconRes = R.drawable.ic_image_frame;
                    tvReplyPreview.setText("Toca para ver");
                    break;
                default:
                    typeLabel = "Respondiendo a un mensaje";
            }

            tvReplyType.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
            tvReplyType.setText(typeLabel);

            // Animación mejorada
            replyPreviewContainer.setAlpha(0f);
            replyPreviewContainer.setVisibility(View.VISIBLE);
            replyPreviewContainer.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start();
        }
    }

    public void exitReplyMode() {
        replyTo = null;
        replyPreviewContainer.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppConfig.setCurrentChat(contact);
        ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE))
                .registerDefaultNetworkCallback(netCallback);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE))
                .unregisterNetworkCallback(netCallback);
        syncHandler.removeCallbacks(syncRunnable);
        AppConfig.setCurrentChat(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(offlineRunnable);
        syncHandler.removeCallbacks(syncRunnable);
        if (emojiPickerManager != null) emojiPickerManager.dismiss();
    }

    private boolean checkAudioPerm() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return false;
        }
        return true;
    }

    private void startRecording() {
        try {
            File dir = new File(getExternalFilesDir(null), "audios_enviados");
            if (!dir.exists()) dir.mkdirs();
            audioPath = new File(dir, "audio_" + System.currentTimeMillis() + ".3gp").getAbsolutePath();
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
            Toast.makeText(this, "Error al grabar audio", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecordingAndSend() {
        recorder.stop();
        recorder.release();
        recorder = null;
        Toast.makeText(this, "Enviando audio...", Toast.LENGTH_SHORT).show();
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
        if (replyTo != null) {
            m.inReplyToId = replyTo.id;
            m.inReplyToBody = replyTo.body;
            m.inReplyToType = replyTo.type;
            exitReplyMode();
        }
        MailHelper.sendAudioEmail(this, m);
    }

    private void updateOnlineStatus() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        if (ni == null || !ni.isConnected()) {
            if (getSupportActionBar() != null) getSupportActionBar().setSubtitle("Sin conexión");
            return;
        }
        handler.removeCallbacks(offlineRunnable);
        long now = System.currentTimeMillis();
        if (now < lastIncomingTs + 120_000) {
            if (getSupportActionBar() != null) getSupportActionBar().setSubtitle("en línea");
            handler.postDelayed(offlineRunnable, (lastIncomingTs + 120_000) - now);
        } else {
            showLastSeen();
        }
    }

    private void showLastSeen() {
        if (getSupportActionBar() == null) return;
        String date = new SimpleDateFormat("d 'de' MMM", new Locale("es")).format(new Date(lastIncomingTs));
        String time = new SimpleDateFormat("hh:mm a", new Locale("es")).format(new Date(lastIncomingTs));
        getSupportActionBar().setSubtitle("últ. vez " + date + ", " + time);
    }
}