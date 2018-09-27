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

import android.support.annotation.Nullable;
import android.view.MotionEvent;
import com.google.ar.sceneform.HitTestResult;
import com.google.ar.sceneform.Node;

/**
 * Base class for a gesture.
 *
 * <p>A gesture represents a sequence of touch events that are detected to represent a particular
 * type of motion (i.e. Dragging, Pinching).
 *
 * <p>Gestures are created and updated by BaseGestureRecognizer's.
 */
public abstract class BaseGesture<T extends BaseGesture<T>> {
  /** Interface definition for callbacks to be invoked by a {@link BaseGesture}. */
  public interface OnGestureEventListener<T extends BaseGesture<T>> {
    void onUpdated(T gesture);

    void onFinished(T gesture);
  }

  protected final GesturePointersUtility gesturePointersUtility;

  private boolean hasStarted;
  private boolean justStarted;
  private boolean hasFinished;
  private boolean wasCancelled;

  @Nullable protected Node targetNode;
  @Nullable private OnGestureEventListener<T> eventListener;

  public BaseGesture(GesturePointersUtility gesturePointersUtility) {
    this.gesturePointersUtility = gesturePointersUtility;
  }

  public boolean hasStarted() {
    return hasStarted;
  }

  public boolean justStarted() {
    return justStarted;
  }

  public boolean hasFinished() {
    return hasFinished;
  }

  public boolean wasCancelled() {
    return wasCancelled;
  }

  public @Nullable Node getTargetNode() {
    return targetNode;
  }

  public float inchesToPixels(float inches) {
    return gesturePointersUtility.inchesToPixels(inches);
  }

  public float pixelsToInches(float pixels) {
    return gesturePointersUtility.pixelsToInches(pixels);
  }

  public void setGestureEventListener(@Nullable OnGestureEventListener<T> listener) {
    eventListener = listener;
  }

  public void onTouch(HitTestResult hitTestResult, MotionEvent motionEvent) {
    if (!hasStarted && canStart(hitTestResult, motionEvent)) {
      start(hitTestResult, motionEvent);
      return;
    }
    justStarted = false;
    if (hasStarted) {
      if (updateGesture(hitTestResult, motionEvent)) {
        dispatchUpdateEvent();
      }
    }
  }

  protected abstract boolean canStart(HitTestResult hitTestResult, MotionEvent motionEvent);

  protected abstract void onStart(HitTestResult hitTestResult, MotionEvent motionEvent);

  protected abstract boolean updateGesture(HitTestResult hitTestResult, MotionEvent motionEvent);

  protected abstract void onCancel();

  protected abstract void onFinish();

  protected void cancel() {
    wasCancelled = true;
    onCancel();
    complete();
  }

  protected void complete() {
    hasFinished = true;
    if (hasStarted) {
      onFinish();
      dispatchFinishedEvent();
    }
  }

  private void start(HitTestResult hitTestResult, MotionEvent motionEvent) {
    hasStarted = true;
    justStarted = true;
    onStart(hitTestResult, motionEvent);
  }

  private void dispatchUpdateEvent() {
    if (eventListener != null) {
      eventListener.onUpdated(getSelf());
    }
  }

  private void dispatchFinishedEvent() {
    if (eventListener != null) {
      eventListener.onFinished(getSelf());
    }
  }

  // For compile-time safety so we don't need to cast when dispatching events.
  protected abstract T getSelf();
}
