/*
 * Copyright 2018 Google LLC All Rights Reserved.
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

import android.view.MotionEvent;
import com.google.ar.sceneform.HitTestResult;

/**
 * Detects various gestures used for transforming the position, rotation, and scale of Nodes.
 *
 * @deprecated Will be removed in release 1.6. Functionality has been merged into {@link
 *     TransformationSystem}.
 */
@Deprecated
public interface TransformationGestureDetector {
  DragGestureRecognizer getDragRecognizer();

  PinchGestureRecognizer getPinchRecognizer();

  TwistGestureRecognizer getTwistRecognizer();

  void onTouch(HitTestResult hitTestResult, MotionEvent motionEvent);
}
