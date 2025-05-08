// ChatListActivity.java
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
        setContentView(R.layout.activity_chat_list);

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
        } catch (Exception ignored) {}
        syncHandler.post(syncRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (cm != null && netCallback != null) {
                cm.unregisterNetworkCallback(netCallback);
            }
        } catch (Exception ignored) {}
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
        fab.setOnClickListener(v ->
                startActivity(new Intent(this, ContactPickerActivity.class))
        );
    }

    private void observeContacts() {
        AppDatabase db = AppDatabase.getInstance(this);
        db.messageDao().getContacts().observe(this, contacts -> {
            Executors.newSingleThreadExecutor().execute(() -> {
                Map<String, String> previews = new HashMap<>();
                Map<String, Long> times = new HashMap<>();
                for (String c : contacts) {
                    Message last = db.messageDao().getLastMessageSync(c);
                    if (last != null) {
                        previews.put(c, android.text.TextUtils.equals(last.type, "text") ? last.body : last.type);
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