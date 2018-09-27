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
package com.google.ar.sceneform.ux;

import android.support.annotation.Nullable;
import android.view.View;

/**
 * This view manages showing the plane discovery instructions view. You can assign into the
 * planeDiscoveryView to override the default visual, or assign null to remove it.
 */
public class PlaneDiscoveryController {
  private @Nullable View planeDiscoveryView;

  public PlaneDiscoveryController(@Nullable View planeDiscoveryView) {
    this.planeDiscoveryView = planeDiscoveryView;
  }

  /** Set the instructions view to present over the Sceneform view. */
  public void setInstructionView(View view) {
    planeDiscoveryView = view;
  }

  /** Show the plane discovery UX instructions for finding a plane. */
  public void show() {
    if (planeDiscoveryView == null) {
      return;
    }

    planeDiscoveryView.setVisibility(View.VISIBLE);
  }

  /** Hide the plane discovery UX instructions. */
  public void hide() {
    if (planeDiscoveryView == null) {
      return;
    }

    planeDiscoveryView.setVisibility(View.GONE);
  }
}
