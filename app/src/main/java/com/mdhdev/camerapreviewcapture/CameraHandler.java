package com.mdhdev.camerapreviewcapture;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;


import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;
import com.google.firebase.ml.vision.text.FirebaseVisionText;
import com.google.firebase.ml.vision.text.FirebaseVisionTextRecognizer;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import static android.content.Context.CAMERA_SERVICE;

public class CameraHandler {

    private enum CameraHandlerType {StillImage, LivePreview};
    private CameraHandlerType typeOfHandler;

    private static final int PREVIEW_STATE = 0;
    private static final int AWAIT_LOCK_STATE = 1;
    private static final int AWAITING_PRECAPTURE_STATE = 2;
    private static final int AWAITING_NON_PRECAPTURE_STATE = 3;
    private static final int PICTURE_TAKEN_STATE = 4;
    private int currentCameraState = 0;

    private static final int RECOGNIZED_TEXT = 0;

    private CameraManager cameraManager;
    private Context ctx;
    private TextureView cameraView;
    private Size streamsize;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder captureBuilder;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private int rotation;
    private int texturewidth;
    private int textureheight;
    private String selectedcameraId;
    private TextView infoText;
    private ImageButton snapshotButton;

    File fileToSave;

    private HandlerThread backGroundThread;
    private Handler backGroundHandler;

    private Handler uiHandler;

    private Handler recHandler;
    private HandlerThread recHandlerThread;
    private TextRecognitionEngine textEngine;

    //Size is enough for text capture
    private final static int TEXT_CAPTURE_WIDTH = 480;
    private final static int TEXT_CAPTURE_HEIGHT = 360;
    private final static int FRAME_DETECT_THRESHOLD = 3;

    private OnTextRecognizedListener listener;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    //Used for live feed
    public CameraHandler(Context ctx, TextureView textureView, TextView textInfo) {

        this.ctx = ctx;
        this.cameraView = textureView;
        this.infoText = textInfo;
        this.typeOfHandler = CameraHandlerType.LivePreview;

        cameraView.setSurfaceTextureListener(textureListener);

    }

    //Used for still image capture
    public CameraHandler(Context ctx, TextureView textureView, ImageButton snapshotButton) {

        this.ctx = ctx;
        this.cameraView = textureView;
        this.snapshotButton = snapshotButton;
        this.typeOfHandler = CameraHandlerType.StillImage;
        cameraView.setSurfaceTextureListener(textureListener);
        fileToSave = new File(ctx.getExternalFilesDir(null), "temp_pic.jpg");

    }

    private void startBackGroundThread(){
        backGroundThread = new HandlerThread("Background Thread");
        backGroundThread.start();
        backGroundHandler = new Handler(backGroundThread.getLooper());
    }
    /**
     * Get the angle by which an image must be rotated given the device's current
     * orientation.
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private int getRotationCompensation(String cameraId, Activity activity, Context context)
            throws CameraAccessException {
        // Get the device's current rotation relative to its "native" orientation.
        // Then, from the ORIENTATIONS table, look up the angle the image must be
        // rotated to compensate for the device's rotation.
        int deviceRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int rotationCompensation = ORIENTATIONS.get(deviceRotation);

        // On most devices, the sensor orientation is 90 degrees, but for some
        // devices it is 270 degrees. For devices with a sensor orientation of
        // 270, rotate the image an additional 180 ((270 + 270) % 360) degrees.
        CameraManager cameraManager = (CameraManager) context.getSystemService(CAMERA_SERVICE);

        if(cameraManager == null) return 0;

        int sensorOrientation = cameraManager
                .getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION);
        rotationCompensation = (rotationCompensation + sensorOrientation + 270) % 360;

        // Return the corresponding FirebaseVisionImageMetadata rotation value.
        int result;
        switch (rotationCompensation) {
            case 0:
                result = FirebaseVisionImageMetadata.ROTATION_0;
                break;
            case 90:
                result = FirebaseVisionImageMetadata.ROTATION_90;
                break;
            case 180:
                result = FirebaseVisionImageMetadata.ROTATION_180;
                break;
            case 270:
                result = FirebaseVisionImageMetadata.ROTATION_270;
                break;
            default:
                result = FirebaseVisionImageMetadata.ROTATION_0;
                Log.e("CAMERA_ROT_COMPENSATION", "Bad rotation value: " + rotationCompensation);
        }
        return result;
    }


    private TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            texturewidth = width;
            textureheight = height;
            openCamera();
            transformImage(width, height);

        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

            texturewidth = width;
            textureheight = height;

            openCamera();
            transformImage(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            closeCamera();
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

    private void openCamera() {

        if (!cameraView.isAvailable()) {
            cameraView.setSurfaceTextureListener(textureListener);
            return;
        }

        cameraManager = (CameraManager) ctx.getSystemService(CAMERA_SERVICE);

        try {

            selectedcameraId = null;

            for (String cameraId : cameraManager.getCameraIdList()) {

                //Get properties from the selected camera
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);

                //We want to use back camera
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {

                    //Get the resolutions from the selected camera that can be used in a TextureView
                    StreamConfigurationMap streamConfigurationMap = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                    streamsize = chooseOptimalPreviewSize(streamConfigurationMap.getOutputSizes(SurfaceTexture.class), texturewidth, textureheight);
                    selectedcameraId = cameraId;
                }
            }

            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED || selectedcameraId == null) {

                return;
            }


            rotation = getRotationCompensation(selectedcameraId, (Activity) ctx, ctx);

            startBackGroundThread();

            cameraManager.openCamera(selectedcameraId, camerastateCallback, backGroundHandler);

            //Keep screen on
            ((Activity) ctx).getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //This selects the closest matching image size the camera outputs to the surface
    private Size chooseOptimalPreviewSize(Size[] sizes_available, int width, int height) {

        ArrayList<Size> sizelist = new ArrayList<>();

        for (Size size : sizes_available) {

            //Landscape mode
            if (width > height) {

                //If the size is bigger than our preview window
                if (size.getWidth() > width && size.getHeight() > height) {

                    sizelist.add(size);
                }
            }
            //Portrait mode
            else {

                if (size.getWidth() > height && size.getHeight() > width) {

                    sizelist.add(size);
                }
            }
        }

        //Select the closest match
        if (sizelist.size() > 0) {

            //Compare resolutions in list to find the closest
            Size optimal_size = Collections.min(sizelist, new Comparator<Size>() {

                @Override
                public int compare(Size o1, Size o2) {

                    return Long.signum((o1.getWidth() * o1.getHeight()) - (o2.getWidth() * o2.getHeight()));
                }
            });

            return optimal_size;
        }

        //If no optimal found, return the biggest
        return sizes_available[0];
    }

    private CameraDevice.StateCallback camerastateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;

            startCameraCapture();

        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
        }
    };

    //Transforms the preview when rotating
    private void transformImage(int width, int height) {
        if (streamsize == null || cameraView == null) {
            return;
        }
        Matrix matrix = new Matrix();

        WindowManager windowManager = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        int rotation = windowManager.getDefaultDisplay().getRotation();


        RectF textureRectF = new RectF(0, 0, width, height);
        RectF previewRectF = new RectF(0, 0, streamsize.getHeight(), streamsize.getWidth());
        float centerX = textureRectF.centerX();
        float centerY = textureRectF.centerY();

        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            previewRectF.offset(centerX - previewRectF.centerX(),
                    centerY - previewRectF.centerY());
            matrix.setRectToRect(textureRectF, previewRectF, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) width / streamsize.getWidth(),
                    (float) height / streamsize.getHeight());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        cameraView.setTransform(matrix);
    }


    private void startCameraCapture() {

        if (cameraDevice == null || !cameraView.isAvailable() || selectedcameraId == null) {
            return;
        }

        //Texture for preview window
        SurfaceTexture texture = cameraView.getSurfaceTexture();
        if (texture == null) {
            return;
        }
        texture.setDefaultBufferSize(texturewidth, textureheight);
        Surface surface = new Surface(texture);

        //Imagereader for images used for textrecognition
        if(typeOfHandler == CameraHandlerType.LivePreview){
            imageReader = ImageReader.newInstance(TEXT_CAPTURE_WIDTH, TEXT_CAPTURE_HEIGHT, ImageFormat.JPEG, 1);
        }
        else{
            imageReader = ImageReader.newInstance(TEXT_CAPTURE_WIDTH, TEXT_CAPTURE_HEIGHT, ImageFormat.YUV_420_888, 1);
        }

        uiHandler = new Handler(Looper.getMainLooper()){

            @Override
            public void handleMessage(Message msg) {

                switch(msg.what){

                    //Get Firebase text object
                    case RECOGNIZED_TEXT:

                        //TODO Change to FirebaseVisionDocumentText
                        listener.onTextRecognized((FirebaseVisionText)msg.obj);

                        break;

                    //Stop live camera feed
                    case 1:

                        textEngine.stop();
                        cameraDevice.close();
                        break;

                        default:
                            break;
                }

            }
        };

        //Setup a capture request
        try {
            captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

        } catch (Exception e) {
            e.printStackTrace();
        }

        //Setup the capture session

        List<Surface> outputSurfaces = new LinkedList<>();


        //Thread for the text recognition
        recHandlerThread = new HandlerThread("Live Rec Text");
        recHandlerThread.start();
        recHandler = new Handler(recHandlerThread.getLooper());

        //If Live preview capture is used
        if(typeOfHandler == CameraHandlerType.LivePreview){

            textEngine = new TextRecognitionEngine(FRAME_DETECT_THRESHOLD,uiHandler);
            recHandler.post(textEngine);

            captureBuilder.addTarget(imageReader.getSurface());
        }
        //If Still image capture is used
        else if(typeOfHandler == CameraHandlerType.StillImage){

            snapshotButton.setOnClickListener(onSnapshotClick);
        }



        imageReader.setOnImageAvailableListener(ImageAvailable, backGroundHandler);
        outputSurfaces.add(imageReader.getSurface());
        outputSurfaces.add(surface);

        captureBuilder.addTarget(surface);


        try {
            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    getUpdatedPreview();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                }
            }, null);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void getUpdatedPreview() {
        if (cameraDevice == null) {
            return;
        }

        captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

        try {
            captureSession.setRepeatingRequest(captureBuilder.build(), captureCallback, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {


        private void process(CaptureResult result){

            switch (currentCameraState){

                case PREVIEW_STATE: {
                    // We have nothing to do when the camera preview is working normally.
                    break;
                }
                case AWAIT_LOCK_STATE: {

                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);

                    if (afState == null) {
                        snapImage();

                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);

                        if (aeState == null ||
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            currentCameraState = PICTURE_TAKEN_STATE;
                            snapImage();
                        } else {
                            preCaptureSequence();
                        }
                    }
                    break;
                }
                case AWAITING_PRECAPTURE_STATE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);

                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        currentCameraState = AWAITING_NON_PRECAPTURE_STATE;
                    }
                    break;
                }
                case AWAITING_NON_PRECAPTURE_STATE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);

                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        currentCameraState = PICTURE_TAKEN_STATE;
                        snapImage();
                    }
                    break;
                }

            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
            super.onCaptureProgressed(session, request, partialResult);

            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);

            process(result);
        }

    };

    private ImageReader.OnImageAvailableListener ImageAvailable = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(final ImageReader reader) {

            //If live preview is used
            if(typeOfHandler == CameraHandlerType.LivePreview){
                //Send the image frame to separate thread for text processing
                textEngine.addImageForProcessing(reader.acquireNextImage(), rotation);
            }
            //If still image capture is used, image is available here after button click
            else if(typeOfHandler == CameraHandlerType.StillImage){

                recHandler.post(textEngine = new TextRecognitionEngine(uiHandler,reader.acquireNextImage(),rotation,fileToSave));

            }
        }
    };

    private View.OnClickListener onSnapshotClick = new View.OnClickListener() {
        @Override
        public void onClick(View v) {

            //Begin the photo capture process
            focusLock();
        }
    };

    private void focusLock(){

        try {

            captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            currentCameraState = AWAIT_LOCK_STATE;
            captureSession.capture(captureBuilder.build(),captureCallback, backGroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }

    private void focusUnLock(){
        try {

            captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);

            captureSession.capture(captureBuilder.build(),captureCallback, backGroundHandler);


            //TODO What happens after snapshot?
            currentCameraState = PREVIEW_STATE;

            //Restart preview temporarily
            restartPreview();

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }

    private void preCaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            captureBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            currentCameraState = AWAITING_PRECAPTURE_STATE;
            captureSession.capture(captureBuilder.build(), captureCallback,
                    backGroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void snapImage(){

        try {
            CaptureRequest.Builder snapCaptureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            snapCaptureBuilder.addTarget(imageReader.getSurface());

            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            snapCaptureBuilder.set(CaptureRequest.JPEG_ORIENTATION,ORIENTATIONS.get(rotation));

            CameraCaptureSession.CaptureCallback snapCallback = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);

                    focusUnLock();
                }
            };

            captureSession.stopRepeating();
            captureSession.abortCaptures();
            captureSession.capture(snapCaptureBuilder.build(),snapCallback,null);


        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void showToast(final String msg){

        Activity activity = (Activity)ctx;

        if(activity == null) return;

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ctx.getApplicationContext(),msg,Toast.LENGTH_SHORT).show();
            }
        });

    }

    public void restartPreview(){


        if(captureSession == null) return;

        try {
            captureSession.setRepeatingRequest(captureBuilder.build(), captureCallback, backGroundHandler);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private void closeThreads() {

        if (backGroundThread != null) {

            backGroundThread.quitSafely();

            try {
                backGroundThread.join();
                backGroundThread = null;
                backGroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }


        if(recHandlerThread != null){

            textEngine.stop();
            recHandlerThread.quitSafely();
            try {
                recHandlerThread.join();
                recHandlerThread = null;
                recHandler = null;
                textEngine = null;
            }catch (InterruptedException e){
                e.printStackTrace();
            }

        }

    }

    public void closeCamera() {

        if(captureSession != null){
            captureSession.close();
            captureSession = null;
        }

        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        closeThreads();
    }

    public void setOnTextRecognizedListener(OnTextRecognizedListener listener){
        this.listener = listener;
    }

    public interface OnTextRecognizedListener{

        //TODO Change to FirebaseVisionDocumentText
        void onTextRecognized(FirebaseVisionText text);

    }

/*    private static class ImageSaver implements Runnable {

        *//**
         * The JPEG image
         *//*
        private final Image mImage;
        *//**
         * The file we save the image into.
         *//*
        private final File mFile;

        ImageSaver(Image image, File file) {
            mImage = image;
            mFile = file;
        }

        @Override
        public void run() {

            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            FileOutputStream output = null;

            try {
                output = new FileOutputStream(mFile);
                output.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

    }*/


    private static class TextRecognitionEngine implements Runnable {

        private Boolean isRunning = true;
        private Boolean isProcessing = false;
        private Boolean continousRun;

        private FirebaseVisionTextRecognizer textRecognizer;
        private FirebaseVisionImage firebaseVisionImage;
        private int rotation;
        private Image image;
        private File mFile;

        private int frameThreshold;
        private int frameCount = 0;
        private Handler uiHandler;


        private TextRecognitionEngine(Handler uiHandler,Image imageIn, int rotation, File saveFile){

            this.uiHandler = uiHandler;
            continousRun = false;
            mFile = saveFile;

            addImageForProcessing(imageIn,rotation);
        }

        private TextRecognitionEngine(int frameThreshold, Handler uihandler) {

            this.frameThreshold = frameThreshold;
            this.uiHandler = uihandler;
            continousRun = true;


        }

        private void saveFile(){

            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            FileOutputStream output = null;

            try {
                output = new FileOutputStream(mFile);
                output.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                image.close();
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

        }

        public synchronized void addImageForProcessing(Image imagein, int rotationin) {

            if(!continousRun){
                isRunning = true;
            }
            else if (!readyForProcessing() || isProcessing || !isRunning) {

                frameCount++;
                imagein.close();
                return;
            }

            this.image = imagein;
            this.rotation = rotationin;

            if (firebaseVisionImage != null) return;

            firebaseVisionImage = FirebaseVisionImage.fromMediaImage(image, rotation);

            if(!continousRun){
                saveFile();
            }
            else{
                image.close();
            }

            isProcessing = true;

            frameCount = 0;
        }

        //If threshold is met a new frame can be processed
        private boolean readyForProcessing() {

            if(frameCount >= frameThreshold || !continousRun){

                frameCount = 0;
                return true;
            }
            else{
                return false;
            }
        }


        public void stop(){
            isRunning = false;
        }

        public synchronized Boolean isProcessing() {
            return isProcessing;
        }

        public synchronized Boolean isRunning() {
            return isRunning;
        }

        @Override
        public void run() {

            //TODO Change to CloudTextRecognizer
            textRecognizer = FirebaseVision.getInstance().getOnDeviceTextRecognizer();

            while(isRunning) {

                if (isProcessing) {

                    if(firebaseVisionImage != null && textRecognizer != null){

                        textRecognizer.processImage(firebaseVisionImage).addOnSuccessListener(new OnSuccessListener<FirebaseVisionText>() {
                            @Override
                            public void onSuccess(final FirebaseVisionText firebaseVisionText) {

                                if (!firebaseVisionText.getTextBlocks().isEmpty()) {


                                    Log.d("FIREBASE_TEXT_REC", firebaseVisionText.getText());

                                    //TODO Send the text object to UI thread? Or work with it here?
                                    Message message_obj = uiHandler.obtainMessage(RECOGNIZED_TEXT,firebaseVisionText);
                                    message_obj.sendToTarget();

                                    //Run line below to stop camera feed
                                    //uiHandler.sendEmptyMessage(2);
                                }

                            }
                        });
                    }


                    firebaseVisionImage = null;
                    isProcessing = false;
                }

                if(!continousRun) isRunning = false;
            }

            //Close the TextRecognizer
            try {
                if (textRecognizer != null) {

                    textRecognizer.close();
                    textRecognizer = null;
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }


    }

}
