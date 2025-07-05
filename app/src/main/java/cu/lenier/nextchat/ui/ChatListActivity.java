package cu.lenier.nextchat.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Observer;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.lenier.update_chaker.UpdateChecker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import cu.lenier.nextchat.R;
import cu.lenier.nextchat.adapter.ChatListAdapter;
import cu.lenier.nextchat.config.AppConfig;
import cu.lenier.nextchat.data.AppDatabase;
import cu.lenier.nextchat.model.Message;
import cu.lenier.nextchat.model.Profile;
import cu.lenier.nextchat.model.UnreadCount;
import cu.lenier.nextchat.service.MailService;
import cu.lenier.nextchat.service.ProfileFetchService;
import cu.lenier.nextchat.work.MailSyncWorker;

public class ChatListActivity extends AppCompatActivity {
    private ChatListAdapter adapter;
    private final Map<String, Integer> unreadMap = new HashMap<>();
    private Toolbar toolbar;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCallback;

    private final Handler syncHandler = new Handler();
    private final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
            MailSyncWorker.forceSyncNow(ChatListActivity.this);
            syncHandler.postDelayed(this, 5000);
        }
    };

    private final BroadcastReceiver profilesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            loadAndApplyProfiles();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_chat_list);

        setupEdgeToEdgeInsets();
        
        // Start mail sync service
        Intent mailSvc = new Intent(this, MailService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(mailSvc);
        } else {
            startService(mailSvc);
        }
        startService(new Intent(this, ProfileFetchService.class));

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setTitle("Esperando conexión...");

        setupVersionDialog();
        setupRecyclerView();
        setupFab();
        setupNetworkCallback();
        observeContacts();
        observeUnreadCounts();
        loadAndApplyProfiles();
    }

    private void setupEdgeToEdgeInsets() {
        // 1) Referencias al root, toolbar y FAB
        final View root       = findViewById(R.id.rootLayout);
        final Toolbar toolbar = findViewById(R.id.toolbar);
        final FloatingActionButton fab = findViewById(R.id.fabNewChat);

        // 2) Guardar márgenes/paddings base
        //    – Toolbar: paddingTop original
        final int baseToolbarPaddingTop = toolbar.getPaddingTop();
        //    – FAB: marginBottom original
        ViewGroup.MarginLayoutParams fabLp =
                (ViewGroup.MarginLayoutParams) fab.getLayoutParams();
        final int baseFabMarginBottom = fabLp.bottomMargin;

        // 3) Listener único para insets superior e inferior
        ViewCompat.setOnApplyWindowInsetsListener(root, new OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
                Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

                // 3a) Ajustar Toolbar para que quede debajo de la status bar
                toolbar.setPadding(
                        toolbar.getPaddingLeft(),
                        sysBars.top + baseToolbarPaddingTop,
                        toolbar.getPaddingRight(),
                        toolbar.getPaddingBottom()
                );

                // 3b) Ajustar FAB para que quede encima de la navigation bar
                ViewGroup.MarginLayoutParams params =
                        (ViewGroup.MarginLayoutParams) fab.getLayoutParams();
                params.bottomMargin = sysBars.bottom + baseFabMarginBottom;
                fab.setLayoutParams(params);

                // 4) Devolver insets para que sigan propagándose
                return insets;
            }
        });

        // 5) Forzar la primera aplicación
        root.requestApplyInsets();
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_chat_list, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_profile) {
            startActivity(new Intent(this, ProfileActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(profilesReceiver,
                        new IntentFilter(ProfileFetchService.ACTION_PROFILES_SYNCED));
    }

    @Override
    protected void onStop() {
        LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(profilesReceiver);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception ignored) {
        }
        syncHandler.post(syncRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (cm != null && netCallback != null) {
                cm.unregisterNetworkCallback(netCallback);
            }
        } catch (Exception ignored) {
        }
        syncHandler.removeCallbacks(syncRunnable);
    }

    private void setupVersionDialog() {
        try {
            String vName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            int vCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
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
        } catch (Exception ignored) {
        }
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
        final View root = findViewById(R.id.rootLayout);
        final FloatingActionButton fab = findViewById(R.id.fabNewChat);

        // 2) Margin base (16dp) que pusiste en XML
        ViewGroup.MarginLayoutParams lpFab =
                (ViewGroup.MarginLayoutParams) fab.getLayoutParams();
        final int baseBottomMargin = lpFab.bottomMargin;

        // 3) Listener de insets correctamente tipado
        ViewCompat.setOnApplyWindowInsetsListener(root, new OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
                // Aquí sysBars es un Insets (de android.graphics)
                Insets sysBars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars());

                // Actualizamos sólo el marginBottom
                ViewGroup.MarginLayoutParams params =
                        (ViewGroup.MarginLayoutParams) fab.getLayoutParams();
                params.bottomMargin = sysBars.bottom + baseBottomMargin;
                fab.setLayoutParams(params);

                // Devolvemos los insets para que los puedan usar otras vistas hijas
                return insets;
            }
        });

        // 4) Listener normal de clic
        fab.setOnClickListener(view ->
                startActivity(new Intent(this, ContactPickerActivity.class))
        );

        // 5) Importante: después de setContentView() llama a requestApplyInsets()
        //    para que se dispare inmediatamente el listener.
        root.requestApplyInsets();
    }

    private void observeContacts() {
        AppDatabase db = AppDatabase.getInstance(this);
        db.messageDao().getContacts().observe(this, contacts -> {
            Executors.newSingleThreadExecutor().execute(() -> {
                // 1) Copia mutable e incluye el canal al inicio
                List<String> modList = new ArrayList<>(contacts);
                if (!modList.contains(AppConfig.OFFICIAL_CHANNEL_ID)) {
                    modList.add(0, AppConfig.OFFICIAL_CHANNEL_ID);
                }

                // 2) Construir mapas de preview y timestamp
                Map<String, String> previews = new HashMap<>();
                Map<String, Long> times = new HashMap<>();
                for (String c : modList) {
                    if (AppConfig.OFFICIAL_CHANNEL_ID.equals(c)) {
                        // Canal: último mensaje estático
                        List<Message> chMsgs = AppConfig.getChannelMessages();
                        if (!chMsgs.isEmpty()) {
                            Message last = chMsgs.get(chMsgs.size() - 1);
                            previews.put(c, last.type.equals("text") ? last.body : last.type);
                            times.put(c, last.timestamp);
                        } else {
                            previews.put(c, "");
                            times.put(c, 0L);
                        }
                    } else {
                        // Chat normal
                        Message last = db.messageDao().getLastMessageSync(c);
                        if (last != null) {
                            previews.put(c, last.type.equals("text") ? last.body : last.type);
                            times.put(c, last.timestamp);
                        } else {
                            previews.put(c, "");
                            times.put(c, 0L);
                        }
                    }
                }

                // 3) Ordenar por timestamp desc, canal siempre primero
                List<String> sorted = new ArrayList<>(modList);
                Collections.sort(sorted, (a, b) -> {
                    if (AppConfig.OFFICIAL_CHANNEL_ID.equals(a)) return -1;
                    if (AppConfig.OFFICIAL_CHANNEL_ID.equals(b)) return 1;
                    return Long.compare(times.getOrDefault(b, 0L),
                            times.getOrDefault(a, 0L));
                });

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
            for (UnreadCount uc : list) {
                unreadMap.put(uc.contact, uc.unread);
            }
            adapter.setUnreadMap(unreadMap);
        });
    }

    private void setupNetworkCallback() {
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                runOnUiThread(() -> toolbar.setTitle(getString(R.string.app_name)));
            }

            @Override
            public void onLost(@NonNull Network network) {
                runOnUiThread(() -> toolbar.setTitle("Esperando conexión..."));
            }
        };
    }

    private void loadAndApplyProfiles() {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<Profile> profiles = AppDatabase.getInstance(this)
                    .profileDao()
                    .getAllProfilesSync();
            Map<String, String> nameMap = new HashMap<>();
            Map<String, String> avatarMap = new HashMap<>();
            for (Profile p : profiles) {
                String key = p.email.trim().toLowerCase();
                nameMap.put(key, p.name);
                avatarMap.put(key, p.photoUri);
            }

            // ---- Inyectar canal oficial ----
            String chan = AppConfig.OFFICIAL_CHANNEL_ID;
            nameMap.put(chan, AppConfig.OFFICIAL_CHANNEL_NAME);
            // Usa drawable://ic_channel_avatar
            avatarMap.put(chan, "drawable://ic_verifed");

            runOnUiThread(() -> {
                adapter.setNameMap(nameMap);
                adapter.setAvatarMap(avatarMap);
            });
        });
    }


    private void openChat(String contact) {
        Executors.newSingleThreadExecutor().execute(() -> {
            String me = getSharedPreferences("prefs", MODE_PRIVATE).getString("email", "");
            AppDatabase.getInstance(this)
                    .messageDao()
                    .markAsRead(contact, me);
        });
        Intent i = new Intent(this, ChatActivity.class)
                .putExtra("contact", contact);
        startActivity(i);
    }

    private void confirmDelete(String contact) {
        new AlertDialog.Builder(this)
                .setTitle("Eliminar chat")
                .setMessage("¿Eliminar toda la conversación con " + contact + "?")
                .setPositiveButton("Eliminar", (d, w) ->
                        Executors.newSingleThreadExecutor().execute(() ->
                                AppDatabase.getInstance(this)
                                        .messageDao()
                                        .deleteByContact(contact)
                        ))
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
