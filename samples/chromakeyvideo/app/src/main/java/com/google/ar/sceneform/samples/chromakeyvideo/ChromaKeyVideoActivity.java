/*
 * Copyright 2018 Google LLC. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.sceneform.samples.chromakeyvideo;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Toast;
import com.google.ar.core.Anchor;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.Color;
import com.google.ar.sceneform.rendering.ExternalTexture;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.ux.ArFragment;

/**
 * This is an example activity that shows how to display a video with chroma key filtering in
 * Sceneform.
 */
public class ChromaKeyVideoActivity extends AppCompatActivity {
  private static final String TAG = ChromaKeyVideoActivity.class.getSimpleName();
  private static final double MIN_OPENGL_VERSION = 3.0;

  private ArFragment arFragment;

  @Nullable private ModelRenderable videoRenderable;
  private MediaPlayer mediaPlayer;

  // The color to filter out of the video.
  private static final Color CHROMA_KEY_COLOR = new Color(0.1843f, 1.0f, 0.098f);

  // Controls the height of the video in world space.
  private static final float VIDEO_HEIGHT_METERS = 0.85f;

  @Override
  @SuppressWarnings({"AndroidApiChecker", "FutureReturnValueIgnored"})
  // CompletableFuture requires api level 24
  // FutureReturnValueIgnored is not valid
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (!checkIsSupportedDeviceOrFinish(this)) {
      return;
    }

    setContentView(R.layout.activity_video);
    arFragment = (ArFragment) getSupportFragmentManager().findFragmentById(R.id.ux_fragment);

    // Create an ExternalTexture for displaying the contents of the video.
    ExternalTexture texture = new ExternalTexture();

    // Create an Android MediaPlayer to capture the video on the external texture's surface.
    mediaPlayer = MediaPlayer.create(this, R.raw.lion_chroma);
    mediaPlayer.setSurface(texture.getSurface());
    mediaPlayer.setLooping(true);

    // Create a renderable with a material that has a parameter of type 'samplerExternal' so that
    // it can display an ExternalTexture. The material also has an implementation of a chroma key
    // filter.
    ModelRenderable.builder()
        .setSource(this, R.raw.chroma_key_video)
        .build()
        .thenAccept(
            renderable -> {
              videoRenderable = renderable;
              renderable.getMaterial().setExternalTexture("videoTexture", texture);
              renderable.getMaterial().setFloat4("keyColor", CHROMA_KEY_COLOR);
            })
        .exceptionally(
            throwable -> {
              Toast toast =
                  Toast.makeText(this, "Unable to load video renderable", Toast.LENGTH_LONG);
              toast.setGravity(Gravity.CENTER, 0, 0);
              toast.show();
              return null;
            });

    arFragment.setOnTapArPlaneListener(
        (HitResult hitResult, Plane plane, MotionEvent motionEvent) -> {
          if (videoRenderable == null) {
            return;
          }

          // Create the Anchor.
          Anchor anchor = hitResult.createAnchor();
          AnchorNode anchorNode = new AnchorNode(anchor);
          anchorNode.setParent(arFragment.getArSceneView().getScene());

          // Create a node to render the video and add it to the anchor.
          Node videoNode = new Node();
          videoNode.setParent(anchorNode);

          // Set the scale of the node so that the aspect ratio of the video is correct.
          float videoWidth = mediaPlayer.getVideoWidth();
          float videoHeight = mediaPlayer.getVideoHeight();
          videoNode.setLocalScale(
              new Vector3(
                  VIDEO_HEIGHT_METERS * (videoWidth / videoHeight), VIDEO_HEIGHT_METERS, 1.0f));

          // Start playing the video when the first node is placed.
          if (!mediaPlayer.isPlaying()) {
            mediaPlayer.start();

            // Wait to set the renderable until the first frame of the  video becomes available.
            // This prevents the renderable from briefly appearing as a black quad before the video
            // plays.
            texture
                .getSurfaceTexture()
                .setOnFrameAvailableListener(
                    (SurfaceTexture surfaceTexture) -> {
                      videoNode.setRenderable(videoRenderable);
                      texture.getSurfaceTexture().setOnFrameAvailableListener(null);
                    });
          } else {
            videoNode.setRenderable(videoRenderable);
          }
        });
  }

  @Override
  public void onDestroy() {
    super.onDestroy();

    if (mediaPlayer != null) {
      mediaPlayer.release();
      mediaPlayer = null;
    }
  }

  /**
   * Returns false and displays an error message if Sceneform can not run, true if Sceneform can run
   * on this device.
   *
   * <p>Sceneform requires Android N on the device as well as OpenGL 3.0 capabilities.
   *
   * <p>Finishes the activity if Sceneform can not run
   */
  public static boolean checkIsSupportedDeviceOrFinish(final Activity activity) {
    if (Build.VERSION.SDK_INT < VERSION_CODES.N) {
      Log.e(TAG, "Sceneform requires Android N or later");
      Toast.makeText(activity, "Sceneform requires Android N or later", Toast.LENGTH_LONG).show();
      activity.finish();
      return false;
    }
    String openGlVersionString =
        ((ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE))
            .getDeviceConfigurationInfo()
            .getGlEsVersion();
    if (Double.parseDouble(openGlVersionString) < MIN_OPENGL_VERSION) {
      Log.e(TAG, "Sceneform requires OpenGL ES 3.0 later");
      Toast.makeText(activity, "Sceneform requires OpenGL ES 3.0 or later", Toast.LENGTH_LONG)
          .show();
      activity.finish();
      return false;
    }
    return true;
  }
}
