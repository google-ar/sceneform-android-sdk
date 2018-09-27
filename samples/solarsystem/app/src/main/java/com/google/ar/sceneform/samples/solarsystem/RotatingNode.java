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
package com.google.ar.sceneform.samples.solarsystem;

import android.animation.ObjectAnimator;
import android.support.annotation.Nullable;
import android.view.animation.LinearInterpolator;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.QuaternionEvaluator;
import com.google.ar.sceneform.math.Vector3;

/** Node demonstrating rotation and transformations. */
public class RotatingNode extends Node {
  // We'll use Property Animation to make this node rotate.
  @Nullable private ObjectAnimator orbitAnimation = null;
  private float degreesPerSecond = 90.0f;

  private final SolarSettings solarSettings;
  private final boolean isOrbit;
  private float lastSpeedMultiplier = 1.0f;

  public RotatingNode(SolarSettings solarSettings, boolean isOrbit) {
    this.solarSettings = solarSettings;
    this.isOrbit = isOrbit;
  }

  @Override
  public void onUpdate(FrameTime frameTime) {
    super.onUpdate(frameTime);

    // Animation hasn't been set up.
    if (orbitAnimation == null) {
      return;
    }

    // Check if we need to change the speed of rotation.
    float speedMultiplier = getSpeedMultiplier();

    // Nothing has changed. Continue rotating at the same speed.
    if (lastSpeedMultiplier == speedMultiplier) {
      return;
    }

    if (speedMultiplier == 0.0f) {
      orbitAnimation.pause();
    } else {
      orbitAnimation.resume();

      float animatedFraction = orbitAnimation.getAnimatedFraction();
      orbitAnimation.setDuration(getAnimationDuration());
      orbitAnimation.setCurrentFraction(animatedFraction);
    }
    lastSpeedMultiplier = speedMultiplier;
  }

  /** Sets rotation speed */
  public void setDegreesPerSecond(float degreesPerSecond) {
    this.degreesPerSecond = degreesPerSecond;
  }

  @Override
  public void onActivate() {
    startAnimation();
  }

  @Override
  public void onDeactivate() {
    stopAnimation();
  }

  private long getAnimationDuration() {
    return (long) (1000 * 360 / (degreesPerSecond * getSpeedMultiplier()));
  }

  private float getSpeedMultiplier() {
    if (isOrbit) {
      return solarSettings.getOrbitSpeedMultiplier();
    } else {
      return solarSettings.getRotationSpeedMultiplier();
    }
  }

  private void startAnimation() {
    if (orbitAnimation != null) {
      return;
    }
    orbitAnimation = createAnimator();
    orbitAnimation.setTarget(this);
    orbitAnimation.setDuration(getAnimationDuration());
    orbitAnimation.start();
  }

  private void stopAnimation() {
    if (orbitAnimation == null) {
      return;
    }
    orbitAnimation.cancel();
    orbitAnimation = null;
  }

  /** Returns an ObjectAnimator that makes this node rotate. */
  private static ObjectAnimator createAnimator() {
    // Node's setLocalRotation method accepts Quaternions as parameters.
    // First, set up orientations that will animate a circle.
    Quaternion orientation1 = Quaternion.axisAngle(new Vector3(0.0f, 1.0f, 0.0f), 0);
    Quaternion orientation2 = Quaternion.axisAngle(new Vector3(0.0f, 1.0f, 0.0f), 120);
    Quaternion orientation3 = Quaternion.axisAngle(new Vector3(0.0f, 1.0f, 0.0f), 240);
    Quaternion orientation4 = Quaternion.axisAngle(new Vector3(0.0f, 1.0f, 0.0f), 360);

    ObjectAnimator orbitAnimation = new ObjectAnimator();
    orbitAnimation.setObjectValues(orientation1, orientation2, orientation3, orientation4);

    // Next, give it the localRotation property.
    orbitAnimation.setPropertyName("localRotation");

    // Use Sceneform's QuaternionEvaluator.
    orbitAnimation.setEvaluator(new QuaternionEvaluator());

    //  Allow orbitAnimation to repeat forever
    orbitAnimation.setRepeatCount(ObjectAnimator.INFINITE);
    orbitAnimation.setRepeatMode(ObjectAnimator.RESTART);
    orbitAnimation.setInterpolator(new LinearInterpolator());
    orbitAnimation.setAutoCancel(true);

    return orbitAnimation;
  }
}
