package com.mdhdev.camerapreviewcapture;

import android.Manifest;
import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.ml.vision.text.FirebaseVisionText;
import com.mdhdev.camerapreviewcapture.mlkit.GraphicOverlay;

public class CameraActivity extends AppCompatActivity {

    private CameraHandler cameraHandler;


    CameraHandler.OnTextRecognizedListener onTextRecognizedListener = new CameraHandler.OnTextRecognizedListener() {
        @Override
        public void onTextRecognized(FirebaseVisionText text) {

            //TODO Work with Firebasetext object here?
            Toast t = Toast.makeText(getApplicationContext(),text.getText(), Toast.LENGTH_LONG);
            t.setGravity(Gravity.TOP,0,0);
            t.show();
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        ActivityCompat.requestPermissions(this,new String[]
                {Manifest.permission.CAMERA,Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }


    @Override
    protected void onResume() {
        super.onResume();


            cameraHandler = new CameraHandler(this, (TextureView) findViewById(R.id.previeWindow),(ImageButton)findViewById(R.id.snapshotBtn));

            cameraHandler.setOnTextRecognizedListener(onTextRecognizedListener);

    }

    @Override
    protected void onPause() {
        super.onPause();

        if(cameraHandler != null) {
            cameraHandler.closeCamera();
            cameraHandler = null;
        }

    }

    @Override
    protected void onStop() {
        super.onStop();

        if(cameraHandler != null) {
            cameraHandler.closeCamera();
            cameraHandler = null;
        }
    }

    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 1: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                } else {
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                }
                return;
            }
            // other 'case' lines to check for other
            // permissions this app might request
        }
    }
}


