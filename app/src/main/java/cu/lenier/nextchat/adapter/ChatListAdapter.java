package cu.lenier.nextchat.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import cu.lenier.nextchat.R;
import cu.lenier.nextchat.config.AppConfig;

public class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.VH> {
    public interface OnItemClickListener { void onClick(String contact); }
    public interface OnItemLongClickListener { void onLong(String contact); }

    private List<String> contacts;
    private Map<String,Integer> unreadMap;
    private Map<String,String> previewMap;
    private Map<String,Long> timeMap;
    private Map<String,String> nameMap;    // email → profile name
    private Map<String,String> avatarMap;  // email → local file path

    private OnItemClickListener clickListener;
    private OnItemLongClickListener longClickListener;

    public void setContacts(List<String> c)              { contacts = c; notifyDataSetChanged(); }
    public void setUnreadMap(Map<String,Integer> m)      { unreadMap = m; notifyDataSetChanged(); }
    public void setPreviewMap(Map<String,String> m)      { previewMap = m; notifyDataSetChanged(); }
    public void setTimeMap(Map<String,Long> m)           { timeMap = m; notifyDataSetChanged(); }
    public void setNameMap(Map<String,String> m)         { nameMap = m; notifyDataSetChanged(); }
    public void setAvatarMap(Map<String,String> m)       { avatarMap = m; notifyDataSetChanged(); }
    public void setOnItemClickListener(OnItemClickListener l)         { clickListener = l; }
    public void setOnItemLongClickListener(OnItemLongClickListener l) { longClickListener = l; }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat_contact, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(contacts.get(position));
    }

    @Override public int getItemCount() {
        return contacts == null ? 0 : contacts.size();
    }

    class VH extends RecyclerView.ViewHolder {
        ImageView ivAvatar, ivVerified;
        TextView tvName, tvPreview, tvTime, tvBadge;

        VH(View v) {
            super(v);
            ivAvatar   = v.findViewById(R.id.ivAvatar);
            ivVerified = v.findViewById(R.id.ivVerified);
            tvName     = v.findViewById(R.id.tvName);
            tvPreview  = v.findViewById(R.id.tvPreview);
            tvTime     = v.findViewById(R.id.tvTime);
            tvBadge    = v.findViewById(R.id.tvBadge);

            v.setOnClickListener(view -> {
                if (clickListener != null)
                    clickListener.onClick(contacts.get(getAdapterPosition()));
            });
            v.setOnLongClickListener(view -> {
                if (longClickListener != null) {
                    longClickListener.onLong(contacts.get(getAdapterPosition()));
                    return true;
                }
                return false;
            });
        }

        void bind(String contact) {
            Context ctx = itemView.getContext();
            String key = contact.trim().toLowerCase();

            // 1) Avatar: cargar desde fichero local
            if (avatarMap != null && avatarMap.containsKey(key)) {
                File avatarFile = new File(avatarMap.get(key));
                if (avatarFile.exists()) {
                    Glide.with(ctx)
                            .load(avatarFile)
                            .circleCrop()
                            .into(ivAvatar);
                } else {
                    ivAvatar.setImageResource(R.mipmap.ic_profile_default);
                }
            } else {
                ivAvatar.setImageResource(R.mipmap.ic_profile_default);
            }

            // 2) Nombre (perfil si existe, sino alias/email)
            if (nameMap != null && nameMap.containsKey(key)) {
                tvName.setText(nameMap.get(key));
            } else {
                tvName.setText(AppConfig.getDisplayName(contact));
            }

            // 3) Verificado
            ivVerified.setVisibility(
                    AppConfig.isVerified(contact) ? View.VISIBLE : View.GONE
            );

            // 4) Vista previa
            String prev = (previewMap != null && previewMap.containsKey(contact))
                    ? previewMap.get(contact) : "";
            tvPreview.setText(prev);

            // 5) Hora del último mensaje
            if (timeMap != null && timeMap.containsKey(contact)) {
                long ts = timeMap.get(contact);
                tvTime.setText(new SimpleDateFormat("hh:mm a", Locale.getDefault())
                        .format(new Date(ts)));
            } else {
                tvTime.setText("");
            }

            // 6) Badge de no leídos
            int cnt = (unreadMap != null && unreadMap.containsKey(contact))
                    ? unreadMap.get(contact) : 0;
            if (cnt > 0) {
                tvBadge.setVisibility(View.VISIBLE);
                tvBadge.setText(String.valueOf(cnt));
            } else {
                tvBadge.setVisibility(View.GONE);
            }
        }
    }
}
