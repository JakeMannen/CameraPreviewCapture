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
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CameraProfile;
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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import static android.content.ContentValues.TAG;
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
    private FirebaseVisionTextRecognizer textRecognizer;
    private int texturewidth;
    private int textureheight;
    private String selectedcameraId;
    private GraphicOverlay graphicOverlay;

    final static int TEXT_CAPTURE_WIDTH = 480;
    final static int TEXT_CAPTURE_HEIGHT = 360;

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
            transformImage(width,height);

        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

            closeCamera();
            texturewidth = width;
            textureheight = height;

            openCamera();
            transformImage(width,height);
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

    public void openCamera() {

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
                    streamsize = streamConfigurationMap.getOutputSizes(SurfaceTexture.class)[0];

                    //streamsize = chooseOptimalSize(available_sizes, texturewidth,textureheight,available_sizes[0].getWidth(),available_sizes[0].getHeight(),);
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


            rotation = getRotationCompensation(selectedcameraId,(Activity)ctx,ctx);

            cameraManager.openCamera(selectedcameraId, camerastateCallback, null);

        }catch (Exception e)
        {
        }
    }

    private CameraDevice.StateCallback camerastateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            try {
                startCamera();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraDevice.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    private void transformImage(int width, int height) {
        if(streamsize == null || cameraView == null) {
            return;
        }
        Matrix matrix = new Matrix();

        WindowManager windowManager = (WindowManager)ctx.getSystemService(Context.WINDOW_SERVICE);
        int rotation = windowManager.getDefaultDisplay().getRotation();


        RectF textureRectF = new RectF(0, 0, width, height);
        RectF previewRectF = new RectF(0, 0, streamsize.getHeight(), streamsize.getWidth());
        float centerX = textureRectF.centerX();
        float centerY = textureRectF.centerY();

        if(rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            previewRectF.offset(centerX - previewRectF.centerX(),
                    centerY - previewRectF.centerY());
            matrix.setRectToRect(textureRectF, previewRectF, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float)width / streamsize.getWidth(),
                    (float)height / streamsize.getHeight());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        cameraView.setTransform(matrix);
    }


    private void  startCamera() throws CameraAccessException {

        if(cameraDevice==null||!cameraView.isAvailable() || selectedcameraId == null)
        {
            return;
        }

        //cameraView.setRotation(cameraManager
        //        .getCameraCharacteristics(selectedcameraId)
        //        .get(CameraCharacteristics.SENSOR_ORIENTATION));

        SurfaceTexture texture=cameraView.getSurfaceTexture();
        imageReader = ImageReader.newInstance(TEXT_CAPTURE_WIDTH,TEXT_CAPTURE_HEIGHT,ImageFormat.YUV_420_888,1);



        HandlerThread thread=new HandlerThread("image accuired");
        thread.start();
        Handler handler=new Handler(thread.getLooper());

        imageReader.setOnImageAvailableListener(ImageAvailable,handler);

        if(texture==null)
        {
            return;
        }
        texture.setDefaultBufferSize(texturewidth, textureheight);
        Surface surface=new Surface(texture);
        try
        {
            captureBuilder=cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

        }catch (Exception e)
        {
        }

        List<Surface> outputSurfaces = new LinkedList<>();
        outputSurfaces.add(imageReader.getSurface());
        outputSurfaces.add(surface);

        captureBuilder.addTarget(imageReader.getSurface());
        captureBuilder.addTarget(surface);


        try
        {
            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() { //Arrays.asList(surface)
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession=session;
                    getChangedPreview();
                }
                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                }
            },null);

        }catch (Exception e)
        {
        }
    }

    private void getChangedPreview()
    {
        if(cameraDevice==null)
        {
            return;
        }

        captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

        HandlerThread thread=new HandlerThread("changed Preview");
        thread.start();
        Handler handler=new Handler(thread.getLooper());


        try
        {
            captureSession.setRepeatingRequest(captureBuilder.build(), null, handler);
        }catch (Exception e){}
    }

    ImageReader.OnImageAvailableListener ImageAvailable = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {


            getStreamedImage(reader.acquireNextImage());

        }
    };




    public void closeCamera(){

        if(cameraDevice!=null)
        {
            cameraDevice.close();
            cameraDevice = null;
        }
        if(imageReader!=null)
        {
            imageReader.close();
            imageReader = null;
        }
        if(textRecognizer != null)
        {
            try {
                textRecognizer.close();
            } catch (IOException e) {

                e.printStackTrace();
            }
            textRecognizer = null;
        }
    }

    private void getStreamedImage(Image image){

        if(null == image) return;


        detectTextInImage(image);
        image.close();

    }


    private void detectTextInImage(Image image){

        if(textRecognizer != null) return;

        final FirebaseVisionImage firebaseVisionImage = FirebaseVisionImage.fromMediaImage(image,rotation);


        textRecognizer = FirebaseVision.getInstance().getOnDeviceTextRecognizer();



        textRecognizer.processImage(firebaseVisionImage).addOnSuccessListener(new OnSuccessListener<FirebaseVisionText>() {
            @Override
            public void onSuccess(FirebaseVisionText firebaseVisionText) {

                if(!firebaseVisionText.getTextBlocks().isEmpty()){

                    //TODO HERE IS THE PLACE TO WORK WITH RECOGNIZED TEXT
                    Log.d("FIREBASE_TEXT_REC",firebaseVisionText.getText());


                    //Graphic mumbo jumbo
                    if(graphicOverlay != null){
                        //TODO Fix image source
                        Bitmap originalCameraImage = cameraView.getBitmap();
                        graphicOverlay.clear();
                        if (originalCameraImage != null) {
                            CameraImageGraphic imageGraphic = new CameraImageGraphic(graphicOverlay,
                                    originalCameraImage);
                            graphicOverlay.add(imageGraphic);
                        }
                        List<FirebaseVisionText.TextBlock> blocks = firebaseVisionText.getTextBlocks();
                        for (int i = 0; i < blocks.size(); i++) {
                            List<FirebaseVisionText.Line> lines = blocks.get(i).getLines();
                            for (int j = 0; j < lines.size(); j++) {
                                List<FirebaseVisionText.Element> elements = lines.get(j).getElements();
                                for (int k = 0; k < elements.size(); k++) {
                                    GraphicOverlay.Graphic textGraphic = new TextGraphic(graphicOverlay,
                                            elements.get(k));
                                    graphicOverlay.add(textGraphic);
                                }
                            }
                        }
                        graphicOverlay.postInvalidate();
                    }


                }

                try {
                    textRecognizer.close();
                    textRecognizer = null;
                } catch (IOException e) {
                    e.printStackTrace();
                }

            }
        });



    }

}
