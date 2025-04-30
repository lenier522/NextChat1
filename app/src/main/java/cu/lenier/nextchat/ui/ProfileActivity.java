package cu.lenier.nextchat.ui;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Calendar;
import java.util.concurrent.Executors;

import cu.lenier.nextchat.R;
import cu.lenier.nextchat.data.AppDatabase;
import cu.lenier.nextchat.model.Profile;
import cu.lenier.nextchat.util.ProfileSender;

public class ProfileActivity extends AppCompatActivity {
    private static final int PICK_IMAGE = 100;

    private ImageView ivPhoto;
    private EditText  etName, etEmail, etPhone, etInfo, etBirth;
    private Spinner   spGender, spCountry, spProvince;
    private String    photoUriString;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        Button btnSave = findViewById(R.id.btnSave);

        // Adaptadores
        spGender.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Masculino","Femenino","Otro"}));
        spCountry.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Cuba","Otro país"}));
        spProvince.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"La Habana","Santiago","Otra provincia"}));

        // Fecha de nacimiento
        etBirth.setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            new DatePickerDialog(this,
                    (view, year, month, day) ->
                            etBirth.setText(String.format("%04d-%02d-%02d",
                                    year, month+1, day)),
                    c.get(Calendar.YEAR),
                    c.get(Calendar.MONTH),
                    c.get(Calendar.DAY_OF_MONTH)
            ).show();
        });

        // Selección de foto
        ivPhoto.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(i, PICK_IMAGE);
        });

        // Guardar + enviar
        btnSave.setOnClickListener(v -> {
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

            // 2) Enviar JSON + imagen adjunta
            ProfileSender.sendProfile(this, profile);

            finish();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                photoUriString = uri.toString();
                ivPhoto.setImageURI(uri);
            }
        }
    }
}
