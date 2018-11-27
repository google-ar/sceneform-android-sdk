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
package com.google.ar.sceneform.samples.hellosceneform;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Toast;
import com.google.ar.core.Anchor;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.ux.ArFragment;
import com.google.ar.sceneform.ux.TransformableNode;

/**
 * This is an example activity that uses the Sceneform UX package to make common AR tasks easier.
 */
public class HelloSceneformActivity extends AppCompatActivity {
  private static final String TAG = HelloSceneformActivity.class.getSimpleName();
  private static final double MIN_OPENGL_VERSION = 3.0;

  private ArFragment arFragment;
  private ModelRenderable andyRenderable;

  @Override
  @SuppressWarnings({"AndroidApiChecker", "FutureReturnValueIgnored"})
  // CompletableFuture requires api level 24
  // FutureReturnValueIgnored is not valid
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (!checkIsSupportedDeviceOrFinish(this)) {
      return;
    }

    setContentView(R.layout.activity_ux);
    arFragment = (ArFragment) getSupportFragmentManager().findFragmentById(R.id.ux_fragment);

    // When you build a Renderable, Sceneform loads its resources in the background while returning
    // a CompletableFuture. Call thenAccept(), handle(), or check isDone() before calling get().
 

    arFragment.setOnTapArPlaneListener(
        (HitResult hitResult, Plane plane, MotionEvent motionEvent) -> {
            initializeGallery();
            return;
          }
  }
  
  private void initializeGallery() {
  LinearLayout gallery = findViewById(R.id.gallery_layout);

 ImageView andy = new ImageView(this);
 andy.setImageResource(R.drawable.droid_thumb);
 andy.setContentDescription("andy");
 andy.setOnClickListener(view ->{addObject(Uri.parse("andy.sfb"));});
 gallery.addView(andy);

}
  
 private void addObject(Uri model) {
 Frame frame = fragment.getArSceneView().getArFrame();
 android.graphics.Point pt = getScreenCenter();
 List<HitResult> hits;
 if (frame != null) {
   hits = frame.hitTest(pt.x, pt.y);
   for (HitResult hit : hits) {
     Trackable trackable = hit.getTrackable();
     if (trackable instanceof Plane &&
             ((Plane) trackable).isPoseInPolygon(hit.getHitPose())) {
       placeObject(fragment, hit.createAnchor(), model);
       break;

     }
   }
 }
}
  
  private void placeObject(ArFragment fragment, Anchor anchor, Uri model) {
 CompletableFuture<Void> renderableFuture =
         ModelRenderable.builder()
                 .setSource(fragment.getContext(), model)
                 .build()
                 .thenAccept(renderable -> addNodeToScene(fragment, anchor, renderable))
                 .exceptionally((throwable -> {
                  AlertDialog.Builder builder = new AlertDialog.Builder(this);
                     builder.setMessage(throwable.getMessage())
                       .setTitle("Codelab error!");
                     AlertDialog dialog = builder.create();
                    dialog.show();
                   return null;
                 }));
}
  private void addNodeToScene(ArFragment fragment, Anchor anchor, Renderable renderable) {
 AnchorNode anchorNode = new AnchorNode(anchor);
 TransformableNode node = new TransformableNode(fragment.getTransformationSystem());
 node.setRenderable(renderable);
 node.setParent(anchorNode);
 fragment.getArSceneView().getScene().addChild(anchorNode);
 node.select();
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
