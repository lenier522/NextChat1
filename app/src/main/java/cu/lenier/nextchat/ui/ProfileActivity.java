package cu.lenier.nextchat.ui;

import android.app.DatePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import com.google.android.material.textfield.TextInputEditText;

import java.util.Calendar;
import java.util.concurrent.Executors;

import cu.lenier.nextchat.R;
import cu.lenier.nextchat.data.AppDatabase;
import cu.lenier.nextchat.model.Profile;
import cu.lenier.nextchat.util.ProfileSender;

public class ProfileActivity extends AppCompatActivity {
    private static final int PICK_IMAGE = 100;
    private static final long  TIMEOUT_MS = 7_000L;

    private ImageView ivPhoto;
    private EditText  etName, etEmail, etPhone, etInfo, etBirth;
    private Spinner   spGender, spCountry, spProvince;
    private Button    btnSave;
    private View      progressOverlay;

    private String    photoUriString;
    private final Handler handler = new Handler();

    private final BroadcastReceiver sendReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            handler.removeCallbacks(timeoutRunnable);
            hideProgress();
            if (ProfileSender.ACTION_PROFILE_SENT.equals(intent.getAction())) {
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

    @Override
    protected void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_profile);

        ivPhoto    = findViewById(R.id.ivPhoto);
        etName     = findViewById(R.id.etName);
        etEmail    = findViewById(R.id.etEmail);
        etPhone    = findViewById(R.id.etPhone);
        etInfo     = findViewById(R.id.etInfo);
        etBirth    = findViewById(R.id.etBirth);
        spGender   = findViewById(R.id.spGender);
        spCountry  = findViewById(R.id.spCountry);
        spProvince = findViewById(R.id.spProvince);
        btnSave    = findViewById(R.id.btnSave);
        progressOverlay = findViewById(R.id.progressOverlay);


        btnSave.setOnClickListener(v -> validateAndSave());


        // Spinners
        spGender.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Masculino","Femenino","Otro"}));
        spCountry.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Cuba","Otro país"}));
        spProvince.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"La Habana","Santiago","Holguin","Otra provincia"}));

        // Birth date picker
        etBirth.setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            new DatePickerDialog(this,
                    (DatePicker dp, int year, int month, int day) -> {
                        etBirth.setText(String.format("%04d-%02d-%02d",
                                year, month + 1, day));
                    },
                    c.get(Calendar.YEAR),
                    c.get(Calendar.MONTH),
                    c.get(Calendar.DAY_OF_MONTH)
            ).show();
        });

        // Photo picker
        ivPhoto.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(i, PICK_IMAGE);
        });

    }

    // Método de validación
    private void validateAndSave() {
        // Reiniciar errores
        resetErrors();

        boolean isValid = true;

        // Validar campo por campo
        if (etName.getText().toString().trim().isEmpty()) {
            etName.setError("Este campo es obligatorio");
            isValid = false;
        }

        if (etEmail.getText().toString().trim().isEmpty()) {
            etEmail.setError("Este campo es obligatorio");
            isValid = false;
        } else if (!isValidEmail(etEmail.getText().toString())) {
            etEmail.setError("Correo electrónico no válido");
            isValid = false;
        }

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

        if (spGender.getSelectedItemPosition() == 0) { // Asumiendo que el primer elemento es un hint
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

        if (isValid) {
            // Todos los campos son válidos
            Toast.makeText(this, "Datos guardados correctamente", Toast.LENGTH_SHORT).show();
            // Aquí tu lógica para guardar los datos
            showProgress();
            Profile profile = new Profile(
                    etName.getText().toString().trim(),
                    etEmail.getText().toString().trim(),
                    etPhone.getText().toString().trim(),
                    etInfo.getText().toString().trim(),
                    spGender.getSelectedItem().toString(),
                    etBirth.getText().toString().trim(),
                    spCountry.getSelectedItem().toString(),
                    spProvince.getSelectedItem().toString(),
                    photoUriString
            );

            // 1) Persistir en Room
            Executors.newSingleThreadExecutor().execute(() ->
                    AppDatabase.getInstance(this).profileDao().insert(profile)
            );
            // 2) Registrar broadcast receiver
            IntentFilter filter = new IntentFilter();
            filter.addAction(ProfileSender.ACTION_PROFILE_SENT);
            filter.addAction(ProfileSender.ACTION_PROFILE_FAILED);
            LocalBroadcastManager.getInstance(this)
                    .registerReceiver(sendReceiver, filter);

            // 3) Enviar JSON + imagen adjunta
            ProfileSender.sendProfile(this, profile);

            // 4) Programar timeout
            handler.postDelayed(timeoutRunnable, TIMEOUT_MS);
        }
    }

    private void resetErrors() {
        etName.setError(null);
        etEmail.setError(null);
        etPhone.setError(null);
        etInfo.setError(null);
        etBirth.setError(null);
    }

    private boolean isValidEmail(CharSequence target) {
        return Patterns.EMAIL_ADDRESS.matcher(target).matches();
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(sendReceiver);
        handler.removeCallbacks(timeoutRunnable);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int req, int res, @Nullable Intent data) {
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
}
