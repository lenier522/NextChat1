package cu.lenier.nextchat.adapter;

import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.audiofx.Visualizer;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

import cu.lenier.nextchat.R;
import cu.lenier.nextchat.data.AppDatabase;
import cu.lenier.nextchat.data.MessageDao;
import cu.lenier.nextchat.model.Message;
import cu.lenier.nextchat.util.MailHelper;
import rm.com.audiowave.AudioWaveView;
import rm.com.audiowave.OnSamplingListener;

public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final String TAG = "MessageAdapter";

    private static final int TYPE_DATE           = 0;
    private static final int TYPE_SENT           = 1;
    private static final int TYPE_AUDIO_SENT     = 2;
    private static final int TYPE_RECEIVED       = 3;
    private static final int TYPE_AUDIO_RECV     = 4;
    private static final int TYPE_IMAGE_SENT     = 5;
    private static final int TYPE_IMAGE_RECV     = 6;

    private final Handler uiHandler = new Handler();
    private final List<Object> items = new ArrayList<>();

    public void setMessages(List<Message> msgs) {
        items.clear();
        String lastDate = null;
        SimpleDateFormat fmt = new SimpleDateFormat("d 'de' MMM", new Locale("es"));
        for (Message m : msgs) {
            String thisDate = fmt.format(new Date(m.timestamp));
            if (!thisDate.equals(lastDate)) {
                items.add(thisDate);
                lastDate = thisDate;
            }
            items.add(m);
        }
        notifyDataSetChanged();
    }

    // PARA SWIPE-TO-REPLY
    public Message getMessageAt(int pos) {
        Object o = items.get(pos);
        return o instanceof Message ? (Message) o : null;
    }


    @Override
    public int getItemViewType(int pos) {
        Object o = items.get(pos);
        if (o instanceof String) {
            return TYPE_DATE;
        }
        Message m = (Message) o;
        if (m.sent) {
            if ("audio".equals(m.type)) return TYPE_AUDIO_SENT;
            if ("image".equals(m.type)) return TYPE_IMAGE_SENT;
            return TYPE_SENT;
        } else {
            if ("audio".equals(m.type)) return TYPE_AUDIO_RECV;
            if ("image".equals(m.type)) return TYPE_IMAGE_RECV;
            return TYPE_RECEIVED;
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (vt == TYPE_DATE) {
            View v = inf.inflate(R.layout.item_date_header, parent, false);
            return new DateVH(v);
        }
        int layout;
        switch (vt) {
            case TYPE_SENT:        layout = R.layout.item_message_sent;           break;
            case TYPE_AUDIO_SENT:  layout = R.layout.item_message_audio_sent;     break;
            case TYPE_RECEIVED:    layout = R.layout.item_message_received;       break;
            case TYPE_AUDIO_RECV:  layout = R.layout.item_message_audio_received; break;
            case TYPE_IMAGE_SENT:  layout = R.layout.item_message_image_sent;     break;
            default:               layout = R.layout.item_message_image_received; break;
        }
        View v = inf.inflate(layout, parent, false);
        return new MessageVH(v, vt);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
        if (h instanceof DateVH) {
            ((DateVH) h).bind((String) items.get(pos));
        } else {
            try {
                ((MessageVH) h).bind((Message) items.get(pos));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ViewHolder para los headers de fecha
    static class DateVH extends RecyclerView.ViewHolder {
        private final TextView tvDate;
        DateVH(View itemView) {
            super(itemView);
            tvDate = itemView.findViewById(R.id.tvDateHeader);
        }
        void bind(String date) {
            tvDate.setText(date);
        }
    }

    // Tu ViewHolder original renombrado
    class MessageVH extends RecyclerView.ViewHolder {
        TextView tvReplyQuote,tvBody, tvTime, tvDuration;
        ImageView ivState, ivImage;
        ImageButton btnPlay;
        AudioWaveView waveform;
        int type;
        private MediaPlayer mp;
        private Visualizer visualizer;

        private final Runnable updater = new Runnable() {
            @Override public void run() {
                if (mp != null && mp.isPlaying()) {
                    int pos = mp.getCurrentPosition();
                    int dur = mp.getDuration();
                    float pct = pos * 100f / dur;
                    waveform.setProgress(pct);
                    uiHandler.postDelayed(this, 50);
                }
            }
        };

        MessageVH(View iv, int t) {
            super(iv);
            type = t;
            tvReplyQuote = iv.findViewById(R.id.tvReplyPreview);
            if (type == TYPE_SENT || type == TYPE_RECEIVED) {
                tvBody = iv.findViewById(R.id.tvBody);
            } else if (type == TYPE_IMAGE_SENT || type == TYPE_IMAGE_RECV) {
                ivImage = iv.findViewById(R.id.ivImage);
            } else {
                btnPlay    = iv.findViewById(R.id.btnPlay);
                waveform   = iv.findViewById(R.id.waveform);
                tvDuration = iv.findViewById(R.id.tvDuration);
            }
            tvTime  = iv.findViewById(R.id.tvTime);
            ivState = iv.findViewById(R.id.ivState);

            iv.setOnClickListener(view -> {
                Message m = (Message) items.get(getAdapterPosition());
                var dao = AppDatabase.getInstance(view.getContext()).messageDao();
                if (m.sent && m.sendState == Message.STATE_FAILED) {
                    new AlertDialog.Builder(view.getContext())
                            .setTitle("Error al enviar")
                            .setItems(new CharSequence[]{"Reintentar","Eliminar"}, (d,w) -> {
                                if (w == 0) Executors.newSingleThreadExecutor().execute(() -> {
                                    m.sendState = Message.STATE_PENDING;
                                    dao.update(m);
                                    if ("text".equals(m.type)) MailHelper.sendEmail(view.getContext(), m);
                                    else if ("audio".equals(m.type)) MailHelper.sendAudioEmail(view.getContext(), m);
                                    else MailHelper.sendImageEmail(view.getContext(), m);
                                });
                                else Executors.newSingleThreadExecutor().execute(() -> dao.deleteById(m.id));
                            }).show();
                } else {
                    new AlertDialog.Builder(view.getContext())
                            .setTitle("Eliminar mensaje")
                            .setMessage("¿Eliminar este mensaje?")
                            .setPositiveButton("Eliminar", (d,w) -> Executors.newSingleThreadExecutor()
                                    .execute(() -> dao.deleteById(m.id)))
                            .setNegativeButton("Cancelar", null)
                            .show();
                }
            });
        }

        void bind(Message m) throws IOException {
            // Cita reply
            if (tvReplyQuote != null) {
                if (m.inReplyToBody != null) {
                    tvReplyQuote.setVisibility(View.VISIBLE);
                    String snip = m.inReplyToBody.length() > 30
                            ? m.inReplyToBody.substring(0, 30) + "…"
                            : m.inReplyToBody;
                    tvReplyQuote.setText("↳ " + snip);
                } else {
                    tvReplyQuote.setVisibility(View.GONE);
                }
            }
            // Cuerpo
            if (type == TYPE_SENT || type == TYPE_RECEIVED) {
                tvBody.setText(m.body);
                tvTime.setText(new SimpleDateFormat("hh:mm a", Locale.getDefault())
                        .format(new Date(m.timestamp)));
            } else if (type == TYPE_IMAGE_SENT || type == TYPE_IMAGE_RECV) {
                File f = new File(m.attachmentPath);
                Glide.with(ivImage.getContext())
                        .load(f)
                        .into(ivImage);
                tvTime.setText(new SimpleDateFormat("hh:mm a", Locale.getDefault())
                        .format(new Date(m.timestamp)));
            } else {
                tvTime.setText(new SimpleDateFormat("hh:mm a", Locale.getDefault())
                        .format(new Date(m.timestamp)));
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                mmr.setDataSource(itemView.getContext(), Uri.fromFile(new File(m.attachmentPath)));
                int dur = Integer.parseInt(
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                );
                mmr.release();
                tvDuration.setText(new SimpleDateFormat("mm:ss",Locale.getDefault())
                        .format(new Date(dur)));
                waveform.setProgress(0f);
                btnPlay.setOnClickListener(v -> {
                    if (mp != null && mp.isPlaying()) stop(); else play(m);
                });
            }
            if (ivState != null) {
                if (m.sent) {
                    int res = R.mipmap.ic_state_failed;
                    if (m.sendState == Message.STATE_PENDING)   res = R.mipmap.ic_state_pending;
                    else if (m.sendState == Message.STATE_SENT) res = R.mipmap.ic_state_sent;
                    ivState.setImageResource(res);
                    ivState.setVisibility(View.VISIBLE);
                } else {
                    ivState.setVisibility(View.GONE);
                }
            }
        }

        private void play(Message m) {
            try {
                mp = new MediaPlayer();
                mp.setDataSource(m.attachmentPath);
                mp.prepare();
                mp.start();

                visualizer = new Visualizer(mp.getAudioSessionId());
                visualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
                visualizer.setDataCaptureListener(
                        new Visualizer.OnDataCaptureListener() {
                            @Override
                            public void onWaveFormDataCapture(Visualizer vis, byte[] data, int rate) {
                                waveform.setRawData(data, new OnSamplingListener() {
                                    @Override public void onComplete() { }
                                });
                            }
                            @Override public void onFftDataCapture(Visualizer vis, byte[] fft, int rate) { }
                        },
                        Visualizer.getMaxCaptureRate() / 2,
                        true,  // waveform
                        false  // fft
                );
                visualizer.setEnabled(true);

                uiHandler.post(updater);
                btnPlay.setImageResource(R.mipmap.ic_pause);

                mp.setOnCompletionListener(p -> stop());
            } catch (IOException e) {
                Log.e(TAG, "play error", e);
            }
        }

        private void stop() {
            if (visualizer != null) {
                visualizer.setEnabled(false);
                visualizer.release();
                visualizer = null;
            }
            if (mp != null) {
                try { mp.stop(); } catch (IllegalStateException ignored) {}
                mp.release();
                mp = null;
            }
            uiHandler.removeCallbacks(updater);
            waveform.setProgress(0f);
            btnPlay.setImageResource(R.mipmap.ic_play_arrow);
        }

    }
}