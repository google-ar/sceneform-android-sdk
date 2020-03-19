package com.google.ar.sceneform;

import android.view.MotionEvent;
import android.view.View;

import com.google.ar.sceneform.collision.Plane;
import com.google.ar.sceneform.collision.Ray;
import com.google.ar.sceneform.collision.RayHit;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.ViewRenderable;
import com.google.ar.sceneform.utilities.Preconditions;

/** Helper class for utility functions for touching a view rendered in world space. */

class ViewTouchHelpers {
  /**
   * Dispatches a touch event to a node's ViewRenderable if that node has a ViewRenderable by
   * converting the touch event into the local coordinate space of the view.
   */
  static boolean dispatchTouchEventToView(Node node, MotionEvent motionEvent) {
    Preconditions.checkNotNull(node, "Parameter \"node\" was null.");
    Preconditions.checkNotNull(motionEvent, "Parameter \"motionEvent\" was null.");

    if (!(node.getRenderable() instanceof ViewRenderable)) {
      return false;
    }

    if (!node.isActive()) {
      return false;
    }

    Scene scene = node.getScene();
    if (scene == null) {
      return false;
    }

    ViewRenderable viewRenderable = (ViewRenderable) node.getRenderable();
    if (viewRenderable == null) {
      return false;
    }

    int pointerCount = motionEvent.getPointerCount();

    MotionEvent.PointerProperties[] pointerProperties =
        new MotionEvent.PointerProperties[pointerCount];

    MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[pointerCount];

    /*
     * Cast a ray against a plane that extends to infinity located where the view is in 3D space
     * instead of casting against the node's collision shape. This is important for the UX of touch
     * events after the initial ACTION_DOWN event. i.e. If a user is dragging a slider and their
     * finger moves beyond the view the position of their finger relative to the slider should still
     * be respected.
     */
    Plane plane = new Plane(node.getWorldPosition(), node.getForward());
    RayHit rayHit = new RayHit();

    // Also cast a ray against a back-facing plane because we render the view as double-sided.
    Plane backPlane = new Plane(node.getWorldPosition(), node.getBack());

    // Convert the pointer coordinates for each pointer into the view's local coordinate space.
    for (int i = 0; i < pointerCount; i++) {
      MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
      MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();

      motionEvent.getPointerProperties(i, props);
      motionEvent.getPointerCoords(i, coords);

      Camera camera = scene.getCamera();
      Ray ray = camera.screenPointToRay(coords.x, coords.y);
      if (plane.rayIntersection(ray, rayHit)) {
        Vector3 viewPosition =
            convertWorldPositionToLocalView(node, rayHit.getPoint(), viewRenderable);

        coords.x = viewPosition.x;
        coords.y = viewPosition.y;
      } else if (backPlane.rayIntersection(ray, rayHit)) {
        Vector3 viewPosition =
            convertWorldPositionToLocalView(node, rayHit.getPoint(), viewRenderable);

        // Flip the x coordinate for the back-facing plane.
        coords.x = viewRenderable.getView().getWidth() - viewPosition.x;
        coords.y = viewPosition.y;
      } else {
        coords.clear();
        props.clear();
      }

      pointerProperties[i] = props;
      pointerCoords[i] = coords;
    }

    // We must copy the touch event with the new coordinates and dispatch it to the view.
    MotionEvent me =
        MotionEvent.obtain(
            motionEvent.getDownTime(),
            motionEvent.getEventTime(),
            motionEvent.getAction(),
            pointerCount,
            pointerProperties,
            pointerCoords,
            motionEvent.getMetaState(),
            motionEvent.getButtonState(),
            motionEvent.getXPrecision(),
            motionEvent.getYPrecision(),
            motionEvent.getDeviceId(),
            motionEvent.getEdgeFlags(),
            motionEvent.getSource(),
            motionEvent.getFlags());

    return viewRenderable.getView().dispatchTouchEvent(me);
  }

  static Vector3 convertWorldPositionToLocalView(
      Node node, Vector3 worldPos, ViewRenderable viewRenderable) {
    Preconditions.checkNotNull(node, "Parameter \"node\" was null.");
    Preconditions.checkNotNull(worldPos, "Parameter \"worldPos\" was null.");
    Preconditions.checkNotNull(viewRenderable, "Parameter \"viewRenderable\" was null.");

    // Find where the view renderable is being touched in local space.
    // this will be in meters relative to the bottom-middle of the view.
    Vector3 localPos = node.worldToLocalPoint(worldPos);

    // Calculate the pixels to meters ratio.
    View view = viewRenderable.getView();
    int width = view.getWidth();
    int height = view.getHeight();
    float pixelsToMetersRatio = getPixelsToMetersRatio(viewRenderable);

    // We must convert the position to pixels
    int xPixels = (int) (localPos.x * pixelsToMetersRatio);
    int yPixels = (int) (localPos.y * pixelsToMetersRatio);

    // We must convert the coordinates from the renderable's alignment origin to top-left origin.

    int halfWidth = width / 2;
    int halfHeight = height / 2;

    ViewRenderable.VerticalAlignment verticalAlignment = viewRenderable.getVerticalAlignment();
    switch (verticalAlignment) {
      case BOTTOM:
        yPixels = height - yPixels;
        break;
      case CENTER:
        yPixels = height - (yPixels + halfHeight);
        break;
      case TOP:
        yPixels = height - (yPixels + height);
        break;
    }

    ViewRenderable.HorizontalAlignment horizontalAlignment =
        viewRenderable.getHorizontalAlignment();
    switch (horizontalAlignment) {
      case LEFT:
        // Do nothing.
        break;
      case CENTER:
        xPixels = (xPixels + halfWidth);
        break;
      case RIGHT:
        xPixels = xPixels + width;
        break;
    }

    return new Vector3(xPixels, yPixels, 0.0f);
  }

  private static float getPixelsToMetersRatio(ViewRenderable viewRenderable) {
    View view = viewRenderable.getView();
    int width = view.getWidth();
    Vector3 size = viewRenderable.getSizer().getSize(viewRenderable.getView());

    if (size.x == 0.0f) {
      return 0.0f;
    }

    return (float) width / size.x;
  }
}
