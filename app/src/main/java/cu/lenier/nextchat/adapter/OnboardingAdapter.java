package cu.lenier.nextchat.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.airbnb.lottie.LottieAnimationView;

import cu.lenier.nextchat.R;

public class OnboardingAdapter extends RecyclerView.Adapter<OnboardingAdapter.PageViewHolder> {

    public interface OnFaqClickListener { void onFaqClicked(View v); }

    private final Context ctx;
    private final String[] titles, descriptions, lotties;
    private final OnFaqClickListener faqListener;

    public OnboardingAdapter(Context ctx,
                             String[] titles,
                             String[] descriptions,
                             String[] lotties,
                             OnFaqClickListener listener) {
        this.ctx = ctx;
        this.titles = titles;
        this.descriptions = descriptions;
        this.lotties = lotties;
        this.faqListener = listener;
    }

    @NonNull @Override
    public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx)
                .inflate(R.layout.item_onboarding_page, parent, false);
        return new PageViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder h, int pos) {
        h.tvTitle.setText(titles[pos]);
        h.tvDescription.setText(descriptions[pos]);
        h.lottieView.setAnimation(lotties[pos]);

        if (pos == titles.length - 1) {
            h.buttonFaq.setVisibility(View.VISIBLE);
            h.buttonFaq.setOnClickListener(faqListener::onFaqClicked);
        } else {
            h.buttonFaq.setVisibility(View.GONE);
        }
    }

    @Override public int getItemCount() { return titles.length; }

    static class PageViewHolder extends RecyclerView.ViewHolder {
        LottieAnimationView lottieView;
        TextView tvTitle, tvDescription;
        Button buttonFaq;

        PageViewHolder(@NonNull View itemView) {
            super(itemView);
            lottieView    = itemView.findViewById(R.id.lottieView);
            tvTitle       = itemView.findViewById(R.id.tvTitle);
            tvDescription = itemView.findViewById(R.id.tvDescription);
            buttonFaq     = itemView.findViewById(R.id.buttonFaq);
        }
    }
}
