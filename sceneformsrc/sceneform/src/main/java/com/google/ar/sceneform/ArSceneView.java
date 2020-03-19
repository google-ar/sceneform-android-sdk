package com.google.ar.sceneform;

import android.content.Context;
import android.media.Image;

import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import com.google.ar.core.Anchor;
import com.google.ar.core.CameraConfig.FacingDirection;
import com.google.ar.core.Config;
import com.google.ar.core.Config.LightEstimationMode;
import com.google.ar.core.Frame;
import com.google.ar.core.LightEstimate;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;

import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.FatalException;

import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.CameraStream;
import com.google.ar.sceneform.rendering.Color;
import com.google.ar.sceneform.rendering.EnvironmentalHdrLightEstimate;
import com.google.ar.sceneform.rendering.GLHelper;

import com.google.ar.sceneform.rendering.PlaneRenderer;

import com.google.ar.sceneform.rendering.Renderer;
import com.google.ar.sceneform.rendering.ThreadPools;
import com.google.ar.sceneform.utilities.AndroidPreconditions;
import com.google.ar.sceneform.utilities.ArCoreVersion;
import com.google.ar.sceneform.utilities.Preconditions;
import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** A SurfaceView that integrates with ARCore and renders a scene. */
@SuppressWarnings({"AndroidApiChecker", "FutureReturnValueIgnored"})
public class ArSceneView extends SceneView {
  private static final String TAG = ArSceneView.class.getSimpleName();
  private static final String REPORTED_ENGINE_TYPE = "Sceneform";
  private static final String REPORTED_ENGINE_VERSION = "1.7";
  private static final float DEFAULT_PIXEL_INTENSITY = 1.0f;
  private static final Color DEFAULT_COLOR_CORRECTION = new Color(1, 1, 1);

  /**
   * When the camera has moved this distance, we create a new anchor to which we attach the Hdr
   * Lighting scene.
   */
  private static final float RECREATE_LIGHTING_ANCHOR_DISTANCE = 0.5f;

  private int cameraTextureId;
  @Nullable private Session session;
  @Nullable private Frame currentFrame;
  @Nullable private Config cachedConfig;
  private int minArCoreVersionCode;

  private Display display;
  private CameraStream cameraStream;
  private PlaneRenderer planeRenderer;

  private boolean lightEstimationEnabled = true;
  private boolean isLightDirectionUpdateEnabled = true;
  @Nullable private Consumer<EnvironmentalHdrLightEstimate> onNextHdrLightingEstimate = null;

  private float lastValidPixelIntensity = DEFAULT_PIXEL_INTENSITY;
  private final Color lastValidColorCorrection = new Color(DEFAULT_COLOR_CORRECTION);
  @Nullable private Anchor lastValidEnvironmentalHdrAnchor;
  @Nullable private float[] lastValidEnvironmentalHdrAmbientSphericalHarmonics;
  @Nullable private float[] lastValidEnvironmentalHdrMainLightDirection;
  @Nullable private float[] lastValidEnvironmentalHdrMainLightIntensity;

  private final float[] colorCorrectionPixelIntensity = new float[4];

  // pauseResumeTask is modified on the main thread only.  It may be completed on background
  // threads however.
  private final SequentialTask pauseResumeTask = new SequentialTask();

  /**
   * Constructs a ArSceneView object and binds it to an Android Context.
   *
   * <p>In order to have rendering work correctly, {@link #setupSession(Session)} must be called.
   *
   * @see #ArSceneView(Context, AttributeSet)
   * @param context the Android Context to use
   */
  @SuppressWarnings("initialization") // Suppress @UnderInitialization warning.
  public ArSceneView(Context context) {
    // SceneView will initialize the scene, renderer, and camera.
    super(context);
    Renderer renderer = Preconditions.checkNotNull(getRenderer());
    renderer.enablePerformanceMode();
    initializeAr();
  }

  /**
   * Constructs a ArSceneView object and binds it to an Android Context.
   *
   * <p>In order to have rendering work correctly, {@link #setupSession(Session)} must be called.
   *
   * @see #setupSession(Session)
   * @param context the Android Context to use
   * @param attrs the Android AttributeSet to associate with
   */
  @SuppressWarnings("initialization") // Suppress @UnderInitialization warning.
  public ArSceneView(Context context, AttributeSet attrs) {
    // SceneView will initialize the scene, renderer, and camera.
    super(context, attrs);
    Renderer renderer = Preconditions.checkNotNull(getRenderer());
    renderer.enablePerformanceMode();
    initializeAr();
  }

  /**
   * Setup the view with an AR Session. This method must be called once to supply the ARCore
   * session. The session is needed for any rendering to occur.
   *
   * <p>The session is expected to be configured with the update mode of LATEST_CAMERA_IMAGE.
   * Without this configuration, the updating of the ARCore session could block the UI Thread
   * causing poor UI experience.
   *
   * @see #ArSceneView(Context, AttributeSet)
   * @param session the ARCore session to use for this view
   */
  public void setupSession(Session session) {
    if (this.session != null) {
      Log.w(TAG, "The session has already been setup, cannot set it up again.");
      return;
    }
    // Enforce api level 24
    AndroidPreconditions.checkMinAndroidApiLevel();

    this.session = session;

    Renderer renderer = Preconditions.checkNotNull(getRenderer());
    int width = renderer.getDesiredWidth();
    int height = renderer.getDesiredHeight();
    if (width != 0 && height != 0) {
      session.setDisplayGeometry(display.getRotation(), width, height);
    }

    // Feature config, therefore facing direction, can only be configured once per session.
    initializeFacingDirection(session);

    // Session needs access to a texture id for updating the camera stream.
    // Filament and the Main thread each have their own gl context that share resources for this.
    session.setCameraTextureName(cameraTextureId);
  }

  
  private void initializeFacingDirection(Session session) {
    if (session.getCameraConfig().getFacingDirection() == FacingDirection.FRONT) {
      Renderer renderer = Preconditions.checkNotNull(getRenderer());
      renderer.setFrontFaceWindingInverted(true);
    }
  }

  










  










  















  













  /**
   * Resumes the rendering thread and ARCore session.
   *
   * <p>This must be called from onResume().
   *
   * @throws CameraNotAvailableException if the camera can not be opened
   */
  @Override
  public void resume() throws CameraNotAvailableException {
    resumeSession();
    resumeScene();
  }

  /**
   * Non blocking call to resume the rendering thread and ARCore session in the background
   *
   * <p>This must be called from onResume().
   *
   * <p>If called while another pause or resume is in progress, the resume will be enqueued and
   * happen after the current operation completes.
   *
   * @return A CompletableFuture completed on the main thread once the resume has completed. The
   *     future will be completed exceptionally if the resume can not be done.
   */
  public CompletableFuture<Void> resumeAsync(Executor executor) {
    final WeakReference<ArSceneView> currentSceneView = new WeakReference<>(this);
    pauseResumeTask.appendRunnable(
        () -> {
          ArSceneView arSceneView = currentSceneView.get();
          if (arSceneView == null) {
            return;
          }
          try {
            arSceneView.resumeSession();
          } catch (CameraNotAvailableException e) {
            throw new RuntimeException(e);
          }
        },
        executor);

    return pauseResumeTask.appendRunnable(
        () -> {
          ArSceneView arSceneView = currentSceneView.get();
          if (arSceneView == null) {
            return;
          }
          arSceneView.resumeScene();
        },
        ThreadPools.getMainExecutor());
  }

  /** Resumes the session without starting the scene. */
  private void resumeSession() throws CameraNotAvailableException {
    Session session = this.session;
    if (session != null) {
      reportEngineType();
      session.resume();
    }
  }

  /** Resumes the scene without starting the session */
  private void resumeScene() {
    try {
      super.resume();
    } catch (CameraNotAvailableException ex) {
      // This exception should not be possible from here
      throw new IllegalStateException(ex);
    }
  }

  /**
   * Pauses the rendering thread and ARCore session.
   *
   * <p>This must be called from onPause().
   */
  @Override
  public void pause() {
    pauseScene();
    pauseSession();
  }

  /**
   * Non blocking call to pause the rendering thread and ARCore session.
   *
   * <p>This should be called from onPause().
   *
   * <p>If pauseAsync is called while another pause or resume is in progress, the pause will be
   * enqueued and happen after the current operation completes.
   *
   * @return A {@link CompletableFuture} completed on the main thread on the pause has completed.
   *     The future Will will be completed exceptionally if the resume can not be done.
   */
  public CompletableFuture<Void> pauseAsync(Executor executor) {
    final WeakReference<ArSceneView> currentSceneView = new WeakReference<>(this);
    pauseResumeTask.appendRunnable(
        () -> {
          ArSceneView arSceneView = currentSceneView.get();
          if (arSceneView == null) {
            return;
          }
          arSceneView.pauseScene();
        },
        ThreadPools.getMainExecutor());

    return pauseResumeTask
        .appendRunnable(
            () -> {
              ArSceneView arSceneView = currentSceneView.get();
              if (arSceneView == null) {
                return;
              }
              arSceneView.pauseSession();
            },
            executor)
        .thenAcceptAsync(
            // Ensure the final completed future is on the main thread.
            notUsed -> {},
            ThreadPools.getMainExecutor());
  }

  /** Pause the session without touching the scene */
  private void pauseSession() {
    if (session != null) {
      session.pause();
    }
  }

  /** Pause the scene without touching the session */
  private void pauseScene() {
    super.pause();
  }

  /** @hide */
  @Override
  public void onLayout(boolean changed, int left, int top, int right, int bottom) {
    super.onLayout(changed, left, top, right, bottom);

    if (session != null) {
      int width = right - left;
      int height = bottom - top;
      session.setDisplayGeometry(display.getRotation(), width, height);
    }
  }

  /**
   * Enable Light Estimation based on the camera feed. The color and intensity of the sun's indirect
   * light will be modulated by values provided by ARCore's light estimation. Lit objects in the
   * scene will be affected.
   *
   * @param enable set to true to enable Light Estimation or false to use the default estimate,
   *     which is a pixel intensity of 1.0 and color correction value of white (1.0, 1.0, 1.0).
   */
  public void setLightEstimationEnabled(boolean enable) {
    lightEstimationEnabled = enable;
    if (!lightEstimationEnabled) {
      // Update the light probe with the current best light estimate.
      getScene().setLightEstimate(DEFAULT_COLOR_CORRECTION, DEFAULT_PIXEL_INTENSITY);
      lastValidPixelIntensity = DEFAULT_PIXEL_INTENSITY;
      lastValidColorCorrection.set(DEFAULT_COLOR_CORRECTION);
    }
  }

  /** @return returns true if light estimation is enabled. */
  public boolean isLightEstimationEnabled() {
    return lightEstimationEnabled;
  }

  /** Returns the ARCore Session used by this view. */
  @Nullable
  public Session getSession() {
    return session;
  }

  /**
   * Returns the most recent ARCore Frame if it is available. The frame is updated at the beginning
   * of each drawing frame. Callers of this method should not retain a reference to the return
   * value, since it will be invalid to use the ARCore frame starting with the next frame.
   */
  @Nullable
  @UiThread
  public Frame getArFrame() {
    return currentFrame;
  }

  /** Returns PlaneRenderer, used to control plane visualization. */
  public PlaneRenderer getPlaneRenderer() {
    return planeRenderer;
  }

  /**
   * Before the render call occurs, update the ARCore session to grab the latest frame and update
   * listeners.
   *
   * @return true if the session updated successfully and a new frame was obtained. Update the scene
   *     before rendering.
   * @hide
   */
  @SuppressWarnings("AndroidApiChecker")
  @Override
  protected boolean onBeginFrame(long frameTimeNanos) {
    // No session, no drawing.
    Session session = this.session;
    if (session == null) {
      return false;
    }

    if (!pauseResumeTask.isDone()) {
      return false;
    }

    ensureUpdateMode();

    // Before doing anything update the Frame from ARCore.
    boolean updated = true;
    try {
      Frame frame = session.update();
      // No frame, no drawing.
      if (frame == null) {
        return false;
      }

      // Setup Camera Stream if needed.
      if (!cameraStream.isTextureInitialized()) {
        cameraStream.initializeTexture(frame);
      }

      // Recalculate camera Uvs if necessary.
      if (shouldRecalculateCameraUvs(frame)) {
        cameraStream.recalculateCameraUvs(frame);
      }

      if (currentFrame != null && currentFrame.getTimestamp() == frame.getTimestamp()) {
        updated = false;
      }

      currentFrame = frame;
    } catch (CameraNotAvailableException e) {
      Log.w(TAG, "Exception updating ARCore session", e);
      return false;
    }

    // No camera, no drawing.
    com.google.ar.core.Camera currentArCamera = currentFrame.getCamera();
    if (currentArCamera == null) {
      getScene().setUseHdrLightEstimate(false);
      return false;
    }

    // If ARCore session has changed, update listeners.
    if (updated) {
      // At the start of the frame, update the tracked pose of the camera
      // to use in any calculations during the frame.
      getScene().getCamera().updateTrackedPose(currentArCamera);

      Frame frame = currentFrame;
      if (frame != null) {
        // Update the light estimate.
        updateLightEstimate(frame);
        // Update the plane renderer.
        planeRenderer.update(frame, getWidth(), getHeight());
      }
    }

    return updated;
  }

  private boolean shouldRecalculateCameraUvs(Frame frame) {
    return frame.hasDisplayGeometryChanged();
  }

  /** Get the AR light estimate from the frame and then update the scene. */
  private void updateLightEstimate(Frame frame) {
    // Just return if Light Estimation is disabled.
    if (!lightEstimationEnabled || getSession() == null) {
      return;
    }

    // Update the Light Probe with the new light estimate.
    LightEstimate estimate = frame.getLightEstimate();

    if (isEnvironmentalHdrLightingAvailable()) {
      if (frame.getCamera().getTrackingState() == TrackingState.TRACKING) {
        updateHdrLightEstimate(
            estimate, Preconditions.checkNotNull(getSession()), frame.getCamera());
      }
    } else {
      updateNormalLightEstimate(estimate);
    }
  }

  /**
   * Checks whether the sunlight is being updated every frame based on the Environmental HDR
   * lighting estimate.
   *
   * @return true if the sunlight direction is updated every frame, false otherwise.
   */
  
  public boolean isLightDirectionUpdateEnabled() {
    return isLightDirectionUpdateEnabled;
  }

  /**
   * Sets whether the sunlight direction generated from Environmental HDR lighting should be updated
   * every frame. If false the light direction will be updated a single time and then no longer
   * change.
   *
   * <p>This may be used to turn off shadow direction updates when they are distracting or unwanted.
   *
   * <p>The default state is true, with sunlight direction updated every frame.
   */
  
  public void setLightDirectionUpdateEnabled(boolean isLightDirectionUpdateEnabled) {
    this.isLightDirectionUpdateEnabled = isLightDirectionUpdateEnabled;
  }

  /**
   * Returns true if the ARCore camera is configured with
   * Config.LightEstimationMode.ENVIRONMENTAL_HDR. When Environmental HDR lighting mode is enabled,
   * the resulting light estimates will be applied to the Sceneform Scene.
   *
   * @return true if HDR lighting is enabled in Sceneform because ARCore HDR lighting estimation is
   *     enabled.
   */
  
  public boolean isEnvironmentalHdrLightingAvailable() {
    if (cachedConfig == null) {
      return false;
    }
    return (cachedConfig.getLightEstimationMode() == LightEstimationMode.ENVIRONMENTAL_HDR);
  }

  /**
   * Causes a serialized version of the next captured light estimate to be saved to disk.
   *
   * @hide
   */
  
  public void captureLightingValues(
      Consumer<EnvironmentalHdrLightEstimate> onNextHdrLightingEstimate) {
    this.onNextHdrLightingEstimate = onNextHdrLightingEstimate;
  }

  
  void updateHdrLightEstimate(
      LightEstimate estimate, Session session, com.google.ar.core.Camera camera) {
    if (estimate.getState() != LightEstimate.State.VALID) {
      return;
    }
    getScene().setUseHdrLightEstimate(true);

    // Updating the direction shouldn't be skipped if it hasn't ever been acquired yet.
    if (isLightDirectionUpdateEnabled || lastValidEnvironmentalHdrMainLightDirection == null) {
      boolean needsNewAnchor = false;

      // If the current anchor for the hdr light direction is not tracking, or we have moved too far
      // then we need a new anchor on which to base our light direction.
      if (lastValidEnvironmentalHdrAnchor == null
          || lastValidEnvironmentalHdrAnchor.getTrackingState() != TrackingState.TRACKING) {
        needsNewAnchor = true;
      } else {
        Pose cameraPose = camera.getPose();
        Vector3 cameraPosition = new Vector3(cameraPose.tx(), cameraPose.ty(), cameraPose.tz());
        Pose anchorPose = Preconditions.checkNotNull(lastValidEnvironmentalHdrAnchor).getPose();
        Vector3 anchorPosition = new Vector3(anchorPose.tx(), anchorPose.ty(), anchorPose.tz());
        needsNewAnchor =
            Vector3.subtract(cameraPosition, anchorPosition).length()
                > RECREATE_LIGHTING_ANCHOR_DISTANCE;
      }

      // If we need a new anchor we destroy the current anchor and try to create a new one. If the
      // ARCore session is tracking this will succeed, and if not we will stop updating the
      // deeplight estimate until we begin tracking again.
      if (needsNewAnchor) {
        if (lastValidEnvironmentalHdrAnchor != null) {
          lastValidEnvironmentalHdrAnchor.detach();
          lastValidEnvironmentalHdrAnchor = null;
        }
        lastValidEnvironmentalHdrMainLightDirection = null;
        if (camera.getTrackingState() == TrackingState.TRACKING) {
          try {
            lastValidEnvironmentalHdrAnchor = session.createAnchor(camera.getPose());
          } catch (FatalException e) {
            // Hopefully this exception is not truly fatal.
            Log.e(TAG, "Error trying to create environmental hdr anchor", e);
          }
        }
      }

      // If we have a valid anchor, we update the anchor-relative local direction based on the
      // current light estimate.
      if (lastValidEnvironmentalHdrAnchor != null) {
        float[] mainLightDirection = estimate.getEnvironmentalHdrMainLightDirection();
        if (mainLightDirection != null) {
          Pose anchorPose = Preconditions.checkNotNull(lastValidEnvironmentalHdrAnchor).getPose();
          lastValidEnvironmentalHdrMainLightDirection =
              anchorPose.inverse().rotateVector(mainLightDirection);
        }
      }
    }

    float[] sphericalHarmonics = estimate.getEnvironmentalHdrAmbientSphericalHarmonics();
    if (sphericalHarmonics != null) {
      lastValidEnvironmentalHdrAmbientSphericalHarmonics = sphericalHarmonics;
    }

    float[] mainLightIntensity = estimate.getEnvironmentalHdrMainLightIntensity();
    if (mainLightIntensity != null) {
      lastValidEnvironmentalHdrMainLightIntensity = mainLightIntensity;
    }

    if (lastValidEnvironmentalHdrAnchor == null
        || lastValidEnvironmentalHdrMainLightIntensity == null
        || lastValidEnvironmentalHdrAmbientSphericalHarmonics == null
        || lastValidEnvironmentalHdrMainLightDirection == null) {
      return;
    }

    float mainLightIntensityScalar =
        Math.max(
            1.0f,
            Math.max(
                Math.max(
                    lastValidEnvironmentalHdrMainLightIntensity[0],
                    lastValidEnvironmentalHdrMainLightIntensity[1]),
                lastValidEnvironmentalHdrMainLightIntensity[2]));

    final Color mainLightColor =
        new Color(
            lastValidEnvironmentalHdrMainLightIntensity[0] / mainLightIntensityScalar,
            lastValidEnvironmentalHdrMainLightIntensity[1] / mainLightIntensityScalar,
            lastValidEnvironmentalHdrMainLightIntensity[2] / mainLightIntensityScalar);

    Image[] cubeMap = estimate.acquireEnvironmentalHdrCubeMap();

    // We calculate the world-space direction relative to the current position of the tracked
    // anchor.
    Pose anchorPose = Preconditions.checkNotNull(lastValidEnvironmentalHdrAnchor).getPose();
    float[] currentLightDirection =
        anchorPose.rotateVector(
            Preconditions.checkNotNull(lastValidEnvironmentalHdrMainLightDirection));

    if (onNextHdrLightingEstimate != null) {
      EnvironmentalHdrLightEstimate lightEstimate =
          new EnvironmentalHdrLightEstimate(
              lastValidEnvironmentalHdrAmbientSphericalHarmonics,
              currentLightDirection,
              mainLightColor,
              mainLightIntensityScalar,
              cubeMap);
      onNextHdrLightingEstimate.accept(lightEstimate);
      onNextHdrLightingEstimate = null;
    }

    getScene()
        .setEnvironmentalHdrLightEstimate(
            lastValidEnvironmentalHdrAmbientSphericalHarmonics,
            currentLightDirection,
            mainLightColor,
            mainLightIntensityScalar,
            cubeMap);
    for (Image cubeMapImage : cubeMap) {
      cubeMapImage.close();
    }
  }

  private void updateNormalLightEstimate(LightEstimate estimate) {
    getScene().setUseHdrLightEstimate(false);
    // Verify that the estimate is valid
    float pixelIntensity = lastValidPixelIntensity;
    // Only update the estimate if it is valid.
    if (estimate.getState() == LightEstimate.State.VALID) {
      estimate.getColorCorrection(colorCorrectionPixelIntensity, 0);
      pixelIntensity = Math.max(colorCorrectionPixelIntensity[3], 0.0f);
      lastValidColorCorrection.set(
          colorCorrectionPixelIntensity[0],
          colorCorrectionPixelIntensity[1],
          colorCorrectionPixelIntensity[2]);
    }
    // Update the light probe with the current best light estimate.
    getScene().setLightEstimate(lastValidColorCorrection, pixelIntensity);
    // Update the last valid estimate.
    lastValidPixelIntensity = pixelIntensity;
  }

  private void initializeAr() {
    minArCoreVersionCode = ArCoreVersion.getMinArCoreVersionCode(getContext());
    display = getContext().getSystemService(WindowManager.class).getDefaultDisplay();

    initializePlaneRenderer();
    initializeCameraStream();
  }

  private void initializePlaneRenderer() {
    Renderer renderer = Preconditions.checkNotNull(getRenderer());
    planeRenderer = new PlaneRenderer(renderer);
  }

  private void initializeCameraStream() {
    cameraTextureId = GLHelper.createCameraTexture();
    Renderer renderer = Preconditions.checkNotNull(getRenderer());
    cameraStream = new CameraStream(cameraTextureId, renderer);
  }

  private void ensureUpdateMode() {
    if (session == null) {
      return;
    }

    // Check the update mode.
    if (minArCoreVersionCode >= ArCoreVersion.VERSION_CODE_1_3) {
      if (cachedConfig == null) {
        cachedConfig = session.getConfig();
      } else {
        session.getConfig(cachedConfig);
      }

      Config.UpdateMode updateMode = cachedConfig.getUpdateMode();
      if (updateMode != Config.UpdateMode.LATEST_CAMERA_IMAGE) {
        throw new RuntimeException(
            "Invalid ARCore UpdateMode "
                + updateMode
                + ", Sceneform requires that the ARCore session is configured to the "
                + "UpdateMode LATEST_CAMERA_IMAGE.");
      }
    }
  }

  


  private static boolean loadUnifiedJni() {return false;}



  
  private void reportEngineType() {return ;}
















  private static native void nativeReportEngineType(
      Session session, String engineType, String engineVersion);
}
