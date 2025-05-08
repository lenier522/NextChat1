package cu.lenier.nextchat;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import cu.lenier.nextchat.BottomSheet.FAQBottomSheet;
import cu.lenier.nextchat.adapter.OnboardingAdapter;
import cu.lenier.nextchat.ui.LoginActivity;

public class MainActivity extends AppCompatActivity implements OnboardingAdapter.OnFaqClickListener {

    private static final String PREFS_NAME = "onboarding_prefs";
    private static final String KEY_COMPLETED = "completed";

    private ViewPager2 viewPager;
    private Button buttonSkip, buttonBack, buttonNext;
    private String[] titles, descriptions, lotties;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Saltar if already completed
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_COMPLETED, false)) {
            navigateToLogin();
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        titles       = getResources().getStringArray(R.array.onboard_titles);
        descriptions = getResources().getStringArray(R.array.onboard_descriptions);
        lotties      = getResources().getStringArray(R.array.onboard_lottie);

        viewPager  = findViewById(R.id.viewPager);
        buttonSkip = findViewById(R.id.buttonSkip);
        buttonBack = findViewById(R.id.buttonBack);
        buttonNext = findViewById(R.id.buttonNext);

        OnboardingAdapter adapter = new OnboardingAdapter(
                this, titles, descriptions, lotties, this
        );
        viewPager.setAdapter(adapter);

        setupNavButtons(adapter);
    }

    private void setupNavButtons(OnboardingAdapter adapter) {
        updateButtons(0, adapter.getItemCount());

        buttonSkip.setOnClickListener(v -> {
            markCompleted();
            navigateToLogin();
            finish();
        });
        buttonBack.setOnClickListener(v ->
                viewPager.setCurrentItem(viewPager.getCurrentItem() - 1, true)
        );
        buttonNext.setOnClickListener(v -> {
            int next = viewPager.getCurrentItem() + 1;
            if (next < adapter.getItemCount()) {
                viewPager.setCurrentItem(next, true);
            } else {
                markCompleted();
                navigateToLogin();
                finish();
            }
        });

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int pos) {
                updateButtons(pos, adapter.getItemCount());
            }
        });
    }

    private void updateButtons(int pos, int count) {
        int last = count - 1;
        buttonSkip.setVisibility(pos == 0 ? View.VISIBLE : View.GONE);
        buttonBack.setVisibility(pos > 0 ? View.VISIBLE : View.GONE);
        buttonNext.setText(pos < last ? "Siguiente" : "Comenzar");
    }

    @Override
    public void onFaqClicked(View v) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.sheet_faq, null);
        sheet.setContentView(content);

        String[] qs = getResources().getStringArray(R.array.faq_questions);
        String[] as = getResources().getStringArray(R.array.faq_answers);
        FAQBottomSheet.setupSheet(content, qs, as);

        sheet.show();

        // EXPANDIR completamente
        View bottom = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottom != null) {
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottom);
            // altura total de pantalla
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            behavior.setPeekHeight(screenHeight);
            behavior.setFitToContents(true);
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    private void markCompleted() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_COMPLETED, true)
                .apply();
    }

    private void navigateToLogin() {
        startActivity(new Intent(this, LoginActivity.class));
    }
}