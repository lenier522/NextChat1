package cu.lenier.nextchat.BottomSheet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;

import cu.lenier.nextchat.R;
import cu.lenier.nextchat.model.Message;
import cu.lenier.nextchat.ui.ChatActivity;
import cu.lenier.nextchat.util.MailHelper;

public class CameraBottomSheet extends BottomSheetDialogFragment {
    private static final String ARG_CONTACT    = "contact";
    private static final String IMG_SUBJ       = "NextChat Image";

    private PreviewView previewView;
    private ImageView ivPhotoPreview;
    private View cameraControls, previewControls;
    private ImageButton btnShutter, btnSwitch, btnFlash, btnDiscard, btnAccept;

    private static final String ARG_REPLY_ID   = "reply_id";
    private static final String ARG_REPLY_BODY = "reply_body";
    private static final String ARG_REPLY_TYPE = "reply_type";

    private ProcessCameraProvider cameraProvider;
    private Preview previewUseCase;
    private ImageCapture imageCapture;
    private Camera camera;
    private CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
    private boolean flashEnabled = false;

    private File lastPhotoFile;
    private String contact;
    private Long replyId;
    private String replyBody, replyType;

    public static CameraBottomSheet newInstance(
            String contact,
            Long   replyId,
            String replyBody,
            String replyType
    ) {
        CameraBottomSheet sheet = new CameraBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_CONTACT, contact);
        if (replyId != null) {
            args.putLong(ARG_REPLY_ID, replyId);
            args.putString(ARG_REPLY_BODY, replyBody);
            args.putString(ARG_REPLY_TYPE, replyType);
        }
        sheet.setArguments(args);
        return sheet;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle a = getArguments();
        contact = (a != null) ? a.getString(ARG_CONTACT) : null;
        if (a != null && a.containsKey(ARG_REPLY_ID)) {
            replyId   = a.getLong(ARG_REPLY_ID);
            replyBody = a.getString(ARG_REPLY_BODY);
            replyType = a.getString(ARG_REPLY_TYPE);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.bs_camera, container, false);

        previewView     = root.findViewById(R.id.previewView);
        ivPhotoPreview  = root.findViewById(R.id.ivPhotoPreview);
        cameraControls  = root.findViewById(R.id.cameraControls);
        previewControls = root.findViewById(R.id.previewControls);
        btnShutter      = root.findViewById(R.id.btnShutter);
        btnSwitch       = root.findViewById(R.id.btnSwitch);
        btnFlash        = root.findViewById(R.id.btnFlash);
        btnDiscard      = root.findViewById(R.id.btnDiscard);
        btnAccept       = root.findViewById(R.id.btnAccept);

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(requireContext());
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(requireContext()));

        btnShutter.setOnClickListener(v -> takePhoto());
        btnSwitch.setOnClickListener(v -> {
            cameraSelector = cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA
                    ? CameraSelector.DEFAULT_FRONT_CAMERA
                    : CameraSelector.DEFAULT_BACK_CAMERA;
            bindCameraUseCases();
        });
        btnFlash.setOnClickListener(v -> {
            flashEnabled = !flashEnabled;
            if (camera != null) {
                camera.getCameraControl().enableTorch(flashEnabled);
            }
        });

        btnDiscard.setOnClickListener(v -> {
            if (lastPhotoFile != null && lastPhotoFile.exists()) {
                lastPhotoFile.delete();
            }
            showCameraControls();
        });
        btnAccept.setOnClickListener(v -> {
            sendImageMessage();
            dismiss();
        });

        return root;
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();

        previewUseCase = new Preview.Builder().build();
        previewUseCase.setSurfaceProvider(previewView.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder()
                .setFlashMode(flashEnabled
                        ? ImageCapture.FLASH_MODE_ON
                        : ImageCapture.FLASH_MODE_OFF)
                .build();

        camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, previewUseCase, imageCapture
        );
    }

    private void takePhoto() {
        if (imageCapture == null) return;

        File dir = new File(requireContext().getExternalFilesDir(null), "images_enviadas");
        if (!dir.exists()) dir.mkdirs();

        lastPhotoFile = new File(dir, "photo_" + System.currentTimeMillis() + ".jpg");

        ImageCapture.OutputFileOptions opts =
                new ImageCapture.OutputFileOptions.Builder(lastPhotoFile).build();

        imageCapture.takePicture(opts,
                ContextCompat.getMainExecutor(requireContext()),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                        ivPhotoPreview.setImageURI(Uri.fromFile(lastPhotoFile));
                        ivPhotoPreview.setVisibility(View.VISIBLE);
                        previewView.setVisibility(View.GONE);
                        showPreviewControls();
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exc) {
                        exc.printStackTrace();
                    }
                });
    }

    private void showPreviewControls() {
        cameraControls.setVisibility(View.GONE);
        previewControls.setVisibility(View.VISIBLE);
    }

    private void showCameraControls() {
        previewControls.setVisibility(View.GONE);
        cameraControls.setVisibility(View.VISIBLE);
        ivPhotoPreview.setVisibility(View.GONE);
        previewView.setVisibility(View.VISIBLE);
    }

    private void sendImageMessage() {
        if (lastPhotoFile == null) return;

        // Comprimir imagen (mediano 600x600)
        Bitmap original = BitmapFactory.decodeFile(lastPhotoFile.getAbsolutePath());
        File compressed = new File(lastPhotoFile.getParent(), "photo_med_" + lastPhotoFile.getName());
        Bitmap medBmp = Bitmap.createScaledBitmap(original, 600, 600, true);
        try (FileOutputStream fos = new FileOutputStream(compressed)) {
            medBmp.compress(Bitmap.CompressFormat.JPEG, 80, fos);
        } catch (Exception e) {
            e.printStackTrace();
        }

        String me = requireContext()
                .getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .getString("email", "");

        Message msg = new Message();
        msg.fromAddress    = me;
        msg.toAddress      = contact;
        msg.subject        = IMG_SUBJ;
        msg.timestamp      = System.currentTimeMillis();
        msg.sent           = true;
        msg.read           = true;
        msg.type           = "image";
        msg.attachmentPath = compressed.getAbsolutePath();
        msg.sendState      = Message.STATE_PENDING;

        if (replyId != null) {
            msg.inReplyToId   = replyId;
            msg.inReplyToBody = replyBody;
            msg.inReplyToType = replyType;
            if (getActivity() instanceof ChatActivity) {
                ((ChatActivity) getActivity()).exitReplyMode();
            }
        }

        MailHelper.sendImageEmail(requireContext(), msg);
    }
}
