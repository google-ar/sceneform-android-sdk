/*
 * Copyright 2018 Google LLC
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

package com.google.ar.sceneform.samples.animation;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;
import com.google.ar.core.Anchor;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.SkeletonNode;
import com.google.ar.sceneform.animation.ModelAnimator;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.AnimationData;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.ux.ArFragment;

/** Demonstrates playing animated FBX models. */
public class MainActivity extends AppCompatActivity {

  private static final String TAG = "AnimationSample";
  private static final int ANDY_RENDERABLE = 1;
  private static final int HAT_RENDERABLE = 2;
  private static final String HAT_BONE_NAME = "hat_point";
  private ArFragment arFragment;
  // Model loader class to avoid leaking the activity context.
  private ModelLoader modelLoader;
  private ModelRenderable andyRenderable;
  private AnchorNode anchorNode;
  private SkeletonNode andy;
  // Controls animation playback.
  private ModelAnimator animator;
  // Index of the current animation playing.
  private int nextAnimation;
  // The UI to play next animation.
  private FloatingActionButton animationButton;
  // The UI to toggle wearing the hat.
  private FloatingActionButton hatButton;
  private Node hatNode;
  private ModelRenderable hatRenderable;

  @Override
  @SuppressWarnings({"AndroidApiChecker", "FutureReturnValueIgnored"})
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    arFragment = (ArFragment) getSupportFragmentManager().findFragmentById(R.id.sceneform_fragment);

    modelLoader = new ModelLoader(this);

    modelLoader.loadModel(ANDY_RENDERABLE, R.raw.andy_dance);
    modelLoader.loadModel(HAT_RENDERABLE, R.raw.baseball_cap);

    // When a plane is tapped, the model is placed on an Anchor node anchored to the plane.
    arFragment.setOnTapArPlaneListener(this::onPlaneTap);

    // Add a frame update listener to the scene to control the state of the buttons.
    arFragment.getArSceneView().getScene().addOnUpdateListener(this::onFrameUpdate);

    // Once the model is placed on a plane, this button plays the animations.
    animationButton = findViewById(R.id.animate);
    animationButton.setEnabled(false);
    animationButton.setOnClickListener(this::onPlayAnimation);

    // Place or remove a hat on Andy's head showing how to use Skeleton Nodes.
    hatButton = findViewById(R.id.hat);
    hatButton.setEnabled(false);
    hatButton.setOnClickListener(this::onToggleHat);
  }

  private void onPlayAnimation(View unusedView) {
    if (animator == null || !animator.isRunning()) {
      AnimationData data = andyRenderable.getAnimationData(nextAnimation);
      nextAnimation = (nextAnimation + 1) % andyRenderable.getAnimationDataCount();
      animator = new ModelAnimator(data, andyRenderable);
      animator.start();
      Toast toast = Toast.makeText(this, data.getName(), Toast.LENGTH_SHORT);
      Log.d(
          TAG,
          String.format(
              "Starting animation %s - %d ms long", data.getName(), data.getDurationMs()));
      toast.setGravity(Gravity.CENTER, 0, 0);
      toast.show();
    }
  }

  /*
   * Used as the listener for setOnTapArPlaneListener.
   */
  private void onPlaneTap(HitResult hitResult, Plane unusedPlane, MotionEvent unusedMotionEvent) {
    if (andyRenderable == null || hatRenderable == null) {
      return;
    }
    // Create the Anchor.
    Anchor anchor = hitResult.createAnchor();

    if (anchorNode == null) {
      anchorNode = new AnchorNode(anchor);
      anchorNode.setParent(arFragment.getArSceneView().getScene());

      andy = new SkeletonNode();

      andy.setParent(anchorNode);
      andy.setRenderable(andyRenderable);
      hatNode = new Node();

      // Attach a node to the bone.  This node takes the internal scale of the bone, so any
      // renderables should be added to child nodes with the world pose reset.
      // This also allows for tweaking the position relative to the bone.
      Node boneNode = new Node();
      boneNode.setParent(andy);
      andy.setBoneAttachment(HAT_BONE_NAME, boneNode);
      hatNode.setRenderable(hatRenderable);
      hatNode.setParent(boneNode);
      hatNode.setWorldScale(Vector3.one());
      hatNode.setWorldRotation(Quaternion.identity());
      Vector3 pos = hatNode.getWorldPosition();

      // Lower the hat down over the antennae.
      pos.y -= .1f;

      hatNode.setWorldPosition(pos);
    }
  }
  /**
   * Called on every frame, control the state of the buttons.
   *
   * @param unusedframeTime
   */
  private void onFrameUpdate(FrameTime unusedframeTime) {
    // If the model has not been placed yet, disable the buttons.
    if (anchorNode == null) {
      if (animationButton.isEnabled()) {
        animationButton.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.GRAY));
        animationButton.setEnabled(false);
        hatButton.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.GRAY));
        hatButton.setEnabled(false);
      }
    } else {
      if (!animationButton.isEnabled()) {
        animationButton.setBackgroundTintList(
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.colorAccent)));
        animationButton.setEnabled(true);
        hatButton.setEnabled(true);
        hatButton.setBackgroundTintList(
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.colorPrimary)));
      }
    }
  }

  private void onToggleHat(View unused) {
    if (hatNode != null) {
      hatNode.setEnabled(!hatNode.isEnabled());

      // Set the state of the hat button based on the hat node.
      if (hatNode.isEnabled()) {
        hatButton.setBackgroundTintList(
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.colorPrimary)));
      } else {
        hatButton.setBackgroundTintList(
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.colorAccent)));
      }
    }
  }

  void setRenderable(int id, ModelRenderable renderable) {
    if (id == ANDY_RENDERABLE) {
      this.andyRenderable = renderable;
    } else {
      this.hatRenderable = renderable;
    }
  }

  void onException(int id, Throwable throwable) {
    Toast toast = Toast.makeText(this, "Unable to load renderable: " + id, Toast.LENGTH_LONG);
    toast.setGravity(Gravity.CENTER, 0, 0);
    toast.show();
    Log.e(TAG, "Unable to load andy renderable", throwable);
  }
}
