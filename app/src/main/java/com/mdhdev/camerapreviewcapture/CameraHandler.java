package com.mdhdev.camerapreviewcapture;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
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
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;


import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;
import com.google.firebase.ml.vision.text.FirebaseVisionText;
import com.google.firebase.ml.vision.text.FirebaseVisionTextRecognizer;
import com.mdhdev.camerapreviewcapture.mlkit.CameraImageGraphic;
import com.mdhdev.camerapreviewcapture.mlkit.GraphicOverlay;
import com.mdhdev.camerapreviewcapture.mlkit.TextGraphic;


import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import static android.content.Context.CAMERA_SERVICE;

public class CameraHandler {

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
    private GraphicOverlay graphicOverlay;

    private Handler cameraHandler;
    private Handler imagereaderHandler;
    private Handler captureHandler;
    private HandlerThread cameraHandlerThread;
    private HandlerThread imageReaderThread;
    private HandlerThread captureHandlerThread;
    private Thread textThread;
    private TextRecognitionEngine textEngine;

    //Size is enough for text capture
    private final static int TEXT_CAPTURE_WIDTH = 480;
    private final static int TEXT_CAPTURE_HEIGHT = 360;
    private final static int FRAME_DETECT_THRESHOLD = 5;


    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    public CameraHandler(Context ctx, TextureView textureView, GraphicOverlay grapOv) {

        this.ctx = ctx;
        this.cameraView = textureView;
        this.graphicOverlay = grapOv;
        startTextRecognitionThread();
        cameraView.setSurfaceTextureListener(textureListener);

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
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }


            rotation = getRotationCompensation(selectedcameraId, (Activity) ctx, ctx);

            cameraHandlerThread = new HandlerThread("Camera thread");
            cameraHandlerThread.start();
            cameraHandler = new Handler(cameraHandlerThread.getLooper());

            cameraManager.openCamera(selectedcameraId, camerastateCallback, cameraHandler);

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
        imageReader = ImageReader.newInstance(TEXT_CAPTURE_WIDTH, TEXT_CAPTURE_HEIGHT, ImageFormat.YUV_420_888, 1);

        //Thread for Imagereader
        imageReaderThread = new HandlerThread("Image acquire");
        imageReaderThread.start();
        imagereaderHandler = new Handler(imageReaderThread.getLooper());

        imageReader.setOnImageAvailableListener(ImageAvailable, imagereaderHandler);

        try {
            captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

        } catch (Exception e) {
            e.printStackTrace();
        }

        //Setup the capture session
        List<Surface> outputSurfaces = new LinkedList<>();

        outputSurfaces.add(surface);
        outputSurfaces.add(imageReader.getSurface());

        captureBuilder.addTarget(surface);
        captureBuilder.addTarget(imageReader.getSurface());

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


        captureHandlerThread = new HandlerThread("Changed Preview");
        captureHandlerThread.start();
        captureHandler = new Handler(captureHandlerThread.getLooper());


        try {
            captureSession.setRepeatingRequest(captureBuilder.build(), null, captureHandler);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    ImageReader.OnImageAvailableListener ImageAvailable = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(final ImageReader reader) {

            //Send the image frame to separate thread for text processing
            textEngine.addImageForProcessing(reader.acquireNextImage(), rotation);

        }
    };

    private void startTextRecognitionThread(){

        textEngine = new TextRecognitionEngine(FRAME_DETECT_THRESHOLD);
        textThread = new Thread(textEngine);
        textThread.start();
    }

    private void closeThreads() {


        if (cameraHandlerThread != null) {

            cameraHandlerThread.quitSafely();

            try {
                cameraHandlerThread.join();
                cameraHandlerThread = null;
                cameraHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }

        if (captureHandlerThread != null) {

            captureHandlerThread.quitSafely();

            try {
                captureHandlerThread.join();
                captureHandlerThread = null;
                captureHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }
        if (imageReaderThread != null) {

            imageReaderThread.quitSafely();

            try {
                imageReaderThread.join();
                imageReaderThread = null;
                imagereaderHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if(textThread != null){

            textEngine.stop();
            try {
                textThread.join();
                textThread = null;
                textEngine = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }


    }

    public void closeCamera() {

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



    private static class TextRecognitionEngine implements Runnable {

        private Boolean isRunning = true;
        private Boolean isProcessing = false;

        private FirebaseVisionTextRecognizer textRecognizer;

        private FirebaseVisionImage firebaseVisionImage;
        private int rotation;
        private Image image;
        private int frameThreshold;
        private int frameCount = 0;


        private TextRecognitionEngine(int frameThreshold) {

            this.frameThreshold = frameThreshold;

            textRecognizer = FirebaseVision.getInstance().getOnDeviceTextRecognizer();
        }

        public synchronized void addImageForProcessing(Image imagein, int rotationin) {

            if (!readyForProcessing() || isProcessing || !isRunning || textRecognizer == null) {

                frameCount++;
                imagein.close();
                return;
            }

            this.image = imagein;
            this.rotation = rotationin;

            if (firebaseVisionImage != null) return;

            firebaseVisionImage = FirebaseVisionImage.fromMediaImage(image, rotation);

            image.close();

            isProcessing = true;

            frameCount = 0;
        }

        //If threshold is met a new frame can be processed
        private boolean readyForProcessing() {

            if(frameCount >= frameThreshold){

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


            while(isRunning) {

                if (isProcessing) {

                    textRecognizer.processImage(firebaseVisionImage).addOnSuccessListener(new OnSuccessListener<FirebaseVisionText>() {
                        @Override
                        public void onSuccess(final FirebaseVisionText firebaseVisionText) {

                            if (!firebaseVisionText.getTextBlocks().isEmpty()) {

                                //TODO HERE IS THE PLACE TO WORK WITH RECOGNIZED TEXT
                                Log.d("FIREBASE_TEXT_REC", firebaseVisionText.getText());


                            }

                        }
                    });

                    firebaseVisionImage = null;
                    isProcessing = false;
                }

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
