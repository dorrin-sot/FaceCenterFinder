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
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.provider.MediaStore;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.exifinterface.media.ExifInterface;
import com.google.mediapipe.formats.proto.LandmarkProto;
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark;
import com.google.mediapipe.solutioncore.CameraInput;
import com.google.mediapipe.solutioncore.SolutionGlSurfaceView;
import com.google.mediapipe.solutioncore.VideoInput;
import com.google.mediapipe.solutions.facemesh.FaceMesh;
import com.google.mediapipe.solutions.facemesh.FaceMeshOptions;
import com.google.mediapipe.solutions.facemesh.FaceMeshResult;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Main activity of MediaPipe Face Mesh app. */
public class MainActivity extends AppCompatActivity {
  private static final String TAG = "MainActivity";

  private FaceMesh facemesh;
  // Run the pipeline and the model inference on GPU or CPU.
  private static final boolean RUN_ON_GPU = true;

  private enum InputSource {
    UNKNOWN,
    IMAGE,
    VIDEO,
    CAMERA,
  }
  private InputSource inputSource = InputSource.UNKNOWN;
  // Image demo UI and image loader components.
  private ActivityResultLauncher<Intent> imageGetter;
  private FaceMeshResultImageView imageView;
  // Video demo UI and video loader components.
  private VideoInput videoInput;
  private ActivityResultLauncher<Intent> videoGetter;
  // Live camera demo UI and camera components.
  private CameraInput cameraInput;

  private SolutionGlSurfaceView<FaceMeshResult> glSurfaceView;

  private FrameLayout frameLayout;
  private ImageView resultImageView;
  private TextView resultTextView;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    // TODO: Add a toggle to switch between the original face mesh and attention mesh.
    setupStaticImageDemoUiComponents();
    setupVideoDemoUiComponents();
    setupLiveDemoUiComponents();
    resultImageView = findViewById(R.id.resultImageView);
    resultTextView = findViewById(R.id.resultTextView);
    resultTextView.setVisibility(View.VISIBLE);
    frameLayout = findViewById(R.id.preview_display_layout);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (inputSource == InputSource.CAMERA) {
      // Restarts the camera and the opengl surface rendering.
      cameraInput = new CameraInput(this);
      cameraInput.setNewFrameListener(textureFrame -> facemesh.send(textureFrame));
      glSurfaceView.post(this::startCamera);
      glSurfaceView.setVisibility(View.VISIBLE);
    } else if (inputSource == InputSource.VIDEO) {
      videoInput.resume();
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (inputSource == InputSource.CAMERA) {
      glSurfaceView.setVisibility(View.GONE);
      cameraInput.close();
    } else if (inputSource == InputSource.VIDEO) {
      videoInput.pause();
    }
  }

  private Bitmap downscaleBitmap(Bitmap originalBitmap) {
    double aspectRatio = (double) originalBitmap.getWidth() / originalBitmap.getHeight();
    int width = imageView.getWidth();
    int height = imageView.getHeight();
    if (((double) imageView.getWidth() / imageView.getHeight()) > aspectRatio) {
      width = (int) (height * aspectRatio);
    } else {
      height = (int) (width / aspectRatio);
    }
    return Bitmap.createScaledBitmap(originalBitmap, width, height, false);
  }

  private Bitmap rotateBitmap(Bitmap inputBitmap, InputStream imageData) throws IOException {
    int orientation =
        new ExifInterface(imageData)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
    if (orientation == ExifInterface.ORIENTATION_NORMAL) {
      return inputBitmap;
    }
    Matrix matrix = new Matrix();
    switch (orientation) {
      case ExifInterface.ORIENTATION_ROTATE_90:
        matrix.postRotate(90);
        break;
      case ExifInterface.ORIENTATION_ROTATE_180:
        matrix.postRotate(180);
        break;
      case ExifInterface.ORIENTATION_ROTATE_270:
        matrix.postRotate(270);
        break;
      default:
        matrix.postRotate(0);
    }
    return Bitmap.createBitmap(
        inputBitmap, 0, 0, inputBitmap.getWidth(), inputBitmap.getHeight(), matrix, true);
  }

  /** Sets up the UI components for the static image demo. */
  private void setupStaticImageDemoUiComponents() {
    // The Intent to access gallery and read images as bitmap.
    imageGetter =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
              Intent resultIntent = result.getData();
              if (resultIntent != null) {
                if (result.getResultCode() == RESULT_OK) {
                  Bitmap bitmap = null;
                  try {
                    bitmap =
                        downscaleBitmap(
                            MediaStore.Images.Media.getBitmap(
                                this.getContentResolver(), resultIntent.getData()));
                  } catch (IOException e) {
                    Log.e(TAG, "Bitmap reading error:" + e);
                  }
                  try {
                    InputStream imageData =
                        this.getContentResolver().openInputStream(resultIntent.getData());
                    bitmap = rotateBitmap(bitmap, imageData);
                  } catch (IOException e) {
                    Log.e(TAG, "Bitmap rotation error:" + e);
                  }
                  if (bitmap != null) {
                    facemesh.send(bitmap);
                  }
                }
              }
            });
    Button loadImageButton = findViewById(R.id.button_load_picture);
    loadImageButton.setOnClickListener(
        v -> {
          if (inputSource != InputSource.IMAGE) {
            stopCurrentPipeline();
            setupStaticImageModePipeline();
          }
          // Reads images from gallery.
          Intent pickImageIntent = new Intent(Intent.ACTION_PICK);
          pickImageIntent.setDataAndType(MediaStore.Images.Media.INTERNAL_CONTENT_URI, "image/*");
          imageGetter.launch(pickImageIntent);
        });
    imageView = new FaceMeshResultImageView(this);
  }

  /** Sets up core workflow for static image mode. */
  private void setupStaticImageModePipeline() {
    this.inputSource = InputSource.IMAGE;
    // Initializes a new MediaPipe Face Mesh solution instance in the static image mode.
    facemesh =
        new FaceMesh(
            this,
            FaceMeshOptions.builder()
                .setStaticImageMode(true)
                .setRefineLandmarks(true)
                .setRunOnGpu(RUN_ON_GPU)
                .build());

    // Connects MediaPipe Face Mesh solution to the user-defined FaceMeshResultImageView.
    facemesh.setResultListener(
        faceMeshResult -> {
          imageView.setFaceMeshResult(faceMeshResult);
          runOnUiThread(() -> imageView.update());
        });
    facemesh.setErrorListener((message, e) -> Log.e(TAG, "MediaPipe Face Mesh error:" + message));

    // Updates the preview layout.
    frameLayout.removeAllViewsInLayout();
    imageView.setImageDrawable(null);
    frameLayout.addView(imageView);
    imageView.setVisibility(View.VISIBLE);
  }

  /** Sets up the UI components for the video demo. */
  private void setupVideoDemoUiComponents() {
    // The Intent to access gallery and read a video file.
    videoGetter =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
              Intent resultIntent = result.getData();
              if (resultIntent != null) {
                if (result.getResultCode() == RESULT_OK) {
                  glSurfaceView.post(
                      () ->
                          videoInput.start(
                              this,
                              resultIntent.getData(),
                              facemesh.getGlContext(),
                              glSurfaceView.getWidth(),
                              glSurfaceView.getHeight()));
                }
              }
            });
    Button loadVideoButton = findViewById(R.id.button_load_video);
    loadVideoButton.setOnClickListener(
        v -> {
          stopCurrentPipeline();
          setupStreamingModePipeline(InputSource.VIDEO);
          // Reads video from gallery.
          Intent pickVideoIntent = new Intent(Intent.ACTION_PICK);
          pickVideoIntent.setDataAndType(MediaStore.Video.Media.INTERNAL_CONTENT_URI, "video/*");
          videoGetter.launch(pickVideoIntent);
        });
  }

  /** Sets up the UI components for the live demo with camera input. */
  private void setupLiveDemoUiComponents() {
    Button startCameraButton = findViewById(R.id.button_start_camera);
    startCameraButton.setOnClickListener(
        v -> {
          if (inputSource == InputSource.CAMERA) {
            return;
          }
          stopCurrentPipeline();
          setupStreamingModePipeline(InputSource.CAMERA);
        });
  }

  /** Sets up core workflow for streaming mode. */
  private void setupStreamingModePipeline(InputSource inputSource) {
    this.inputSource = inputSource;
    // Initializes a new MediaPipe Face Mesh solution instance in the streaming mode.
    facemesh =
        new FaceMesh(
            this,
            FaceMeshOptions.builder()
                .setStaticImageMode(false)
                .setRefineLandmarks(true)
                .setRunOnGpu(RUN_ON_GPU)
                .build());
    facemesh.setErrorListener((message, e) -> Log.e(TAG, "MediaPipe Face Mesh error:" + message));

    if (inputSource == InputSource.CAMERA) {
      cameraInput = new CameraInput(this);
      cameraInput.setNewFrameListener(textureFrame -> facemesh.send(textureFrame));
    } else if (inputSource == InputSource.VIDEO) {
      videoInput = new VideoInput(this);
      videoInput.setNewFrameListener(textureFrame -> facemesh.send(textureFrame));
    }

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
    if (inputSource == InputSource.CAMERA) {
      glSurfaceView.post(this::startCamera);
    }

    // Updates the preview layout.
    FrameLayout frameLayout = findViewById(R.id.preview_display_layout);
    imageView.setVisibility(View.GONE);
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
    if (cameraInput != null) {
      cameraInput.setNewFrameListener(null);
      cameraInput.close();
    }
    if (videoInput != null) {
      videoInput.setNewFrameListener(null);
      videoInput.close();
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

    String logText = String.format("x = (%.0f, %.0f, %.0f)\n" +
                    "y = (%.0f, %.0f, %.0f)\n" +
                    "z = (%.0f, %.0f, %.0f)\n" +
                    "angleZ = (%.0f, %.0f, %.0f)\n\n%s",
            normX[0], normX[1], normX[2],
            normY[0], normY[1], normY[2],
            normZ[0], normZ[1], normZ[2],
            angleZ[0], angleZ[1], angleZ[2],
            angleIsForward(angleZ) ? "FORWARD!!" : "");

    runOnUiThread(() -> {
      resultTextView.setText(logText);
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
    int error = 5;
    return isAboutEqual(vect[0], 90, error) &&
            isAboutEqual(vect[1], 180, error) &&
            isAboutEqual(vect[2], 90, error);
  }

  public static boolean isAboutEqual(double number, double approx, double error) {
    return approx - error <= number && number <= approx + error;
  }

}
