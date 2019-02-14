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

import android.util.Log;
import android.widget.Toast;
import com.google.ar.core.Config;
import com.google.ar.core.Session;

import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import java.util.Collections;
import java.util.Set;

/**
 * Implements AR Required ArFragment. Does not require additional permissions and uses the default
 * configuration for ARCore.
 */
public class ArFragment extends BaseArFragment {
  private static final String TAG = "StandardArFragment";

  @Override
  public boolean isArRequired() {
    return true;
  }

  @Override
  public String[] getAdditionalPermissions() {
    return new String[0];
  }

  @Override
  protected void handleSessionException(UnavailableException sessionException) {

    String message;
    if (sessionException instanceof UnavailableArcoreNotInstalledException) {
      message = "Please install ARCore";
    } else if (sessionException instanceof UnavailableApkTooOldException) {
      message = "Please update ARCore";
    } else if (sessionException instanceof UnavailableSdkTooOldException) {
      message = "Please update this app";
    } else if (sessionException instanceof UnavailableDeviceNotCompatibleException) {
      message = "This device does not support AR";
    } else {
      message = "Failed to create AR session";
    }
    Log.e(TAG, "Error: " + message, sessionException);
    Toast.makeText(requireActivity(), message, Toast.LENGTH_LONG).show();
  }

  @Override
  protected Config getSessionConfiguration(Session session) {
    return new Config(session);
  }

  
  @Override
  protected Set<Session.Feature> getSessionFeatures() {
    return Collections.emptySet();
  }
}
