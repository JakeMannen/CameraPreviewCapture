package com.mdhdev.camerapreviewcapture;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
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

import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;
import com.google.firebase.ml.vision.text.FirebaseVisionText;
import com.google.firebase.ml.vision.text.FirebaseVisionTextRecognizer;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
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

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    public CameraHandler(Context ctx, TextureView textureView) {

        this.ctx = ctx;
        this.cameraView = textureView;

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

            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

    public void openCamera() {

        cameraManager = (CameraManager) ctx.getSystemService(CAMERA_SERVICE);

        try {

            String selectedcameraId = null;

            for (String cameraId : cameraManager.getCameraIdList()) {

                //Get properties from the selected camera
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);

                //We want to use back camera
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {

                    //Get the resolutions from the selected camera that can be used in a TextureView
                    StreamConfigurationMap streamConfigurationMap = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    streamsize = streamConfigurationMap.getOutputSizes(SurfaceTexture.class)[0];
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
            startCamera();
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

    void  startCamera()
    {
        if(cameraDevice==null||!cameraView.isAvailable()|| streamsize==null)
        {
            return;
        }

        SurfaceTexture texture=cameraView.getSurfaceTexture();
        imageReader = ImageReader.newInstance(480,360,ImageFormat.YUV_420_888,1);



        HandlerThread thread=new HandlerThread("image accuired");
        thread.start();
        Handler handler=new Handler(thread.getLooper());

        imageReader.setOnImageAvailableListener(ImageAvailable,handler);

        if(texture==null)
        {
            return;
        }
        texture.setDefaultBufferSize(streamsize.getWidth(), streamsize.getHeight());
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

    CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);

           // getStreamedImage();


        }
    };




    public void closeCamera(){

        if(cameraDevice!=null)
        {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private void getStreamedImage(Image image){

        if(null == image) return;


        detectTextInImage(image);
        image.close();
  /*
        try {

            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.capacity()];
            buffer.get(bytes);
            detectTextInImage(bytes);

        } catch (Exception e) {
            e.printStackTrace();

        } finally {
            if (image != null) {
                image.close();
            }
        }
*/

    }

    private void detectTextInImage(Image image){

        FirebaseVisionImage firebaseVisionImage = FirebaseVisionImage.fromMediaImage(image,rotation);


        final FirebaseVisionTextRecognizer textRecognizer = FirebaseVision.getInstance().getOnDeviceTextRecognizer();

        textRecognizer.processImage(firebaseVisionImage).addOnSuccessListener(new OnSuccessListener<FirebaseVisionText>() {
            @Override
            public void onSuccess(FirebaseVisionText firebaseVisionText) {

                Log.d("FIREBASE_TEXT_REC",firebaseVisionText.getText());

            }
        });

        try {
            textRecognizer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
