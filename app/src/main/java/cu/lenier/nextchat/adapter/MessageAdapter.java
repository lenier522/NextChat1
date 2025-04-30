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
    private List<Message> messages;
    private final Handler uiHandler = new Handler();

    public void setMessages(List<Message> msgs) {
        messages = msgs;
        notifyDataSetChanged();
    }

    @Override public int getItemViewType(int pos) {
        Message m = messages.get(pos);
        if (m.sent) {
            if ("audio".equals(m.type)) return 2;
            if ("image".equals(m.type)) return 5;
            return 1;
        } else {
            if ("audio".equals(m.type)) return 4;
            if ("image".equals(m.type)) return 6;
            return 3;
        }
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
        int layout;
        switch (vt) {
            case 1: layout = R.layout.item_message_sent;            break;
            case 2: layout = R.layout.item_message_audio_sent;      break;
            case 3: layout = R.layout.item_message_received;        break;
            case 4: layout = R.layout.item_message_audio_received;  break;
            case 5: layout = R.layout.item_message_image_sent;      break;
            default: layout = R.layout.item_message_image_received; break;
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new VH(v, vt);
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
        try {
            ((VH)h).bind(messages.get(pos));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override public int getItemCount() {
        return messages == null ? 0 : messages.size();
    }

    class VH extends RecyclerView.ViewHolder {
        TextView tvBody, tvTime, tvDuration;
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

        VH(View iv, int t) {
            super(iv);
            type = t;
            if (type == 1 || type == 3) {
                tvBody = iv.findViewById(R.id.tvBody);
            } else if (type == 5 || type == 6) {
                ivImage = iv.findViewById(R.id.ivImage);
            } else {
                btnPlay    = iv.findViewById(R.id.btnPlay);
                waveform   = iv.findViewById(R.id.waveform);
                tvDuration = iv.findViewById(R.id.tvDuration);
            }
            tvTime  = iv.findViewById(R.id.tvTime);
            ivState = iv.findViewById(R.id.ivState);

            iv.setOnClickListener(view -> {
                Message m = messages.get(getAdapterPosition());
                MessageDao dao = AppDatabase.getInstance(view.getContext()).messageDao();
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
            Log.d(TAG, "bind() tipo=" + m.type + " path=" + m.attachmentPath);
            if (type == 1 || type == 3) {
                tvBody.setText(m.body);
                tvTime.setText(new SimpleDateFormat("hh:mm a", Locale.getDefault())
                        .format(new Date(m.timestamp)));
            } else if (type == 5 || type == 6) {
                File f = new File(m.attachmentPath);
                Log.d(TAG, "  Imagen existe? " + f.exists() + " tamaño=" + f.length());
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
                    boolean playing = false;
                    if (mp != null) {
                        try {
                            playing = mp.isPlaying();
                        } catch (IllegalStateException e) {
                            // reproductor no en estado válido -> tratar como detenido
                            playing = false;
                        }
                    }
                    if (playing) {
                        stop();
                    } else {
                        play(m);
                    }
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
                try {
                    mp.stop();
                } catch (IllegalStateException ignored) { }
                mp.release();
                mp = null;
            }
            uiHandler.removeCallbacks(updater);
            waveform.setProgress(0f);
            btnPlay.setImageResource(R.mipmap.ic_play_arrow);
        }
    }
}
