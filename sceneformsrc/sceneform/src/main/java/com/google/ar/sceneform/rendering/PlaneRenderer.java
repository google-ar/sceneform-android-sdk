package com.google.ar.sceneform.rendering;

import android.support.annotation.Nullable;
import android.util.Log;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;

import com.google.ar.sceneform.math.Vector3;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Control rendering of ARCore planes.
 *
 * <p>Used to visualize detected planes and to control whether Renderables cast shadows on them.
 */
@SuppressWarnings({"AndroidApiChecker", "FutureReturnValueIgnored"}) // CompletableFuture
public class PlaneRenderer {
  private static final String TAG = PlaneRenderer.class.getSimpleName();

  /** Material parameter that controls what texture is being used when rendering the planes. */
  public static final String MATERIAL_TEXTURE = "texture";

  /**
   * Float2 material parameter to control the X/Y scaling of the texture's UV coordinates. Can be
   * used to adjust for the texture's aspect ratio and control the frequency of tiling.
   */
  public static final String MATERIAL_UV_SCALE = "uvScale";

  /** Float3 material parameter to control the RGB tint of the plane. */
  public static final String MATERIAL_COLOR = "color";

  /** Float material parameter to control the radius of the spotlight. */
  public static final String MATERIAL_SPOTLIGHT_RADIUS = "radius";

  /** Float3 material parameter to control the grid visualization point. */
  private static final String MATERIAL_SPOTLIGHT_FOCUS_POINT = "focusPoint";

  /** Used to control the UV Scale for the default texture. */
  private static final float BASE_UV_SCALE = 8.0f;

  private static final float DEFAULT_TEXTURE_WIDTH = 293;
  private static final float DEFAULT_TEXTURE_HEIGHT = 513;

  private static final float SPOTLIGHT_RADIUS = .5f;

  private final Renderer renderer;

  private final Map<Plane, PlaneVisualizer> visualizerMap = new HashMap<>();
  private CompletableFuture<Material> planeMaterialFuture;

  private Material shadowMaterial;

  private boolean isEnabled = true;
  private boolean isVisible = true;
  private boolean isShadowReceiver = true;

  // Per-plane overrides
  private final Map<Plane, Material> materialOverrides = new HashMap<>();

  // Distance from the camera to last plane hit, default value is 4 meters (standing height).
  private float lastPlaneHitDistance = 4.0f;

  /** Enable/disable the plane renderer. */
  public void setEnabled(boolean enabled) {
    if (isEnabled != enabled) {
      isEnabled = enabled;

      for (PlaneVisualizer visualizer : visualizerMap.values()) {
        visualizer.setEnabled(isEnabled);
      }
    }
  }

  /** Check if the plane renderer is enabled. */
  public boolean isEnabled() {
    return isEnabled;
  }

  /**
   * Control whether Renderables in the scene should cast shadows onto the planes.
   *
   * <p>If false - no planes receive shadows, regardless of the per-plane setting.
   */
  public void setShadowReceiver(boolean shadowReceiver) {
    if (isShadowReceiver != shadowReceiver) {
      isShadowReceiver = shadowReceiver;

      for (PlaneVisualizer visualizer : visualizerMap.values()) {
        visualizer.setShadowReceiver(isShadowReceiver);
      }
    }
  }

  /** Return true if Renderables in the scene cast shadows onto the planes. */
  public boolean isShadowReceiver() {
    return isShadowReceiver;
  }

  /**
   * Control visibility of plane visualization.
   *
   * <p>If false - no planes are drawn. Note that shadow visibility is independent of plane
   * visibility.
   */
  public void setVisible(boolean visible) {
    if (isVisible != visible) {
      isVisible = visible;

      for (PlaneVisualizer visualizer : visualizerMap.values()) {
        visualizer.setVisible(isVisible);
      }
    }
  }

  /** Return true if plane visualization is visible. */
  public boolean isVisible() {
    return isVisible;
  }

  /** Returns default material instance used to render the planes. */
  public CompletableFuture<Material> getMaterial() {
    return planeMaterialFuture;
  }

  


















  







  /** @hide PlaneRenderer is constructed in a different package, but not part of external API. */
  @SuppressWarnings("initialization")
  public PlaneRenderer(Renderer renderer) {
    this.renderer = renderer;

    loadPlaneMaterial();
    loadShadowMaterial();
  }

  /** @hide PlaneRenderer is updated in a different package, but not part of external API. */
  public void update(Frame frame, int viewWidth, int viewHeight) {
    Collection<Plane> updatedPlanes = frame.getUpdatedTrackables(Plane.class);
    Vector3 focusPoint = getFocusPoint(frame, viewWidth, viewHeight);

    @SuppressWarnings("nullness")
    @Nullable
    Material planeMaterial = planeMaterialFuture.getNow(null);
    if (planeMaterial != null) {
      planeMaterial.setFloat3(MATERIAL_SPOTLIGHT_FOCUS_POINT, focusPoint);
      planeMaterial.setFloat(MATERIAL_SPOTLIGHT_RADIUS, SPOTLIGHT_RADIUS);
    }

    for (Plane plane : updatedPlanes) {
      PlaneVisualizer planeVisualizer;

      // Find the plane visualizer if it already exists.
      // If not, create a new plane visualizer for this plane.
      if (visualizerMap.containsKey(plane)) {
        planeVisualizer = visualizerMap.get(plane);
      } else {
        planeVisualizer = new PlaneVisualizer(plane, renderer);
        Material overrideMaterial = materialOverrides.get(plane);
        if (overrideMaterial != null) {
          planeVisualizer.setPlaneMaterial(overrideMaterial);
        } else if (planeMaterial != null) {
          planeVisualizer.setPlaneMaterial(planeMaterial);
        }
        if (shadowMaterial != null) {
          planeVisualizer.setShadowMaterial(shadowMaterial);
        }
        planeVisualizer.setShadowReceiver(isShadowReceiver);
        planeVisualizer.setVisible(isVisible);
        planeVisualizer.setEnabled(isEnabled);
        visualizerMap.put(plane, planeVisualizer);
      }

      // Update the plane visualizer.
      planeVisualizer.updatePlane();
    }

    // Remove plane visualizers for old planes that are no longer tracking.
    // Update the material parameters for all remaining planes.
    Iterator<Map.Entry<Plane, PlaneVisualizer>> iter = visualizerMap.entrySet().iterator();
    while (iter.hasNext()) {
      Map.Entry<Plane, PlaneVisualizer> entry = iter.next();
      Plane plane = entry.getKey();
      PlaneVisualizer planeVisualizer = entry.getValue();

      // If this plane was subsumed by another plane or it has permanently stopped tracking,
      // remove it.
      if (plane.getSubsumedBy() != null || plane.getTrackingState() == TrackingState.STOPPED) {
        planeVisualizer.release();
        iter.remove();
        continue;
      }
    }
  }

  @SuppressWarnings({"AndroidApiChecker", "FutureReturnValueIgnored"})
  private void loadShadowMaterial() {
    Material.builder()
        .setSource(
            renderer.getContext(),
            RenderingResources.GetSceneformResource(
                renderer.getContext(), RenderingResources.Resource.PLANE_SHADOW_MATERIAL))
        .build()
        .thenAccept(
            material -> {
              shadowMaterial = material;
              for (PlaneVisualizer visualizer : visualizerMap.values()) {
                visualizer.setShadowMaterial(shadowMaterial);
              }
            })
        .exceptionally(
            throwable -> {
              Log.e(TAG, "Unable to load plane shadow material.", throwable);
              return null;
            });
  }

  @SuppressWarnings({"AndroidApiChecker", "FutureReturnValueIgnored"})
  private void loadPlaneMaterial() {
    Texture.Sampler sampler =
        Texture.Sampler.builder()
            .setMinMagFilter(Texture.Sampler.MagFilter.LINEAR)
            .setWrapMode(Texture.Sampler.WrapMode.REPEAT)
            .build();

    CompletableFuture<Texture> textureFuture =
        Texture.builder()
            .setSource(
                renderer.getContext(),
                RenderingResources.GetSceneformResource(
                    renderer.getContext(), RenderingResources.Resource.PLANE))
            .setSampler(sampler)
            .build();

    planeMaterialFuture =
        Material.builder()
            .setSource(
                renderer.getContext(),
                RenderingResources.GetSceneformResource(
                    renderer.getContext(), RenderingResources.Resource.PLANE_MATERIAL))
            .build()
            .thenCombine(
                textureFuture,
                (material, texture) -> {
                  material.setTexture(MATERIAL_TEXTURE, texture);
                  material.setFloat3(MATERIAL_COLOR, 1.0f, 1.0f, 1.0f);

                  // TODO: Don't use hardcoded width and height... Need api for getting
                  // width and
                  // height from the Texture class.
                  float widthToHeightRatio = DEFAULT_TEXTURE_WIDTH / DEFAULT_TEXTURE_HEIGHT;
                  float scaleX = BASE_UV_SCALE;
                  float scaleY = scaleX * widthToHeightRatio;
                  material.setFloat2(MATERIAL_UV_SCALE, scaleX, scaleY);

                  for (Map.Entry<Plane, PlaneVisualizer> entry : visualizerMap.entrySet()) {
                    if (!materialOverrides.containsKey(entry.getKey())) {
                      entry.getValue().setPlaneMaterial(material);
                    }
                  }
                  return material;
                });
  }

  private Vector3 getFocusPoint(Frame frame, int width, int height) {
    Vector3 focusPoint;

    // If we hit a plane, return the hit point.
    List<HitResult> hits = frame.hitTest(width / 2, height / 2);
    if (hits != null && !hits.isEmpty()) {
      for (HitResult hit : hits) {
        Trackable trackable = hit.getTrackable();
        Pose hitPose = hit.getHitPose();
        if (trackable instanceof Plane && ((Plane) trackable).isPoseInPolygon(hitPose)) {
          focusPoint = new Vector3(hitPose.tx(), hitPose.ty(), hitPose.tz());
          lastPlaneHitDistance = hit.getDistance();
          return focusPoint;
        }
      }
    }

    // If we didn't hit anything, project a point in front of the camera so that the spotlight
    // rolls off the edge smoothly.
    Pose cameraPose = frame.getCamera().getPose();
    Vector3 cameraPosition = new Vector3(cameraPose.tx(), cameraPose.ty(), cameraPose.tz());
    float[] zAxis = cameraPose.getZAxis();
    Vector3 backwards = new Vector3(zAxis[0], zAxis[1], zAxis[2]);

    focusPoint = Vector3.add(cameraPosition, backwards.scaled(-lastPlaneHitDistance));

    return focusPoint;
  }
}
