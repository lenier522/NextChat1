package cu.lenier.nextchat.ui;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.util.Patterns;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

import cu.lenier.nextchat.R;
import cu.lenier.nextchat.adapter.ContactAdapter;
import cu.lenier.nextchat.model.ContactItem;

public class ContactPickerActivity extends AppCompatActivity {
    private static final int REQ_READ_CONTACTS = 123;

    private MaterialToolbar toolbar;
    private RecyclerView   rv;
    private ContactAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contact_picker);

        // Toolbar
        toolbar = findViewById(R.id.toolbar_picker);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        // Resuelvo el colorOnSurface dinámicamente
        TypedValue tv = new TypedValue();
        Resources.Theme theme = toolbar.getContext().getTheme();
// Para el título
        theme.resolveAttribute(R.attr.colorToolbarTitle, tv, true);
        int colorTitle = tv.data;
        toolbar.setTitleTextColor(colorTitle);

// Para el subtítulo (ej.: colorOnSurfaceVariant)
        theme.resolveAttribute(R.attr.colorToolbarSubt, tv, true);
        int colorSubtitle = tv.data;
        toolbar.setSubtitleTextColor(colorSubtitle);

// Para el icono de navegación
        if (toolbar.getNavigationIcon() != null) {
            toolbar.getNavigationIcon().setTint(colorTitle);
        }

        // RecyclerView
        rv = findViewById(R.id.rvContacts);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContactAdapter(new ArrayList<>(), email -> {
            // Al hacer click en un contacto, abrir ChatActivity
            Intent intent = new Intent(ContactPickerActivity.this, ChatActivity.class);
            intent.putExtra("contact", email);
            startActivity(intent);
        });
        rv.setAdapter(adapter);

        // Pedir permiso o cargar
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_CONTACTS},
                    REQ_READ_CONTACTS
            );
        } else {
            loadContacts();
        }
    }

    // Infla el menú con “Nueva conversación”
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_contact_picker, menu);
        return true;
    }

    // Maneja click sobre el menú
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_new_chat) {
            promptNewChat();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void promptNewChat() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_new_chat, null);
        TextInputEditText etEmail = dialogView.findViewById(R.id.etEmail);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);
        Button btnChat = dialogView.findViewById(R.id.btnChat);

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
                dlg.show();
                startActivity(new Intent(this, ChatActivity.class)
                        .putExtra("contact", email));
            }
        });
        dlg.show();
    }

    private void loadContacts() {
        List<ContactItem> list = new ArrayList<>();
        ContentResolver cr = getContentResolver();

        Cursor cur = cr.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Email.ADDRESS
                },
                null, null,
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME + " ASC"
        );

        if (cur != null) {
            while (cur.moveToNext()) {
                long id    = cur.getLong(
                        cur.getColumnIndexOrThrow(
                                ContactsContract.CommonDataKinds.Email.CONTACT_ID));
                String email = cur.getString(
                        cur.getColumnIndexOrThrow(
                                ContactsContract.CommonDataKinds.Email.ADDRESS));

                // Obtener nombre real
                String name = null;
                Cursor nc = cr.query(
                        ContactsContract.Contacts.CONTENT_URI,
                        new String[]{ContactsContract.Contacts.DISPLAY_NAME},
                        ContactsContract.Contacts._ID + "=?",
                        new String[]{String.valueOf(id)},
                        null
                );
                if (nc != null) {
                    if (nc.moveToFirst()) {
                        name = nc.getString(
                                nc.getColumnIndexOrThrow(
                                        ContactsContract.Contacts.DISPLAY_NAME));
                    }
                    nc.close();
                }
                if (name == null || name.trim().isEmpty()) {
                    name = email.substring(0, email.indexOf('@'));
                }

                list.add(new ContactItem(name, email));
            }
            cur.close();
        }

        runOnUiThread(() -> {
            adapter.updateData(list);
            toolbar.setSubtitle(list.size() + " contactos");
        });
    }

    @Override
    public void onRequestPermissionsResult(int req,
                                           @NonNull String[] perms,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(req, perms, grantResults);
        if (req == REQ_READ_CONTACTS &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadContacts();
        } else {
            Toast.makeText(this,
                    "Permiso de contactos rechazado",
                    Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
