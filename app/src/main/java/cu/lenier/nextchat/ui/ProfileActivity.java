package cu.lenier.nextchat.ui;

import android.app.DatePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Patterns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.snackbar.Snackbar;

import java.util.Calendar;
import java.util.concurrent.Executors;

import cu.lenier.nextchat.R;
import cu.lenier.nextchat.data.AppDatabase;
import cu.lenier.nextchat.model.Profile;
import cu.lenier.nextchat.util.ProfileSender;

public class ProfileActivity extends AppCompatActivity {
    private static final int PICK_IMAGE    = 100;
    private static final long TIMEOUT_MS   = 7_000L;

    private ImageView ivPhoto;
    private EditText  etName, etEmail, etPhone, etInfo, etBirth;
    private Spinner   spGender, spCountry, spProvince;
    private Button    btnSave;
    private View      progressOverlay;

    private static final String PREFS_NAME         = "profile_prefs";
    private static final String KEY_PROFILE_SENT   = "profile_sent";

    private String photoUriString;
    private final Handler handler = new Handler();

    private final BroadcastReceiver sendReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            handler.removeCallbacks(timeoutRunnable);
            hideProgress();
            if (ProfileSender.ACTION_PROFILE_SENT.equals(intent.getAction())) {
                Snackbar.make(progressOverlay, "Bienvenido", Snackbar.LENGTH_LONG).show();
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_PROFILE_SENT, true)
                        .apply();
                Intent i = new Intent(ProfileActivity.this, ChatListActivity.class);
                startActivity(i);
                finish();
            } else {
                Snackbar.make(progressOverlay, "Error enviando perfil", Snackbar.LENGTH_LONG).show();
            }
        }
    };

    private final Runnable timeoutRunnable = () -> {
        hideProgress();
        Snackbar.make(progressOverlay, "Tiempo de espera agotado", Snackbar.LENGTH_LONG).show();
    };

    @Override protected void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        // 0) Si ya enviamos el perfil, vamos directo al ChatListActivity
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_PROFILE_SENT, false)) {
            startActivity(new Intent(this, ChatListActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_profile);

        ivPhoto         = findViewById(R.id.ivPhoto);
        etName          = findViewById(R.id.etName);
        etEmail         = findViewById(R.id.etEmail);
        etPhone         = findViewById(R.id.etPhone);
        etInfo          = findViewById(R.id.etInfo);
        etBirth         = findViewById(R.id.etBirth);
        spGender        = findViewById(R.id.spGender);
        spCountry       = findViewById(R.id.spCountry);
        spProvince      = findViewById(R.id.spProvince);
        btnSave         = findViewById(R.id.btnSave);
        progressOverlay = findViewById(R.id.progressOverlay);

        // Prefill email and disable editing
        SharedPreferences pref = getSharedPreferences("prefs", MODE_PRIVATE);
        String savedEmail = pref.getString("email", "");
        etEmail.setText(savedEmail);
        etEmail.setEnabled(false);

        // Set up spinners with hint as first item
        ArrayAdapter<String> genderAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Elija un género", "Masculino", "Femenino", "Otro"});
        spGender.setAdapter(genderAdapter);

        ArrayAdapter<String> countryAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Elija un país", "Cuba", "Otro país"});
        spCountry.setAdapter(countryAdapter);

        ArrayAdapter<String> provinceAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Elija una provincia", "La Habana", "Santiago", "Holguin", "Otra provincia"});
        spProvince.setAdapter(provinceAdapter);

        // Birth date picker
        etBirth.setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            new DatePickerDialog(this,
                    (DatePicker dp, int year, int month, int day) ->
                            etBirth.setText(String.format("%04d-%02d-%02d",
                                    year, month + 1, day)),
                    c.get(Calendar.YEAR),
                    c.get(Calendar.MONTH),
                    c.get(Calendar.DAY_OF_MONTH)
            ).show();
        });

        ivPhoto.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(i, PICK_IMAGE);
        });

        btnSave.setOnClickListener(v -> validateAndSave());
    }

    private void validateAndSave() {
        resetErrors();
        boolean isValid = true;

        if (etName.getText().toString().trim().isEmpty()) {
            etName.setError("Este campo es obligatorio");
            isValid = false;
        }

        // Email: ya pre-filled and non-editable, skip validation

        if (etPhone.getText().toString().trim().isEmpty()) {
            etPhone.setError("Este campo es obligatorio");
            isValid = false;
        } else if (!Patterns.PHONE.matcher(etPhone.getText().toString()).matches()) {
            etPhone.setError("Número telefónico no válido");
            isValid = false;
        }

        if (etInfo.getText().toString().trim().isEmpty()) {
            etInfo.setError("Este campo es obligatorio");
            isValid = false;
        }

        if (etBirth.getText().toString().trim().isEmpty()) {
            etBirth.setError("Este campo es obligatorio");
            isValid = false;
        }

        // Spinners: first position is hint → invalid if selected
        if (spGender.getSelectedItemPosition() == 0) {
            Toast.makeText(this, "Seleccione un género", Toast.LENGTH_SHORT).show();
            isValid = false;
        }
        if (spCountry.getSelectedItemPosition() == 0) {
            Toast.makeText(this, "Seleccione un país", Toast.LENGTH_SHORT).show();
            isValid = false;
        }
        if (spProvince.getSelectedItemPosition() == 0) {
            Toast.makeText(this, "Seleccione una provincia", Toast.LENGTH_SHORT).show();
            isValid = false;
        }

        if (!isValid) return;

        showProgress();
        Profile profile = new Profile(
                etName.getText().toString().trim(),
                etEmail.getText().toString(),       // use prefilled
                etPhone.getText().toString().trim(),
                etInfo.getText().toString().trim(),
                spGender.getSelectedItem().toString(),
                etBirth.getText().toString().trim(),
                spCountry.getSelectedItem().toString(),
                spProvince.getSelectedItem().toString(),
                photoUriString
        );

        // Persistir en Room
        Executors.newSingleThreadExecutor().execute(() ->
                AppDatabase.getInstance(this).profileDao().insert(profile)
        );

        // Registrar receiver y enviar
        IntentFilter filter = new IntentFilter();
        filter.addAction(ProfileSender.ACTION_PROFILE_SENT);
        filter.addAction(ProfileSender.ACTION_PROFILE_FAILED);
        LocalBroadcastManager.getInstance(this).registerReceiver(sendReceiver, filter);

        ProfileSender.sendProfile(this, profile);
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS);
    }

    private void resetErrors() {
        etName.setError(null);
        etPhone.setError(null);
        etInfo.setError(null);
        etBirth.setError(null);
    }

    @Override protected void onActivityResult(int req, int res, @Nullable Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_IMAGE && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                photoUriString = uri.toString();
                ivPhoto.setImageURI(uri);
            }
        }
    }

    private void showProgress() {
        progressOverlay.setVisibility(View.VISIBLE);
        btnSave.setEnabled(false);
    }
    private void hideProgress() {
        progressOverlay.setVisibility(View.GONE);
        btnSave.setEnabled(true);
    }

    @Override protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(sendReceiver);
        handler.removeCallbacks(timeoutRunnable);
        super.onDestroy();
    }
}
