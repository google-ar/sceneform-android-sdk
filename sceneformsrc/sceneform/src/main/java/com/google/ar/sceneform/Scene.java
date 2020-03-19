package com.google.ar.sceneform;

import android.media.Image;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.util.Log;
import android.view.MotionEvent;

import com.google.ar.sceneform.collision.Collider;
import com.google.ar.sceneform.collision.CollisionSystem;
import com.google.ar.sceneform.collision.Ray;
import com.google.ar.sceneform.rendering.Color;
import com.google.ar.sceneform.rendering.LightProbe;
import com.google.ar.sceneform.rendering.Renderer;
import com.google.ar.sceneform.utilities.AndroidPreconditions;
import com.google.ar.sceneform.utilities.EnvironmentalHdrParameters;
import com.google.ar.sceneform.utilities.LoadHelper;
import com.google.ar.sceneform.utilities.Preconditions;
import java.util.ArrayList;

/**
 * The Sceneform Scene maintains the scene graph, a hierarchical organization of a scene's content.
 * A scene can have zero or more child nodes and each node can have zero or more child nodes.
 *
 * <p>The Scene also provides hit testing, a way to detect which node is touched by a MotionEvent or
 * Ray.
 */
public class Scene extends NodeParent {
  /**
   * Interface definition for a callback to be invoked when a touch event is dispatched to a scene.
   * The callback will be invoked after the touch event is dispatched to the nodes in the scene if
   * no node consumed the event.
   */
  public interface OnTouchListener {
    /**
     * Called when a touch event is dispatched to a scene. The callback will be invoked after the
     * touch event is dispatched to the nodes in the scene if no node consumed the event. This is
     * called even if the touch is not over a node, in which case {@link HitTestResult#getNode()}
     * will be null.
     *
     * @see Scene#setOnTouchListener(OnTouchListener)
     * @param hitTestResult represents the node that was touched
     * @param motionEvent the motion event
     * @return true if the listener has consumed the event
     */
    boolean onSceneTouch(HitTestResult hitTestResult, MotionEvent motionEvent);
  }

  /**
   * Interface definition for a callback to be invoked when a touch event is dispatched to a scene.
   * The callback will be invoked before the {@link OnTouchListener} is invoked. This is invoked
   * even if the gesture was consumed, making it possible to observe all motion events dispatched to
   * the scene.
   */
  public interface OnPeekTouchListener {
    /**
     * Called when a touch event is dispatched to a scene. The callback will be invoked before the
     * {@link OnTouchListener} is invoked. This is invoked even if the gesture was consumed, making
     * it possible to observe all motion events dispatched to the scene. This is called even if the
     * touch is not over a node, in which case {@link HitTestResult#getNode()} will be null.
     *
     * @see Scene#setOnTouchListener(OnTouchListener)
     * @param hitTestResult represents the node that was touched
     * @param motionEvent the motion event
     */
    void onPeekTouch(HitTestResult hitTestResult, MotionEvent motionEvent);
  }

  /**
   * Interface definition for a callback to be invoked once per frame immediately before the scene
   * is updated.
   */
  public interface OnUpdateListener {
    /**
     * Called once per frame right before the Scene is updated.
     *
     * @param frameTime provides time information for the current frame
     */
    void onUpdate(FrameTime frameTime);
  }

  private static final String TAG = Scene.class.getSimpleName();
  private static final String DEFAULT_LIGHTPROBE_ASSET_NAME = "small_empty_house_2k";
  private static final String DEFAULT_LIGHTPROBE_RESOURCE_NAME = "sceneform_default_light_probe";
  private static final float DEFAULT_EXPOSURE = 1.0f;
  public static final EnvironmentalHdrParameters DEFAULT_HDR_PARAMETERS =
      EnvironmentalHdrParameters.makeDefault();

  private final Camera camera;
  @Nullable private final Sun sunlightNode;
  @Nullable private final SceneView view;
  @Nullable private LightProbe lightProbe;
  private boolean lightProbeSet = false;
  private boolean isUnderTesting = false;

  // Systems.
  final CollisionSystem collisionSystem = new CollisionSystem();
  private final TouchEventSystem touchEventSystem = new TouchEventSystem();

  private final ArrayList<OnUpdateListener> onUpdateListeners = new ArrayList<>();

  @SuppressWarnings("VisibleForTestingUsed")
  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  Scene() {
    view = null;
    lightProbe = null;
    camera = new Camera(true);
    if (!AndroidPreconditions.isMinAndroidApiLevel()) {
      // Enforce min api level 24
      sunlightNode = null;
    } else {
      sunlightNode = new Sun();
    }

    isUnderTesting = true;
  }

  /** Create a scene with the given context. */
  @SuppressWarnings("initialization") // Suppress @UnderInitialization warning.
  public Scene(SceneView view) {
    Preconditions.checkNotNull(view, "Parameter \"view\" was null.");
    this.view = view;
    camera = new Camera(this);
    if (!AndroidPreconditions.isMinAndroidApiLevel()) {
      // Enforce min api level 24
      sunlightNode = null;
      return;
    }
    sunlightNode = new Sun(this);

    // Setup the default lighting for the scene, if it exists.
    setupLightProbe(view);
  }

  /** Returns the SceneView used to create the scene. */
  public SceneView getView() {
    // the view field cannot be marked for the purposes of unit testing.
    // Add this check for static analysis go/nullness.
    if (view == null) {
      throw new IllegalStateException("Scene's view must not be null.");
    }

    return view;
  }

  /**
   * Get the camera that is used to render the scene. The camera is a type of node.
   *
   * @return the camera used to render the scene
   */
  public Camera getCamera() {
    return camera;
  }

  /**
   * Get the default sunlight node.
   *
   * @return the sunlight node used to light the scene
   */
  @Nullable
  public Node getSunlight() {
    return sunlightNode;
  }

  /**
   * Get the Light Probe that defines the lighting environment for the scene.
   *
   * @return the light probe used for reflections and indirect lighting.
   * @hide for 1.0 as we don't yet have tools support
   */
  public LightProbe getLightProbe() {
    // the lightProbe field cannot be marked for the purposes of unit testing.
    // Add this check for static analysis go/nullness.
    if (lightProbe == null) {
      throw new IllegalStateException("Scene's lightProbe must not be null.");
    }
    return lightProbe;
  }

  /**
   * Set a new Light Probe for the scene, this affects reflections and indirect lighting.
   *
   * @param lightProbe the fully loaded LightProbe to be used as the lighting environment.
   * @hide for 1.0 as we don't yet have tools support
   */
  public void setLightProbe(LightProbe lightProbe) {
    Preconditions.checkNotNull(lightProbe, "Parameter \"lightProbe\" was null.");
    this.lightProbe = lightProbe;
    this.lightProbeSet = true;

    // the view field cannot be marked for the purposes of unit testing.
    // Add this check for static analysis go/nullness.
    if (view == null) {
      throw new IllegalStateException("Scene's view must not be null.");
    }
    Preconditions.checkNotNull(view.getRenderer()).setLightProbe(lightProbe);
  }

  /**
   * Register a callback to be invoked when the scene is touched. The callback will be invoked after
   * the touch event is dispatched to the nodes in the scene if no node consumed the event. This is
   * called even if the touch is not over a node, in which case {@link HitTestResult#getNode()} will
   * be null.
   *
   * @param onTouchListener the touch listener to attach
   */
  public void setOnTouchListener(@Nullable OnTouchListener onTouchListener) {
    touchEventSystem.setOnTouchListener(onTouchListener);
  }

  /**
   * Adds a listener that will be called before the {@link Scene.OnTouchListener} is invoked. This
   * is invoked even if the gesture was consumed, making it possible to observe all motion events
   * dispatched to the scene. This is called even if the touch is not over a node, in which case
   * {@link HitTestResult#getNode()} will be null. The listeners will be called in the order in
   * which they were added.
   *
   * @param onPeekTouchListener the peek touch listener to add
   */
  public void addOnPeekTouchListener(OnPeekTouchListener onPeekTouchListener) {
    touchEventSystem.addOnPeekTouchListener(onPeekTouchListener);
  }

  /**
   * Removes a listener that will be called before the {@link Scene.OnTouchListener} is invoked.
   * This is invoked even if the gesture was consumed, making it possible to observe all motion
   * events dispatched to the scene. This is called even if the touch is not over a node, in which
   * case {@link HitTestResult#getNode()} will be null.
   *
   * @param onPeekTouchListener the peek touch listener to remove
   */
  public void removeOnPeekTouchListener(OnPeekTouchListener onPeekTouchListener) {
    touchEventSystem.removeOnPeekTouchListener(onPeekTouchListener);
  }

  /**
   * Adds a listener that will be called once per frame immediately before the Scene is updated. The
   * listeners will be called in the order in which they were added.
   *
   * @param onUpdateListener the update listener to add
   */
  public void addOnUpdateListener(OnUpdateListener onUpdateListener) {
    Preconditions.checkNotNull(onUpdateListener, "Parameter 'onUpdateListener' was null.");
    if (!onUpdateListeners.contains(onUpdateListener)) {
      onUpdateListeners.add(onUpdateListener);
    }
  }

  /**
   * Removes a listener that will be called once per frame immediately before the Scene is updated.
   *
   * @param onUpdateListener the update listener to remove
   */
  public void removeOnUpdateListener(OnUpdateListener onUpdateListener) {
    Preconditions.checkNotNull(onUpdateListener, "Parameter 'onUpdateListener' was null.");
    onUpdateListeners.remove(onUpdateListener);
  }

  @Override
  public void onAddChild(Node child) {
    super.onAddChild(child);
    child.setSceneRecursively(this);
  }

  @Override
  public void onRemoveChild(Node child) {
    super.onRemoveChild(child);
    child.setSceneRecursively(null);
  }

  /**
   * Tests to see if a motion event is touching any nodes within the scene, based on a ray hit test
   * whose origin is the screen position of the motion event, and outputs a HitTestResult containing
   * the node closest to the screen.
   *
   * @param motionEvent the motion event to use for the test
   * @return the result includes the first node that was hit by the motion event (may be null), and
   *     information about where the motion event hit the node in world-space
   */
  public HitTestResult hitTest(MotionEvent motionEvent) {
    Preconditions.checkNotNull(motionEvent, "Parameter \"motionEvent\" was null.");

    if (camera == null) {
      return new HitTestResult();
    }

    Ray ray = camera.motionEventToRay(motionEvent);
    return hitTest(ray);
  }

  /**
   * Tests to see if a ray is hitting any nodes within the scene and outputs a HitTestResult
   * containing the node closest to the ray origin that intersects with the ray.
   *
   * @see Camera#screenPointToRay(float, float)
   * @param ray the ray to use for the test
   * @return the result includes the first node that was hit by the ray (may be null), and
   *     information about where the ray hit the node in world-space
   */
  public HitTestResult hitTest(Ray ray) {
    Preconditions.checkNotNull(ray, "Parameter \"ray\" was null.");

    HitTestResult result = new HitTestResult();
    Collider collider = collisionSystem.raycast(ray, result);
    if (collider != null) {
      result.setNode((Node) collider.getTransformProvider());
    }

    return result;
  }

  /**
   * Tests to see if a motion event is touching any nodes within the scene and returns a list of
   * HitTestResults containing all of the nodes that were hit, sorted by distance.
   *
   * @param motionEvent The motion event to use for the test.
   * @return Populated with a HitTestResult for each node that was hit sorted by distance. Empty if
   *     no nodes were hit.
   */
  public ArrayList<HitTestResult> hitTestAll(MotionEvent motionEvent) {
    Preconditions.checkNotNull(motionEvent, "Parameter \"motionEvent\" was null.");

    if (camera == null) {
      return new ArrayList<>();
    }
    Ray ray = camera.motionEventToRay(motionEvent);
    return hitTestAll(ray);
  }

  /**
   * Tests to see if a ray is hitting any nodes within the scene and returns a list of
   * HitTestResults containing all of the nodes that were hit, sorted by distance.
   *
   * @see Camera#screenPointToRay(float, float)
   * @param ray The ray to use for the test.
   * @return Populated with a HitTestResult for each node that was hit sorted by distance. Empty if
   *     no nodes were hit.
   */
  public ArrayList<HitTestResult> hitTestAll(Ray ray) {
    Preconditions.checkNotNull(ray, "Parameter \"ray\" was null.");

    ArrayList<HitTestResult> results = new ArrayList<>();

    collisionSystem.raycastAll(
        ray,
        results,
        (result, collider) -> result.setNode((Node) collider.getTransformProvider()),
        () -> new HitTestResult());

    return results;
  }

  /**
   * Tests to see if the given node's collision shape overlaps the collision shape of any other
   * nodes in the scene using {@link Node#getCollisionShape()}. The node used for testing does not
   * need to be active.
   *
   * @see #overlapTestAll(Node)
   * @param node The node to use for the test.
   * @return A node that is overlapping the test node. If no node is overlapping the test node, then
   *     this is null. If multiple nodes are overlapping the test node, then this could be any of
   *     them.
   */
  @Nullable
  public Node overlapTest(Node node) {
    Preconditions.checkNotNull(node, "Parameter \"node\" was null.");

    Collider collider = node.getCollider();
    if (collider == null) {
      return null;
    }

    Collider intersectedCollider = collisionSystem.intersects(collider);
    if (intersectedCollider == null) {
      return null;
    }

    return (Node) intersectedCollider.getTransformProvider();
  }

  /**
   * Tests to see if a node is overlapping any other nodes within the scene using {@link
   * Node#getCollisionShape()}. The node used for testing does not need to be active.
   *
   * @see #overlapTest(Node)
   * @param node The node to use for the test.
   * @return A list of all nodes that are overlapping the test node. If no node is overlapping the
   *     test node, then the list is empty.
   */
  public ArrayList<Node> overlapTestAll(Node node) {
    Preconditions.checkNotNull(node, "Parameter \"node\" was null.");

    ArrayList<Node> results = new ArrayList<>();

    Collider collider = node.getCollider();
    if (collider == null) {
      return results;
    }

    collisionSystem.intersectsAll(
        collider,
        (Collider intersectedCollider) ->
            results.add((Node) intersectedCollider.getTransformProvider()));

    return results;
  }

  /** Returns true if this Scene was created by a test. */
  boolean isUnderTesting() {
    return isUnderTesting;
  }

  /**
   * Sets whether the Scene should expect to use an Hdr light estimate, so that Filament light
   * settings can be adjusted appropriately.
   *
   * @hide intended for use by other Sceneform packages which update Hdr lighting every frame.
   */
  
  public void setUseHdrLightEstimate(boolean useHdrLightEstimate) {
    if (view != null) {
      Renderer renderer = Preconditions.checkNotNull(view.getRenderer());
      renderer.setUseHdrLightEstimate(useHdrLightEstimate);
    }
  }

  /**
   * Sets the current Hdr Light Estimate state to apply to the Filament scene.
   *
   * @hide intended for use by other Sceneform packages which update Hdr lighting every frame.
   */
  // incompatible types in argument.
  @SuppressWarnings("nullness:argument.type.incompatible")
  
  public void setEnvironmentalHdrLightEstimate(
      @Nullable float[] sphericalHarmonics,
      @Nullable float[] direction,
      Color colorCorrection,
      float relativeIntensity,
      @Nullable Image[] cubeMap) {
    float exposure;
    EnvironmentalHdrParameters hdrParameters;
    if (view == null) {
      exposure = DEFAULT_EXPOSURE;
      hdrParameters = DEFAULT_HDR_PARAMETERS;
    } else {
      Renderer renderer = Preconditions.checkNotNull(view.getRenderer());
      exposure = renderer.getExposure();
      hdrParameters = renderer.getEnvironmentalHdrParameters();
    }

    if (lightProbe != null) {
      if (sphericalHarmonics != null) {
        lightProbe.setEnvironmentalHdrSphericalHarmonics(
            sphericalHarmonics, exposure, hdrParameters);
      }
      if (cubeMap != null) {
        lightProbe.setCubeMap(cubeMap);
      }
      setLightProbe(lightProbe);
    }
    if (sunlightNode != null && direction != null) {
      sunlightNode.setEnvironmentalHdrLightEstimate(
          direction, colorCorrection, relativeIntensity, exposure, hdrParameters);
    }
  }

  /**
   * Sets light estimate to modulate the scene lighting and intensity. The rendered lights will use
   * a combination of these values and the color and intensity of the lights. A value of a white
   * colorCorrection and pixelIntensity of 1 mean that no changes are made to the light settings.
   *
   * <p>This is used by AR Sceneform scenes internally to adjust lighting based on values from
   * ARCore. An AR scene will call this automatically, possibly overriding other settings. In most
   * cases, you should not need to call this explicitly.
   *
   * @param colorCorrection modulates the lighting color of the scene.
   * @param pixelIntensity modulates the lighting intensity of the scene.
   */
  public void setLightEstimate(Color colorCorrection, float pixelIntensity) {
    if (lightProbe != null) {
      lightProbe.setLightEstimate(colorCorrection, pixelIntensity);
      // TODO: The following call is not public (@hide). When public, ensure that it is
      // not possible to forget to call setLightProbe after changing the light estimate of a light
      // probe.
      setLightProbe(lightProbe);
    }
    if (sunlightNode != null) {
      sunlightNode.setLightEstimate(colorCorrection, pixelIntensity);
    }
  }

  void onTouchEvent(MotionEvent motionEvent) {
    Preconditions.checkNotNull(motionEvent, "Parameter \"motionEvent\" was null.");

    // TODO: Investigate API for controlling what node's can be hit by the hitTest.
    // i.e. layers, disabling collision shapes.
    HitTestResult hitTestResult = hitTest(motionEvent);
    touchEventSystem.onTouchEvent(hitTestResult, motionEvent);
  }

  void dispatchUpdate(FrameTime frameTime) {
    for (OnUpdateListener onUpdateListener : onUpdateListeners) {
      onUpdateListener.onUpdate(frameTime);
    }

    callOnHierarchy(node -> node.dispatchUpdate(frameTime));
  }

  @SuppressWarnings({"AndroidApiChecker", "FutureReturnValueIgnored"})
  private void setupLightProbe(SceneView view) {
    Preconditions.checkNotNull(view, "Parameter \"view\" was null.");

    int defaultLightProbeId =
        LoadHelper.rawResourceNameToIdentifier(view.getContext(), DEFAULT_LIGHTPROBE_RESOURCE_NAME);

    if (defaultLightProbeId == LoadHelper.INVALID_RESOURCE_IDENTIFIER) {
      // TODO: Better log message.
      Log.w(
          TAG,
          "Unable to find the default Light Probe."
              + " The scene will not be lit unless a light probe is set.");
      return;
    }

    try {
      LightProbe.builder()
          .setSource(view.getContext(), defaultLightProbeId)
          .setAssetName(DEFAULT_LIGHTPROBE_ASSET_NAME)
          .build()
          .thenAccept(
              result -> {
                // Set when setLightProbe is called so that we don't override the user setting.
                if (!lightProbeSet) {
                  setLightProbe(result);
                }
              })
          .exceptionally(
              throwable -> {
                Log.e(TAG, "Failed to create the default Light Probe: ", throwable);
                return null;
              });
    } catch (Exception ex) {
      throw new IllegalStateException(
          "Failed to create the default Light Probe: " + ex.getLocalizedMessage());
    }
  }
}
