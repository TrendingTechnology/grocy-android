package xyz.zedler.patrick.grocy.scan;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.WindowManager;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.ResultPoint;
import com.google.zxing.client.android.InactivityTimer;
import com.google.zxing.client.android.Intents;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.CameraPreview;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import java.util.List;

import xyz.zedler.patrick.grocy.R;

public class ScanBatchCaptureManager {

    private static final String TAG = ScanBatchCaptureManager.class.getSimpleName();

    private static int cameraPermissionReqCode = 250;

    private Activity activity;
    private DecoratedBarcodeView barcodeView;

    private boolean showDialogIfMissingCameraPermission = true;
    private String missingCameraPermissionDialogMessage = "";

    private boolean destroyed = false;

    private InactivityTimer inactivityTimer;

    private Handler handler;

    private boolean finishWhenClosed = false;

    private BarcodeListener barcodeListener;

    public BarcodeCallback callback = new BarcodeCallback() {
        @Override
        public void barcodeResult(final BarcodeResult result) {
            barcodeView.pause();
            inactivityTimer.cancel();
            barcodeListener.onBarcodeResult(result);
        }

        @Override
        public void possibleResultPoints(List<ResultPoint> resultPoints) {

        }
    };

    public interface BarcodeListener {
        void onBarcodeResult(BarcodeResult result);
    }

    public ScanBatchCaptureManager(
            Activity activity,
            DecoratedBarcodeView barcodeView,
            BarcodeListener barcodeListener
    ) {
        this.activity = activity;
        this.barcodeView = barcodeView;
        this.barcodeListener = barcodeListener;
        barcodeView.getBarcodeView().addStateListener(
                new CameraPreview.StateListener() {
                    @Override
                    public void previewSized() { }

                    @Override
                    public void previewStarted() { }

                    @Override
                    public void previewStopped() { }

                    @Override
                    public void cameraError(Exception error) {
                        displayFrameworkBugMessageAndExit(
                                activity.getString(R.string.zxing_msg_camera_framework_bug)
                        );
                    }

                    @Override
                    public void cameraClosed() {
                        if (finishWhenClosed) {
                            Log.d(TAG, "Camera closed; finishing activity");
                            finish();
                        }
                    }
                }
        );

        handler = new Handler();

        inactivityTimer = new InactivityTimer(activity, () -> {
            Log.d(TAG, "Finishing due to inactivity");
            finish();
        });
    }

    /**
     * Start decoding.
     */
    public void decode() {
        barcodeView.decodeSingle(callback);
    }

    /**
     * Call from Activity#onResume().
     */
    public void onResume() {
        if (Build.VERSION.SDK_INT >= 23) {
            openCameraWithPermission();
        } else {
            barcodeView.resume();
        }
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        inactivityTimer.start();
        decode();
    }

    private boolean askedPermission = false;

    @TargetApi(23)
    private void openCameraWithPermission() {
        if (ContextCompat.checkSelfPermission(this.activity, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            barcodeView.resume();
        } else if (!askedPermission) {
            ActivityCompat.requestPermissions(this.activity,
                    new String[]{Manifest.permission.CAMERA},
                    cameraPermissionReqCode);
            askedPermission = true;
        } // else wait for permission result
    }

    /**
     * Call from Activity#onRequestPermissionsResult
     * @param requestCode The request code passed in {@link ActivityCompat#requestPermissions(Activity, String[], int)}.
     * @param permissions The requested permissions.
     * @param grantResults The grant results for the corresponding permissions
     *     which is either {@link PackageManager#PERMISSION_GRANTED}
     *     or {@link PackageManager#PERMISSION_DENIED}. Never null.
     */
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if (requestCode == cameraPermissionReqCode) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // permission was granted
                barcodeView.resume();
            } else {
                setMissingCameraPermissionResult();

                if (showDialogIfMissingCameraPermission) {
                    displayFrameworkBugMessageAndExit(missingCameraPermissionDialogMessage);
                } else {
                    closeAndFinish();
                }
            }
        }
    }

    /**
     * Call from Activity#onPause().
     */
    public void onPause() {
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        inactivityTimer.cancel();
        barcodeView.pauseAndWait();
    }

    /**
     * Call from Activity#onDestroy().
     */
    public void onDestroy() {
        destroyed = true;
        inactivityTimer.cancel();
        handler.removeCallbacksAndMessages(null);
    }

    private void finish() {
        activity.finish();
    }

    protected void closeAndFinish() {
        if (barcodeView.getBarcodeView().isCameraClosed()) {
            //finish();
        } else {
            finishWhenClosed = true;
        }

        barcodeView.pause();
        inactivityTimer.cancel();
    }

    private void setMissingCameraPermissionResult() {
        Intent intent = new Intent(Intents.Scan.ACTION);
        intent.putExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, true);
        activity.setResult(Activity.RESULT_CANCELED, intent);
    }

    protected void displayFrameworkBugMessageAndExit(String message) {
        if (activity.isFinishing() || this.destroyed || finishWhenClosed) {
            return;
        }

        if (message.isEmpty()) {
            message = activity.getString(R.string.zxing_msg_camera_framework_bug);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(activity.getString(R.string.zxing_app_name));
        builder.setMessage(message);
        builder.setPositiveButton(R.string.zxing_button_ok, (dialog, which) -> finish());
        builder.setOnCancelListener(dialog -> finish());
        builder.show();
    }

    public static int getCameraPermissionReqCode() {
        return cameraPermissionReqCode;
    }

    public static void setCameraPermissionReqCode(int cameraPermissionReqCode) {
        ScanBatchCaptureManager.cameraPermissionReqCode = cameraPermissionReqCode;
    }

    /**
     * If set to true, shows the default error dialog if camera permission is missing.
     * <p>
     * If set to false, instead the capture manager just finishes.
     * <p>
     * In both cases, the activity result is set to {@link Intents.Scan#MISSING_CAMERA_PERMISSION}
     * and cancelled
     */
    public void setShowMissingCameraPermissionDialog(boolean visible) {
        setShowMissingCameraPermissionDialog(visible, "");
    }

    /**
     * If set to true, shows the specified error dialog message if camera permission is missing.
     * <p>
     * If set to false, instead the capture manager just finishes.
     * <p>
     * In both cases, the activity result is set to {@link Intents.Scan#MISSING_CAMERA_PERMISSION}
     * and cancelled
     */
    public void setShowMissingCameraPermissionDialog(boolean visible, String message) {
        showDialogIfMissingCameraPermission = visible;
        missingCameraPermissionDialogMessage = message != null ? message : "";
    }
}