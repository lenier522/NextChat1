package cu.lenier.nextchat.adapter;

import android.content.Intent;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;
import cu.lenier.nextchat.R;
import cu.lenier.nextchat.model.ContactItem;
import cu.lenier.nextchat.ui.ChatActivity;

public class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.VH> {
    public interface OnItemClickListener {
        void onItemClick(String email);
    }

    private List<ContactItem> items;
    private OnItemClickListener listener;

    public ContactAdapter(List<ContactItem> data, OnItemClickListener l) {
        this.items = data;
        this.listener = l;
    }

    public void updateData(List<ContactItem> data) {
        this.items = data;
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_contact, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH holder, int pos) {
        ContactItem c = items.get(pos);

        // Mostrar nombre real; fallback si está vacío
        String name = !TextUtils.isEmpty(c.name)
                ? c.name
                : c.email.substring(0, c.email.indexOf('@'));
        holder.tvName.setText(name);
        holder.tvEmail.setText(c.email);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(c.email);
            }
        });
    }

    @Override public int getItemCount() {
        return items == null ? 0 : items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvEmail;
        VH(View v) {
            super(v);
            tvName  = v.findViewById(R.id.tvName);
            tvEmail = v.findViewById(R.id.tvEmail);
        }
    }
}