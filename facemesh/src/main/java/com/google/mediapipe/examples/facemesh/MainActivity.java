// Copyright 2021 The MediaPipe Authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.mediapipe.examples.facemesh;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.exifinterface.media.ExifInterface;

import com.google.mediapipe.formats.proto.LandmarkProto;
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark;
import com.google.mediapipe.framework.AndroidPacketGetter;
import com.google.mediapipe.solutioncore.CameraInput;
import com.google.mediapipe.solutioncore.SolutionGlSurfaceView;
import com.google.mediapipe.solutions.facemesh.FaceMeshOptions;
import com.google.mediapipe.solutions.facemesh.FaceMeshResult;

import java.util.Arrays;
import java.util.List;

/** Main activity of MediaPipe Face Mesh app. */
public class MainActivity extends AppCompatActivity {
  private static final String TAG = "MainActivity";

  private CustomFaceMesh facemesh;
  // Run the pipeline and the model inference on GPU or CPU.
  private static final boolean RUN_ON_GPU = true;

  // Live camera demo UI and camera components.
  private CameraInput cameraInput;

  private SolutionGlSurfaceView<FaceMeshResult> glSurfaceView;

  private FrameLayout frameLayout;
  private ImageView resultImageView;
  private TextView resultTextView;

  private TextView center;

  private Button startCameraButton, stopCameraButton;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    setupLiveDemoUiComponents();
    resultImageView = findViewById(R.id.resultImageView);
    resultTextView = findViewById(R.id.resultTextView);
    frameLayout = findViewById(R.id.preview_display_layout);
    center = findViewById(R.id.center);
    setCameraIsStarted(false);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (isCameraStarted()) {
      // Restarts the camera and the opengl surface rendering.
      cameraInput = new CameraInput(this);
      cameraInput.setNewFrameListener(textureFrame -> facemesh.send(textureFrame));
      glSurfaceView.post(this::startCamera);
      glSurfaceView.setVisibility(View.VISIBLE);
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (isCameraStarted()) {
      glSurfaceView.setVisibility(View.GONE);
      cameraInput.close();
    }
  }

  /** Sets up the UI components for the live demo with camera input. */
  private void setupLiveDemoUiComponents() {
    startCameraButton = findViewById(R.id.button_start_camera);
    stopCameraButton = findViewById(R.id.button_stop_camera);

    startCameraButton.setOnClickListener(
        v -> {
          setupStreamingModePipeline();

          setCameraIsStarted(true);
        });
    stopCameraButton.setOnClickListener(
        v -> {
          stopCurrentPipeline();

          startCameraButton.setVisibility(View.VISIBLE);
          stopCameraButton.setVisibility(View.GONE);
        });
  }

  /** Sets up core workflow for streaming mode. */
  private void setupStreamingModePipeline() {
    setCameraIsStarted(true);
    // Initializes a new MediaPipe Face Mesh solution instance in the streaming mode.
    facemesh =
        new CustomFaceMesh(
            this,
            FaceMeshOptions.builder()
                .setStaticImageMode(false)
                .setRefineLandmarks(true)
                .setRunOnGpu(RUN_ON_GPU)
                .build());
    facemesh.setErrorListener((message, e) -> Log.e(TAG, "MediaPipe Face Mesh error:" + message));

    cameraInput = new CameraInput(this);
    cameraInput.setNewFrameListener(textureFrame -> {
      facemesh.cacheImage(textureFrame);
      facemesh.send(textureFrame);
    });

    // Initializes a new Gl surface view with a user-defined FaceMeshResultGlRenderer.
    glSurfaceView =
        new SolutionGlSurfaceView<>(this, facemesh.getGlContext(), facemesh.getGlMajorVersion());
    glSurfaceView.setSolutionResultRenderer(new FaceMeshResultGlRenderer());
    glSurfaceView.setRenderInputImage(true);
    facemesh.setResultListener(
        faceMeshResult -> {
          processFaceMesh(faceMeshResult);
          glSurfaceView.setRenderData(faceMeshResult);
          glSurfaceView.requestRender();
        });

    // The runnable to start camera after the gl surface view is attached.
    // For video input source, videoInput.start() will be called when the video uri is available.
    glSurfaceView.post(this::startCamera);

    // Updates the preview layout.
    FrameLayout frameLayout = findViewById(R.id.preview_display_layout);
    resultTextView.setVisibility(View.VISIBLE);
    frameLayout.removeAllViewsInLayout();
    frameLayout.addView(glSurfaceView);
    glSurfaceView.setVisibility(View.VISIBLE);
    frameLayout.requestLayout();
  }

  private void startCamera() {
    cameraInput.start(
        this,
        facemesh.getGlContext(),
        CameraInput.CameraFacing.FRONT,
        glSurfaceView.getWidth(),
        glSurfaceView.getHeight());
  }

  private void stopCurrentPipeline() {
    setCameraIsStarted(false);
    if (cameraInput != null) {
      cameraInput.setNewFrameListener(null);
      cameraInput.close();
    }
    if (glSurfaceView != null) {
      glSurfaceView.setVisibility(View.GONE);
    }
    if (facemesh != null) {
      facemesh.close();
    }
  }

  @SuppressLint("DefaultLocale")
  public void processFaceMesh(FaceMeshResult result) {
    if (result == null) return;
    List<LandmarkProto.NormalizedLandmarkList> faces = result.multiFaceLandmarks();
    if (faces.isEmpty()) return;

    List<NormalizedLandmark> landmarks = faces.get(0).getLandmarkList();

    int topIndex = 10, bottomIndex = 152, leftChinIndex = 425, rightChinIndex = 205;

    NormalizedLandmark top = landmarks.get(topIndex),
            bottom = landmarks.get(bottomIndex),
            leftChin = landmarks.get(leftChinIndex),
            rightChin = landmarks.get(rightChinIndex);

    double[] topPoints = new double[]{top.getX(), top.getY(), top.getZ()},
            bottomPoints = new double[]{bottom.getX(), bottom.getY(), bottom.getZ()},
            leftChinPoints = new double[]{leftChin.getX(), leftChin.getY(), leftChin.getZ()},
            rightChinPoints = new double[]{rightChin.getX(), rightChin.getY(), rightChin.getZ()};

    double[] vLR = new double[]{leftChinPoints[0] - rightChinPoints[0], leftChinPoints[1] - rightChinPoints[1], leftChinPoints[2] - rightChinPoints[2]},
            vTB = new double[]{topPoints[0] - bottomPoints[0], topPoints[1] - bottomPoints[1], topPoints[2] - bottomPoints[2]};

    // vBF = vLR cross vTB (x = y * z)
    double[] vBF = new double[]{vLR[1] * vTB[2] - vLR[2] * vTB[1], vLR[2] * vTB[0] - vLR[0] * vTB[2], vLR[0] * vTB[1] - vLR[1] * vTB[0]};

    double[] normX = normalize(vBF), normY = normalize(vLR), normZ = normalize(vTB), angleZ = angle(normZ);

    boolean angleIsForward = angleIsForward(angleZ);

    String logText = String.format("x = (%.0f, %.0f, %.0f)\n" +
                    "y = (%.0f, %.0f, %.0f)\n" +
                    "z = (%.0f, %.0f, %.0f)\n" +
                    "angleZ = (%.0f, %.0f, %.0f)\n\n%s",
            normX[0], normX[1], normX[2],
            normY[0], normY[1], normY[2],
            normZ[0], normZ[1], normZ[2],
            angleZ[0], angleZ[1], angleZ[2],
            angleIsForward ? "FORWARD!!" : "");
    Log.i(TAG, "processFaceMesh: " + logText.replace("\n", "  "));

    double[] min = new double[]{1, 1},
            max = new double[]{-1, -1},
            avg = new double[]{0, 0, 0};
    if (angleIsForward) {
      for (NormalizedLandmark landmark : landmarks) {
        double x = landmark.getX(), y = landmark.getY(), z = landmark.getZ();

        min[0] = Math.min(min[0], x); min[1] = Math.min(min[1], y);
        max[0] = Math.max(max[0], x); max[1] = Math.max(max[1], y);
        avg[0] += x; avg[1] += y; avg[2] += z;
      }
      avg[0] /= landmarks.size(); avg[1] /= landmarks.size(); avg[2] /= landmarks.size();
    }
    runOnUiThread(() -> {
      resultTextView.setText(logText);

      if (angleIsForward) {
        Bitmap bm = AndroidPacketGetter.getBitmapFromRgba(facemesh.cacheImagePacket);

        Log.i(TAG, "processFaceMesh: cropped min=" + Arrays.toString(min) + " max=" + Arrays.toString(max) + " avg=" + Arrays.toString(avg));
        double faceX = min[0] * bm.getWidth(),
                faceY = (1 - max[1]) * bm.getHeight(),
                faceW = (max[0] - min[0]) * bm.getWidth(),
                faceH = (max[1] - min[1]) * bm.getHeight();
        Bitmap croppedBm = Bitmap.createBitmap(
                bm,
                (int) (faceX - faceW / 4),
                (int) (faceY - faceH / 8),
                (int) (faceW + faceW * .5),
                (int) (faceH + faceH * .75)
        );
        Log.i(TAG, "processFaceMesh: bitmap = " + croppedBm);

        stopCurrentPipeline();
        resultImageView.setImageBitmap(croppedBm);
        resultImageView.setVisibility(View.VISIBLE);
        center.setText(String.format(
                "Center: (x=%d, y=%d, z=%d)",
                ((int) (avg[0] * bm.getWidth())),
                ((int) (avg[1] * bm.getHeight())),
                ((int) (avg[2] * bm.getWidth()))
        ));
        center.setVisibility(View.VISIBLE);
      }
    });
  }

  public static double[] normalize(double[] vect) {
    double size = Math.sqrt(Math.pow(vect[0], 2) + Math.pow(vect[1], 2) + Math.pow(vect[2], 2));
    return new double[]{vect[0] * 100 / size, vect[1] * 100 / size, vect[2] * 100 / size};
  }

  public static double[] angle(double[] vect) {
    double size = Math.sqrt(Math.pow(vect[0], 2) + Math.pow(vect[1], 2) + Math.pow(vect[2], 2));
    return new double[]{
            Math.acos(vect[0] / size) / Math.PI * 180,
            Math.acos(vect[1] / size) / Math.PI * 180,
            Math.acos(vect[2] / size) / Math.PI * 180,
    };
  }

  public static boolean angleIsForward(double[] vect) {
    double error = 3;
    return isAboutEqual(vect[0], 90, error) &&
            isAboutEqual(vect[1], 175, error) &&
            isAboutEqual(vect[2], 90, error);
  }

  public static boolean isAboutEqual(double number, double approx, double error) {
    return approx - error <= number && number <= approx + error;
  }

  public boolean isCameraStarted() {
    return stopCameraButton.getVisibility() == View.VISIBLE;
  }

  public void setCameraIsStarted(boolean cameraIsStarted) {
    if (cameraIsStarted) {
      startCameraButton.setVisibility(View.GONE);
      stopCameraButton.setVisibility(View.VISIBLE);
    } else {
      startCameraButton.setVisibility(View.VISIBLE);
      stopCameraButton.setVisibility(View.GONE);
    }

    frameLayout.setVisibility(stopCameraButton.getVisibility());
    resultTextView.setVisibility(stopCameraButton.getVisibility());
    resultImageView.setVisibility(View.GONE);
    center.setVisibility(View.GONE);
  }
}
