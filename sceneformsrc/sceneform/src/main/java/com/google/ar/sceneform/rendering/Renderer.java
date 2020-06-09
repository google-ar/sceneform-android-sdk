package com.google.ar.sceneform.rendering;

import android.content.Context;
import android.os.Build;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.annotation.VisibleForTesting;
import android.view.Surface;
import android.view.SurfaceView;
import com.google.android.filament.Camera;
import com.google.android.filament.Entity;
import com.google.android.filament.IndirectLight;
import com.google.android.filament.Scene;
import com.google.android.filament.SwapChain;
import com.google.android.filament.TransformManager;
import com.google.android.filament.View.DynamicResolutionOptions;
import com.google.android.filament.Viewport;
import com.google.android.filament.android.UiHelper;

import com.google.ar.sceneform.utilities.AndroidPreconditions;
import com.google.ar.sceneform.utilities.EnvironmentalHdrParameters;
import com.google.ar.sceneform.utilities.Preconditions;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A rendering context.
 *
 * <p>Contains everything that will be drawn on a surface.
 *
 * @hide Not a public facing API for version 1.0
 */
public class Renderer implements UiHelper.RendererCallback {
  // Default camera settings are used everwhere that ARCore HDR Lighting (Deeplight) is disabled or
  // unavailable.
  private static final float DEFAULT_CAMERA_APERATURE = 4.0f;
  private static final float DEFAULT_CAMERA_SHUTTER_SPEED = 1.0f / 30.0f;
  private static final float DEFAULT_CAMERA_ISO = 320.0f;

  // HDR lighting camera settings are chosen to provide an exposure value of 1.0.  These are used
  // when ARCore HDR Lighting is enabled in Sceneform.
  private static final float ARCORE_HDR_LIGHTING_CAMERA_APERATURE = 1.0f;
  private static final float ARCORE_HDR_LIGHTING_CAMERA_SHUTTER_SPEED = 1.2f;
  private static final float ARCORE_HDR_LIGHTING_CAMERA_ISO = 100.0f;

  private static final Color DEFAULT_CLEAR_COLOR = new Color(0.0f, 0.0f, 0.0f, 1.0f);

  // Limit resolution to 1080p for the minor edge. This is enough for Filament.
  private static final int MAXIMUM_RESOLUTION = 1080;

  @Nullable private CameraProvider cameraProvider;
  private final SurfaceView surfaceView;
  private final ViewAttachmentManager viewAttachmentManager;

  private final ArrayList<RenderableInstance> renderableInstances = new ArrayList<>();
  private final ArrayList<LightInstance> lightInstances = new ArrayList<>();

  private Surface surface;
  @Nullable private SwapChain swapChain;
  private com.google.android.filament.View view;
  private com.google.android.filament.View emptyView;
  private com.google.android.filament.Renderer renderer;
  private Camera camera;
  private Scene scene;
  private IndirectLight indirectLight;
  private boolean recreateSwapChain;

  private float cameraAperature;
  private float cameraShutterSpeed;
  private float cameraIso;

  private UiHelper filamentHelper;

  private final double[] cameraProjectionMatrix = new double[16];

  private EnvironmentalHdrParameters environmentalHdrParameters =
      EnvironmentalHdrParameters.makeDefault();

  private static class Mirror {
    @Nullable SwapChain swapChain;
    @Nullable Surface surface;
    Viewport viewport;
  }

  private final List<Mirror> mirrors = new ArrayList<>();

  /** @hide */
  public interface PreRenderCallback {
    void preRender(
        com.google.android.filament.Renderer renderer,
        com.google.android.filament.SwapChain swapChain,
        com.google.android.filament.Camera camera);
  }

  @Nullable private Runnable onFrameRenderDebugCallback = null;
  @Nullable private PreRenderCallback preRenderCallback;

  /** @hide */
  @SuppressWarnings("initialization") // Suppress @UnderInitialization warning.
  @RequiresApi(api = Build.VERSION_CODES.N)
  public Renderer(SurfaceView view) {
    Preconditions.checkNotNull(view, "Parameter \"view\" was null.");
    // Enforce api level 24
    AndroidPreconditions.checkMinAndroidApiLevel();

    this.surfaceView = view;
    viewAttachmentManager = new ViewAttachmentManager(getContext(), view);
    initialize();
  }

  /**
   * Starts mirroring to the specified {@link Surface}.
   *
   * @hide
   */
  public void startMirroring(Surface surface, int left, int bottom, int width, int height) {
    Mirror mirror = new Mirror();
    mirror.surface = surface;
    mirror.viewport = new Viewport(left, bottom, width, height);
    mirror.swapChain = null;
    synchronized (mirrors) {
      mirrors.add(mirror);
    }
  }

  /**
   * Stops mirroring to the specified {@link Surface}.
   *
   * @hide
   */
  public void stopMirroring(Surface surface) {
    synchronized (mirrors) {
      for (Mirror mirror : mirrors) {
        if (mirror.surface == surface) {
          mirror.surface = null;
        }
      }
    }
  }

  /**
   * Access to the underlying Filament renderer.
   *
   * @hide
   */
  public com.google.android.filament.Renderer getFilamentRenderer() {
    return renderer;
  }

  public SurfaceView getSurfaceView() {
    return surfaceView;
  }

  /** @hide */
  public void setClearColor(Color color) {
    com.google.android.filament.Renderer.ClearOptions options = new com.google.android.filament.Renderer.ClearOptions();
    options.clearColor[0] = color.r;
    options.clearColor[1] = color.g;
    options.clearColor[2] = color.b;
    options.clearColor[3] = color.a;
    renderer.setClearOptions(options);
  }

  /** @hide */
  public void setDefaultClearColor() {
    setClearColor(DEFAULT_CLEAR_COLOR);
  }

  /**
   * Inverts winding for front face rendering.
   *
   * @hide Used internally by ArSceneView
   */
  
  public void setFrontFaceWindingInverted(Boolean inverted) {
    view.setFrontFaceWindingInverted(inverted);
  }

  /**
   * Checks whether winding is inverted for front face rendering.
   *
   * @hide Used internally by ViewRenderable
   */
  
  public boolean isFrontFaceWindingInverted() {
    return view.isFrontFaceWindingInverted();
  }

  /** @hide */
  public void setCameraProvider(@Nullable CameraProvider cameraProvider) {
    this.cameraProvider = cameraProvider;
  }

  /** @hide */
  public void onPause() {
    viewAttachmentManager.onPause();
  }

  /** @hide */
  public void onResume() {
    viewAttachmentManager.onResume();
  }

  /**
   * Sets a callback to happen after each frame is rendered. This can be used to log performance
   * metrics for a given frame.
   */
  public void setFrameRenderDebugCallback(Runnable onFrameRenderDebugCallback) {
    this.onFrameRenderDebugCallback = onFrameRenderDebugCallback;
  }

  private Viewport getLetterboxViewport(Viewport srcViewport, Viewport destViewport) {
    boolean letterBoxSides =
        (destViewport.width / (float) destViewport.height)
            > (srcViewport.width / (float) srcViewport.height);
    float scale =
        letterBoxSides
            ? (destViewport.height / (float) srcViewport.height)
            : (destViewport.width / (float) srcViewport.width);
    int width = (int) (srcViewport.width * scale);
    int height = (int) (srcViewport.height * scale);
    int left = (destViewport.width - width) / 2;
    int bottom = (destViewport.height - height) / 2;
    return new Viewport(left, bottom, width, height);
  }

  /** @hide */
  public void setPreRenderCallback(@Nullable PreRenderCallback preRenderCallback) {
    this.preRenderCallback = preRenderCallback;
  }

  /** @hide */
  public void render(boolean debugEnabled) {
    synchronized (this) {
      if (recreateSwapChain) {
        final IEngine engine = EngineInstance.getEngine();
        if (swapChain != null) {
          engine.destroySwapChain(swapChain);
        }
        swapChain = engine.createSwapChain(surface, SwapChain.CONFIG_READABLE);
        recreateSwapChain = false;
      }
    }
    synchronized (mirrors) {
      Iterator<Mirror> mirrorIterator = mirrors.iterator();
      while (mirrorIterator.hasNext()) {
        Mirror mirror = mirrorIterator.next();
        if (mirror.surface == null) {
          if (mirror.swapChain != null) {
            final IEngine engine = EngineInstance.getEngine();
            engine.destroySwapChain(Preconditions.checkNotNull(mirror.swapChain));
          }
          mirrorIterator.remove();
        } else if (mirror.swapChain == null) {
          final IEngine engine = EngineInstance.getEngine();
          mirror.swapChain = engine.createSwapChain(Preconditions.checkNotNull(mirror.surface));
        }
      }
    }

    if (filamentHelper.isReadyToRender() || EngineInstance.isHeadlessMode()) {
      updateInstances();
      updateLights();

      CameraProvider cameraProvider = this.cameraProvider;
      if (cameraProvider != null) {
        final float[] projectionMatrixData = cameraProvider.getProjectionMatrix().data;
        for (int i = 0; i < 16; ++i) {
          cameraProjectionMatrix[i] = projectionMatrixData[i];
        }

        camera.setModelMatrix(cameraProvider.getWorldModelMatrix().data);
        camera.setCustomProjection(
            cameraProjectionMatrix,
            cameraProvider.getNearClipPlane(),
            cameraProvider.getFarClipPlane());
        @Nullable SwapChain swapChainLocal = swapChain;
        if (swapChainLocal == null) {
          throw new AssertionError("Internal Error: Failed to get swap chain");
        }

        if (renderer.beginFrame(swapChainLocal, 0)) {
          if (preRenderCallback != null) {
            preRenderCallback.preRender(renderer, swapChainLocal, camera);
          }

          // Currently, filament does not provide functionality for disabling cameras, and
          // rendering a view with a null camera doesn't clear the viewport. As a workaround, we
          // render an empty view when the camera is disabled. this is actually similar to what we
          // need to do in the future if we want to add multiple camera support anyways. filament
          // only allows one camera per-view, so for multiple cameras you need to create multiple
          // views pointing to the same scene.
          com.google.android.filament.View currentView =
              cameraProvider.isActive() ? view : emptyView;
          renderer.render(currentView);

          synchronized (mirrors) {
            for (Mirror mirror : mirrors) {
              if (mirror.swapChain != null) {
                renderer.mirrorFrame(
                    mirror.swapChain,
                    getLetterboxViewport(currentView.getViewport(), mirror.viewport),
                    currentView.getViewport(),
                    com.google.android.filament.Renderer.MIRROR_FRAME_FLAG_COMMIT
                        | com.google.android.filament.Renderer
                            .MIRROR_FRAME_FLAG_SET_PRESENTATION_TIME
                        | com.google.android.filament.Renderer.MIRROR_FRAME_FLAG_CLEAR);
              }
            }
          }
          if (onFrameRenderDebugCallback != null) {
            onFrameRenderDebugCallback.run();
          }
          renderer.endFrame();
        }

        reclaimReleasedResources();
      }
    }
  }

  /** @hide */
  public void dispose() {
    filamentHelper.detach(); // call this before destroying the Engine (it could call back)

    final IEngine engine = EngineInstance.getEngine();
    if (indirectLight != null) {
      engine.destroyIndirectLight(indirectLight);
    }
    engine.destroyRenderer(renderer);
    engine.destroyView(view);
    reclaimReleasedResources();
  }

  public Context getContext() {
    return getSurfaceView().getContext();
  }

  /**
   * Set the Light Probe used for reflections and indirect light.
   *
   * @hide the scene level API is publicly exposed, this is used by the Scene internally.
   */
  public void setLightProbe(LightProbe lightProbe) {
    if (lightProbe == null) {
      throw new AssertionError("Passed in an invalid light probe.");
    }
    final IndirectLight latestIndirectLight = lightProbe.buildIndirectLight();
    if (latestIndirectLight != null) {
      scene.setIndirectLight(latestIndirectLight);
      if (indirectLight != null && indirectLight != latestIndirectLight) {
        final IEngine engine = EngineInstance.getEngine();
        engine.destroyIndirectLight(indirectLight);
      }
      indirectLight = latestIndirectLight;
    }
  }

  /** @hide */
  public void setDesiredSize(int width, int height) {
    int minor = Math.min(width, height);
    int major = Math.max(width, height);
    if (minor > MAXIMUM_RESOLUTION) {
      major = (major * MAXIMUM_RESOLUTION) / minor;
      minor = MAXIMUM_RESOLUTION;
    }
    if (width < height) {
      int t = minor;
      minor = major;
      major = t;
    }

    filamentHelper.setDesiredSize(major, minor);
  }

  /** @hide */
  public int getDesiredWidth() {
    return filamentHelper.getDesiredWidth();
  }

  /** @hide */
  public int getDesiredHeight() {
    return filamentHelper.getDesiredHeight();
  }

  /** @hide UiHelper.RendererCallback implementation */
  @Override
  public void onNativeWindowChanged(Surface surface) {
    synchronized (this) {
      this.surface = surface;
      recreateSwapChain = true;
    }
  }

  /** @hide UiHelper.RendererCallback implementation */
  @Override
  public void onDetachedFromSurface() {
    @Nullable SwapChain swapChainLocal = swapChain;
    if (swapChainLocal != null) {
      final IEngine engine = EngineInstance.getEngine();
      engine.destroySwapChain(swapChainLocal);
      // Required to ensure we don't return before Filament is done executing the
      // destroySwapChain command, otherwise Android might destroy the Surface
      // too early
      engine.flushAndWait();
      swapChain = null;
    }
  }

  /** @hide Only used for scuba testing for now. */
  public void setDynamicResolutionEnabled(boolean isEnabled) {
    // Enable dynamic resolution. By default it will scale down to 25% of the screen area
    // (i.e.: 50% on each axis, e.g.: reducing a 1080p image down to 720p).
    // This can be changed in the options below.
    // TODO: This functionality should probably be exposed to the developer eventually.
    DynamicResolutionOptions options = new DynamicResolutionOptions();
    options.enabled = isEnabled;
    view.setDynamicResolutionOptions(options);
  }

  /** @hide Only used for scuba testing for now. */
  @VisibleForTesting
  public void setAntiAliasing(com.google.android.filament.View.AntiAliasing antiAliasing) {
    view.setAntiAliasing(antiAliasing);
  }

  /** @hide Only used for scuba testing for now. */
  @VisibleForTesting
  public void setDithering(com.google.android.filament.View.Dithering dithering) {
    view.setDithering(dithering);
  }

  /** @hide Used internally by ArSceneView. */
  
  public void setPostProcessingEnabled(boolean enablePostProcessing) {return ;}



  /** @hide Used internally by ArSceneView */
  
  public void setRenderQuality(com.google.android.filament.View.RenderQuality renderQuality) {return ;}



  /**
   * Sets a high performance configuration for the filament view. Disables MSAA, disables
   * post-process, disables dynamic resolution, sets quality to 'low'.
   *
   * @hide Used internally by ArSceneView
   */
  
  public void enablePerformanceMode() {return ;}









  /**
   * Getter to help convert between filament and Environmental HDR.
   *
   * @hide This may be removed in the future
   */
  public EnvironmentalHdrParameters getEnvironmentalHdrParameters() {
    return environmentalHdrParameters;
  }

  /**
   * Setter to help convert between filament and Environmental HDR.
   *
   * @hide This may be removed in the future
   */
  public void setEnvironmentalHdrParameters(EnvironmentalHdrParameters environmentalHdrParameters) {
    this.environmentalHdrParameters = environmentalHdrParameters;
  }

  /** @hide UiHelper.RendererCallback implementation */
  @Override
  public void onResized(int width, int height) {
    view.setViewport(new Viewport(0, 0, width, height));
    emptyView.setViewport(new Viewport(0, 0, width, height));
  }

  /** @hide */
  void addLight(LightInstance instance) {
    @Entity int entity = instance.getEntity();
    scene.addEntity(entity);
    lightInstances.add(instance);
  }

  /** @hide */
  void removeLight(LightInstance instance) {
    @Entity int entity = instance.getEntity();
    scene.remove(entity);
    lightInstances.remove(instance);
  }

  
  private void addModelInstanceInternal(RenderableInstance instance) {return ;}





  
  private void removeModelInstanceInternal(RenderableInstance instance) {return ;}





  /** @hide */
  void addInstance(RenderableInstance instance) {
    scene.addEntity(instance.getRenderedEntity());
    addModelInstanceInternal(instance);
    renderableInstances.add(instance);
  }

  /** @hide */
  void removeInstance(RenderableInstance instance) {
    removeModelInstanceInternal(instance);
    scene.remove(instance.getRenderedEntity());
    renderableInstances.remove(instance);
  }

  Scene getFilamentScene() {
    return scene;
  }

  ViewAttachmentManager getViewAttachmentManager() {
    return viewAttachmentManager;
  }

  @SuppressWarnings("AndroidApiChecker") // CompletableFuture
  private void initialize() {
    SurfaceView surfaceView = getSurfaceView();

    filamentHelper = new UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK);
    filamentHelper.setRenderCallback(this);
    filamentHelper.attachTo(surfaceView);

    IEngine engine = EngineInstance.getEngine();

    renderer = engine.createRenderer();
    scene = engine.createScene();
    view = engine.createView();
    emptyView = engine.createView();
    camera = engine.createCamera();
    setUseHdrLightEstimate(false);

    setDefaultClearColor();
    view.setCamera(camera);
    view.setScene(scene);

    setDynamicResolutionEnabled(true);

    emptyView.setCamera(engine.createCamera());
    emptyView.setScene(engine.createScene());
  }

  public void setUseHdrLightEstimate(boolean useHdrLightEstimate) {
    if (useHdrLightEstimate) {
      cameraAperature = ARCORE_HDR_LIGHTING_CAMERA_APERATURE;
      cameraShutterSpeed = ARCORE_HDR_LIGHTING_CAMERA_SHUTTER_SPEED;
      cameraIso = ARCORE_HDR_LIGHTING_CAMERA_ISO;
    } else {
      cameraAperature = DEFAULT_CAMERA_APERATURE;
      cameraShutterSpeed = DEFAULT_CAMERA_SHUTTER_SPEED;
      cameraIso = DEFAULT_CAMERA_ISO;
    }
    // Setup the Camera Exposure values.
    camera.setExposure(cameraAperature, cameraShutterSpeed, cameraIso);
  }

  /**
   * Returns the exposure setting for renderering.
   *
   * @hide This is support deeplight API which is not stable yet.
   */
  
  public float getExposure() {
    float e = (cameraAperature * cameraAperature) / cameraShutterSpeed * 100.0f / cameraIso;
    return 1.0f / (1.2f * e);
  }

  private void updateInstances() {
    final IEngine engine = EngineInstance.getEngine();
    final TransformManager transformManager = engine.getTransformManager();
    transformManager.openLocalTransformTransaction();

    for (RenderableInstance renderableInstance : renderableInstances) {
      renderableInstance.prepareForDraw();

      float[] transform = renderableInstance.getWorldModelMatrix().data;
      renderableInstance.setModelMatrix(transformManager, transform);
    }

    transformManager.commitLocalTransformTransaction();
  }

  private void updateLights() {
    for (LightInstance lightInstance : lightInstances) {
      lightInstance.updateTransform();
    }
  }

  /**
   * Releases rendering resources ready for garbage collection
   *
   * @return Count of resources currently in use
   */
  public static long reclaimReleasedResources() {
    return ResourceManager.getInstance().reclaimReleasedResources();
  }

  /** Immediately releases all rendering resources, even if in use. */
  public static void destroyAllResources() {
    ResourceManager.getInstance().destroyAllResources();
    EngineInstance.destroyEngine();
  }
}
