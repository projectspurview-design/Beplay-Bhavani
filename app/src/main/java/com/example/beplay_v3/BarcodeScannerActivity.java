//Barcode
package com.example.beplay_v3;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.util.Size;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BarcodeScannerActivity extends AppCompatActivity {

    private static final String TAG = "BarcodeScannerActivity";
    private static final int REQ_PERMISSIONS = 500;

    private static final String[] REQUIRED_PERMISSIONS = new String[] {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO   // requested up-front for your voice features later
    };

    private PreviewView previewView;
    private TextView btnBack, btnTorch;

    private ExecutorService cameraExecutor;
    private BarcodeScanner scanner;

    private volatile boolean resultSent = false;
    private Camera camera;
    private CameraControl cameraControl;
    private CameraInfo cameraInfo;
    private boolean torchOn = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_barcode_scanner);

        previewView = findViewById(R.id.previewView);
        btnBack     = findViewById(R.id.btnBack);
        btnTorch    = findViewById(R.id.btnTorch);

        // Prevent black preview on some devices
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);

        btnBack.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        btnTorch.setOnClickListener(v -> toggleTorch());

        cameraExecutor = Executors.newSingleThreadExecutor();

        BarcodeScannerOptions options =
                new BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(
                                Barcode.FORMAT_QR_CODE,
                                Barcode.FORMAT_AZTEC,
                                Barcode.FORMAT_DATA_MATRIX,
                                Barcode.FORMAT_PDF417,
                                Barcode.FORMAT_CODE_128,
                                Barcode.FORMAT_CODE_39,
                                Barcode.FORMAT_CODE_93,
                                Barcode.FORMAT_CODABAR,
                                Barcode.FORMAT_EAN_13,
                                Barcode.FORMAT_EAN_8,
                                Barcode.FORMAT_UPC_A,
                                Barcode.FORMAT_UPC_E)
                        .build();
        scanner = BarcodeScanning.getClient(options);

        // 🔐 Ask for Camera + Mic at first app open
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            requestRuntimePermissions();
        }
    }

    // ===== Permission helpers =====
    private boolean allPermissionsGranted() {
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestRuntimePermissions() {
        // If user already denied once, show a rationale dialog
        boolean shouldShowRationale = false;
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
                shouldShowRationale = true;
                break;
            }
        }

        if (shouldShowRationale) {
            new AlertDialog.Builder(this)
                    .setTitle("Permissions required")
                    .setMessage("We need Camera to scan your QR code and Microphone for hands-free commands. Please allow these to continue.")
                    .setPositiveButton("Continue", (d, w) ->
                            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQ_PERMISSIONS))
                    .setNegativeButton("Cancel", (d, w) -> finish())
                    .setCancelable(false)
                    .show();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                // If user picked “Don’t ask again”, guide them to Settings
                boolean somePermanentlyDenied = false;
                for (int i = 0; i < permissions.length; i++) {
                    String perm = permissions[i];
                    boolean granted = (grantResults.length > i && grantResults[i] == PackageManager.PERMISSION_GRANTED);
                    if (!granted && !ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
                        somePermanentlyDenied = true;
                        break;
                    }
                }
                if (somePermanentlyDenied) {
                    showGoToSettingsDialog();
                } else {
                    Toast.makeText(this, "Camera and Microphone permissions are required", Toast.LENGTH_LONG).show();
                    finish();
                }
            }
        }
    }

    private void showGoToSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Enable permissions")
                .setMessage("Camera and Microphone are turned off for this app. Enable them in Settings to scan your QR code.")
                .setPositiveButton("Open Settings", (d, w) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.fromParts("package", getPackageName(), null));
                        startActivity(intent);
                    } catch (ActivityNotFoundException ignored) {}
                    finish();
                })
                .setNegativeButton("Cancel", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    // ===== CameraX / MLKit =====
    private void toggleTorch() {
        if (cameraInfo == null || cameraControl == null) return;
        if (!cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "Torch not available", Toast.LENGTH_SHORT).show();
            return;
        }
        torchOn = !torchOn;
        cameraControl.enableTorch(torchOn);
        btnTorch.setAlpha(torchOn ? 1f : 0.7f);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = cameraProviderFuture.get();

                // Prefer back; fallback to front on emulators without back camera
                CameraSelector back = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
                CameraSelector front = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT).build();

                if (isAvailable(provider, back)) {
                    bindUseCases(provider, back);
                } else if (isAvailable(provider, front)) {
                    bindUseCases(provider, front);
                    Toast.makeText(this, "Using front camera", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "No camera available", Toast.LENGTH_LONG).show();
                    finish();
                }
            } catch (Exception e) {
                Log.e(TAG, "Camera init failed", e);
                Toast.makeText(this, "Camera init failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private boolean isAvailable(ProcessCameraProvider provider, CameraSelector selector) {
        try {
            provider.bindToLifecycle(this, selector);
            provider.unbindAll();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void bindUseCases(ProcessCameraProvider provider, CameraSelector selector) {
        provider.unbindAll();

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(cameraExecutor, this::analyzeImage);

        try {
            camera = provider.bindToLifecycle(this, selector, preview, analysis);
            cameraControl = camera.getCameraControl();
            cameraInfo    = camera.getCameraInfo();
        } catch (Exception e) {
            Log.e(TAG, "bindToLifecycle failed", e);
            Toast.makeText(this, "Failed to bind camera: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    @SuppressLint("UnsafeOptInUsageError")
    private void analyzeImage(ImageProxy imageProxy) {
        if (resultSent) { imageProxy.close(); return; }
        try {
            if (imageProxy.getImage() == null) { imageProxy.close(); return; }

            InputImage image = InputImage.fromMediaImage(
                    imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

            scanner.process(image)
                    .addOnSuccessListener(barcodes -> {
                        if (barcodes == null || barcodes.isEmpty()) { imageProxy.close(); return; }
                        handleBarcodes(barcodes, imageProxy);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Scanner error", e);
                        imageProxy.close();
                    });
        } catch (Exception e) {
            Log.e(TAG, "analyzeImage error", e);
            imageProxy.close();
        }
    }

    private void handleBarcodes(List<Barcode> barcodes, ImageProxy imageProxy) {
        try {
            for (Barcode b : barcodes) {
                String raw = b.getRawValue();
                if (raw == null || raw.trim().isEmpty()) continue;

                if (!resultSent) {
                    resultSent = true;
                    Intent i = new Intent(this, QRScannerActivity.class);
                    i.putExtra("BARCODE_VALUE", raw);
                    startActivity(i);
                    runOnUiThread(this::finish);
                    break;
                }
            }
        } finally {
            imageProxy.close();
        }
    }

    @Override
    protected void onDestroy() {
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (scanner != null) scanner.close();
        super.onDestroy();
    }
}
