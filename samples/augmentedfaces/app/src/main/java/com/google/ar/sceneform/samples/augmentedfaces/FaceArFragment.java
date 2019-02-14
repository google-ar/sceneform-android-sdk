/*
 * Copyright 2019 Google LLC. All Rights Reserved.
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
package com.google.ar.sceneform.samples.augmentedfaces;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.google.ar.core.Config;
import com.google.ar.core.Config.AugmentedFaceMode;
import com.google.ar.core.Session;
import com.google.ar.sceneform.ux.ArFragment;
import java.util.EnumSet;
import java.util.Set;

/** Implements ArFragment and configures the session for using the augmented faces feature. */
public class FaceArFragment extends ArFragment {

  @Override
  protected Config getSessionConfiguration(Session session) {
    Config config = new Config(session);
    config.setAugmentedFaceMode(AugmentedFaceMode.MESH3D);
    return config;
  }

  @Override
  protected Set<Session.Feature> getSessionFeatures() {
    return EnumSet.of(Session.Feature.FRONT_CAMERA);
  }

  /**
   * Override to turn off planeDiscoveryController. Plane trackables are not supported with the
   * front camera.
   */
  @Override
  public View onCreateView(
      LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    FrameLayout frameLayout =
        (FrameLayout) super.onCreateView(inflater, container, savedInstanceState);

    getPlaneDiscoveryController().hide();
    getPlaneDiscoveryController().setInstructionView(null);

    return frameLayout;
  }
}
