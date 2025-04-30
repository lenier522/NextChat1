package cu.lenier.nextchat.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Patterns;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.lenier.update_chaker.UpdateChecker;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import cu.lenier.nextchat.R;
import cu.lenier.nextchat.adapter.ChatListAdapter;
import cu.lenier.nextchat.data.AppDatabase;
import cu.lenier.nextchat.model.Message;
import cu.lenier.nextchat.model.Profile;
import cu.lenier.nextchat.model.UnreadCount;
import cu.lenier.nextchat.service.MailService;
import cu.lenier.nextchat.service.ProfileFetchService;
import cu.lenier.nextchat.work.MailSyncWorker;

public class ChatListActivity extends AppCompatActivity {
    private ChatListAdapter adapter;
    private final Map<String,Integer> unreadMap = new HashMap<>();
    private Toolbar toolbar;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCallback;

    private final Handler syncHandler = new Handler();
    private final Runnable syncRunnable = new Runnable() {
        @Override public void run() {
            MailSyncWorker.forceSyncNow(ChatListActivity.this);
            syncHandler.postDelayed(this, 5000);
        }
    };

    private final BroadcastReceiver profilesReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            loadAndApplyProfiles();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_list);


        // 1) Iniciar servicios de email y perfil
        Intent mailSvc = new Intent(this, MailService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(mailSvc);
        } else {
            startService(mailSvc);
        }
        startService(new Intent(this, ProfileFetchService.class));

        // 2) Toolbar estado inicial
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setTitle("Esperando conexión...");

        // 3) Configurar versión, RecyclerView y FAB
        setupVersionDialog();
        setupRecyclerView();
        setupFab();

        // 4) Preparar NetworkCallback (registrado en onResume)
        setupNetworkCallback();

        // 5) Observers de datos
        observeContacts();
        observeUnreadCounts();
        loadAndApplyProfiles();
    }

    @Override protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(profilesReceiver,
                        new IntentFilter(ProfileFetchService.ACTION_PROFILES_SYNCED));
    }

    @Override protected void onStop() {
        LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(profilesReceiver);
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        // Registrar callback de red
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception ignored) {}
        // Iniciar sincronización periódica
        syncHandler.post(syncRunnable);
    }

    @Override protected void onPause() {
        super.onPause();
        // Dar de baja callback de red
        try {
            if (cm != null && netCallback != null) {
                cm.unregisterNetworkCallback(netCallback);
            }
        } catch (Exception ignored) {}
        syncHandler.removeCallbacks(syncRunnable);
    }

    // —— Helpers —— //

    private void setupVersionDialog() {
        try {
            String vName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            int vCode = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionCode;
            String url = "https://raw.githubusercontent.com/lenier522/update/main/update.json";
            UpdateChecker.checkForUpdate(this, vCode, url, true);
            boolean shown = getSharedPreferences(vName, MODE_PRIVATE)
                    .getBoolean("about_shown", false);
            if (!shown) {
                View dlgV = getLayoutInflater().inflate(R.layout.dialog_about, null);
                Button b = dlgV.findViewById(R.id.btnCheck);
                CheckBox cb = dlgV.findViewById(R.id.checkPol);
                TextView tv = dlgV.findViewById(R.id.tvVersion);
                tv.setText(vName);
                b.setEnabled(false);
                cb.setOnCheckedChangeListener((c, on) -> b.setEnabled(on));
                AlertDialog dlg = new MaterialAlertDialogBuilder(this,
                        R.style.Theme_NextChat_AlertDialog)
                        .setView(dlgV).setCancelable(false).create();
                b.setOnClickListener(x -> {
                    getSharedPreferences(vName, MODE_PRIVATE)
                            .edit().putBoolean("about_shown", true).apply();
                    dlg.dismiss();
                });
                dlg.show();
            }
        } catch (Exception ignored) {}
    }

    private void setupRecyclerView() {
        RecyclerView rv = findViewById(R.id.rvChats);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChatListAdapter();
        adapter.setOnItemClickListener(this::openChat);
        adapter.setOnItemLongClickListener(this::confirmDelete);
        rv.setAdapter(adapter);
    }

    private void setupFab() {
        FloatingActionButton fab = findViewById(R.id.fabNewChat);
        fab.setOnClickListener(v -> promptNewChat());
    }

    private void promptNewChat() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_new_chat, null);
        TextInputEditText etEmail = dialogView.findViewById(R.id.etEmail);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);
        Button btnChat   = dialogView.findViewById(R.id.btnChat);

        AlertDialog dlg = new MaterialAlertDialogBuilder(this,
                R.style.Theme_NextChat_AlertDialog)
                .setView(dialogView).create();

        btnCancel.setOnClickListener(v -> dlg.dismiss());
        btnChat.setOnClickListener(v -> {

//          throw new RuntimeException("Crash de prueba pulsando el botón");

            String email = etEmail.getText().toString().trim();
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Snackbar.make(v, "Email inválido", Snackbar.LENGTH_SHORT).show();
            } else {
                dlg.dismiss();
                openChat(email);
            }
        });
        dlg.show();
    }

    private void observeContacts() {
        AppDatabase db = AppDatabase.getInstance(this);
        db.messageDao().getContacts().observe(this, contacts -> {
            Executors.newSingleThreadExecutor().execute(() -> {
                Map<String,String> previews = new HashMap<>();
                Map<String,Long> times = new HashMap<>();
                for (String c : contacts) {
                    Message last = db.messageDao().getLastMessageSync(c);
                    if (last != null) {
                        previews.put(c, last.type.equals("text")
                                ? last.body : last.type);
                        times.put(c, last.timestamp);
                    } else {
                        previews.put(c, "");
                        times.put(c, 0L);
                    }
                }
                List<String> sorted = new ArrayList<>(contacts);
                Collections.sort(sorted, (a, b) ->
                        Long.compare(times.getOrDefault(b, 0L),
                                times.getOrDefault(a, 0L))
                );
                runOnUiThread(() -> {
                    adapter.setContacts(sorted);
                    adapter.setPreviewMap(previews);
                    adapter.setTimeMap(times);
                });
            });
        });
    }

    private void observeUnreadCounts() {
        AppDatabase db = AppDatabase.getInstance(this);
        db.messageDao().getUnreadCounts().observe(this, list -> {
            unreadMap.clear();
            for (UnreadCount uc : list) unreadMap.put(uc.contact, uc.unread);
            adapter.setUnreadMap(unreadMap);
        });
    }

    private void setupNetworkCallback() {
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(@NonNull Network network) {
                // TODO: siempre en UI thread
                runOnUiThread(() -> toolbar.setTitle("Conectando..."));
                Executors.newSingleThreadExecutor().execute(() -> {
                    boolean ok = hasInternetAccess();
                    runOnUiThread(() -> {
                        if (ok) toolbar.setTitle(getString(R.string.app_name));
                        else    toolbar.setTitle("Conectando...");
                    });
                });
            }
            @Override public void onLost(@NonNull Network network) {
                runOnUiThread(() -> toolbar.setTitle("Esperando conexión..."));
            }
        };
    }

    private boolean hasInternetAccess() {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new URL("https://www.todus.cu").openConnection();
            conn.setRequestProperty("Connection", "close");
            conn.setConnectTimeout(2000);
            conn.connect();
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 400;
        } catch (IOException e) {
            return false;
        }
    }

    private void loadAndApplyProfiles() {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<Profile> profiles = AppDatabase.getInstance(this)
                    .profileDao()
                    .getAllProfilesSync();
            Map<String,String> nameMap   = new HashMap<>();
            Map<String,String> avatarMap = new HashMap<>();
            for (Profile p : profiles) {
                String key = p.email.trim().toLowerCase();
                nameMap.put(key, p.name);
                avatarMap.put(key, p.photoUri);
            }
            runOnUiThread(() -> {
                adapter.setNameMap(nameMap);
                adapter.setAvatarMap(avatarMap);
            });
        });
    }

    private void openChat(String contact) {
        Executors.newSingleThreadExecutor().execute(() ->
                AppDatabase.getInstance(this)
                        .messageDao()
                        .markAsRead(contact,
                                getSharedPreferences("prefs", MODE_PRIVATE)
                                        .getString("email", "")
                        )
        );
        startActivity(new Intent(this, ChatActivity.class)
                .putExtra("contact", contact));
    }

    private void confirmDelete(String contact) {
        new AlertDialog.Builder(this)
                .setTitle("Eliminar chat")
                .setMessage("¿Eliminar toda la conversación con " + contact + "?")
                .setPositiveButton("Eliminar", (d, w) -> Executors
                        .newSingleThreadExecutor().execute(() ->
                                AppDatabase.getInstance(this)
                                        .messageDao()
                                        .deleteByContact(contact)
                        ))
                .setNegativeButton("Cancelar", null)
                .show();
    }
}