/*
 * Copyright 2018 Google LLC.
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

/** Planet rotation and speed settings */
public class SolarSettings {
  private float orbitSpeedMultiplier = 1.0f;
  private float rotationSpeedMultiplier = 1.0f;

  public void setOrbitSpeedMultiplier(float orbitSpeedMultiplier) {
    this.orbitSpeedMultiplier = orbitSpeedMultiplier;
  }

  public float getOrbitSpeedMultiplier() {
    return orbitSpeedMultiplier;
  }

  public void setRotationSpeedMultiplier(float rotationSpeedMultiplier) {
    this.rotationSpeedMultiplier = rotationSpeedMultiplier;
  }

  public float getRotationSpeedMultiplier() {
    return rotationSpeedMultiplier;
  }
}
