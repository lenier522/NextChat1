package cu.lenier.nextchat.BottomSheet;


import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import cu.lenier.nextchat.R;

public class FAQBottomSheet {
    public static void setupSheet(View root, String[] questions, String[] answers) {
        LinearLayout container = root.findViewById(R.id.llFaqContainer);
        container.removeAllViews();

        for (int i = 0; i < questions.length; i++) {
            View item = View.inflate(root.getContext(), R.layout.item_faq, null);
            TextView tvQ = item.findViewById(R.id.tvQuestion);
            TextView tvA = item.findViewById(R.id.tvAnswer);
            tvQ.setText(questions[i]);
            tvA.setText(answers[i]);
            container.addView(item);
        }
    }
}