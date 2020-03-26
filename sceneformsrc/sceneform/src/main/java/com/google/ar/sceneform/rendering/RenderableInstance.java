package com.google.ar.sceneform.rendering;

import android.net.Uri;
import android.support.annotation.Nullable;
import android.support.annotation.Size;
import android.util.Log;
import com.google.android.filament.Engine;
import com.google.android.filament.Entity;
import com.google.android.filament.EntityInstance;
import com.google.android.filament.EntityManager;

import com.google.android.filament.RenderableManager;
import com.google.android.filament.TransformManager;
import com.google.android.filament.gltfio.AssetLoader;
import com.google.android.filament.gltfio.FilamentAsset;
import com.google.android.filament.gltfio.ResourceLoader;


import com.google.ar.sceneform.collision.Box;
import com.google.ar.sceneform.common.TransformProvider;
import com.google.ar.sceneform.math.Matrix;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.utilities.AndroidPreconditions;
import com.google.ar.sceneform.utilities.ChangeId;
import com.google.ar.sceneform.utilities.LoadHelper;
import com.google.ar.sceneform.utilities.Preconditions;
import com.google.ar.sceneform.utilities.SceneformBufferUtils;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import java.nio.IntBuffer;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * Controls how a {@link Renderable} is displayed. There can be multiple RenderableInstances
 * displaying a single Renderable.
 *
 * @hide
 */
@SuppressWarnings("AndroidJdkLibsChecker")
public class RenderableInstance {

  /**
   * Interface for modifying the bone transforms for this specific RenderableInstance. Used by
   * {@link com.google.ar.sceneform.SkeletonNode} to make it possible to control a bone by moving a
   * node.
   */
  public interface SkinningModifier {

    /**
     * Takes the original boneTransforms and output new boneTransforms used to render the mesh.
     *
     * @param originalBuffer contains the bone transforms from the current animation state of the
     *     skeleton, buffer is read only
     */
    FloatBuffer modifyMaterialBoneTransformsBuffer(FloatBuffer originalBuffer);

    boolean isModifiedSinceLastRender();
  }

  private static final String TAG = RenderableInstance.class.getSimpleName();

  private final TransformProvider transformProvider;
  private final Renderable renderable;
  @Nullable private Renderer attachedRenderer;
  @Entity private int entity = 0;
  @Entity private int childEntity = 0;
  int renderableId = ChangeId.EMPTY_ID;

  



  
  @Nullable
  FilamentAsset filamentAsset;

  @Nullable private SkinningModifier skinningModifier;

  @Nullable private Matrix cachedRelativeTransform;
  @Nullable private Matrix cachedRelativeTransformInverse;

  @SuppressWarnings("initialization") // Suppress @UnderInitialization warning.
  public RenderableInstance(TransformProvider transformProvider, Renderable renderable) {
    Preconditions.checkNotNull(transformProvider, "Parameter \"transformProvider\" was null.");
    Preconditions.checkNotNull(renderable, "Parameter \"renderable\" was null.");
    this.transformProvider = transformProvider;
    this.renderable = renderable;
    entity = createFilamentEntity(EngineInstance.getEngine());

    // SFB's can be imported with re-centering or scaling; rather than perform those operations to
    // the vertices (and bones, &c) at import time, we keep vertex data in the same unit as the
    // source asset and apply at runtime to a child entity via this relative transform.  If we get
    // back null, the relative transform is identity and the child entity path can be skipped.
    @Nullable Matrix relativeTransform = getRelativeTransform();
    if (relativeTransform != null) {
      childEntity =
          createFilamentChildEntity(EngineInstance.getEngine(), entity, relativeTransform);
    }

    createGltfModelInstance();

    createFilamentAssetModelInstance();

    ResourceManager.getInstance()
        .getRenderableInstanceCleanupRegistry()
        .register(this, new CleanupCallback(entity, childEntity));
  }

  
  void createFilamentAssetModelInstance() {
    if (renderable.getRenderableData() instanceof RenderableInternalFilamentAssetData) {
      RenderableInternalFilamentAssetData renderableData =
          (RenderableInternalFilamentAssetData) renderable.getRenderableData();

      Engine engine = EngineInstance.getEngine().getFilamentEngine();

      AssetLoader loader =
          new AssetLoader(
              engine,
              RenderableInternalFilamentAssetData.getMaterialProvider(),
              EntityManager.get());

      FilamentAsset createdAsset = renderableData.isGltfBinary ? loader.createAssetFromBinary(renderableData.gltfByteBuffer)
              : loader.createAssetFromJson(renderableData.gltfByteBuffer);

      if (createdAsset == null) {
        throw new IllegalStateException("Failed to load gltf");
      }

      if (renderable.collisionShape == null) {
        com.google.android.filament.Box box = createdAsset.getBoundingBox();
        float[] halfExtent = box.getHalfExtent();
        float[] center = box.getCenter();
        renderable.collisionShape =
            new Box(
                new Vector3(halfExtent[0], halfExtent[1], halfExtent[2]).scaled(2.0f),
                new Vector3(center[0], center[1], center[2]));
      }

      Function<String, Uri> urlResolver = renderableData.urlResolver;
      for (String uri : createdAsset.getResourceUris()) {
        if (urlResolver == null) {
          Log.e(TAG, "Failed to download uri " + uri + " no url resolver.");
          continue;
        }
        Uri dataUri = urlResolver.apply(uri);
        try {
          Callable<InputStream> callable = LoadHelper.fromUri(renderableData.context, dataUri);
          renderableData.resourceLoader.addResourceData(
              uri, ByteBuffer.wrap(SceneformBufferUtils.inputStreamCallableToByteArray(callable)));
        } catch (Exception e) {
          Log.e(TAG, "Failed to download data uri " + dataUri, e);
        }
      }
      renderableData.resourceLoader.loadResources(createdAsset);

      TransformManager transformManager = EngineInstance.getEngine().getTransformManager();

      @EntityInstance int rootInstance = transformManager.getInstance(createdAsset.getRoot());
      @EntityInstance
      int parentInstance = transformManager.getInstance(childEntity == 0 ? entity : childEntity);

      transformManager.setParent(rootInstance, parentInstance);

      filamentAsset = createdAsset;
    }
  }

  
  void createGltfModelInstance() {return ;}




























  





  @Nullable
  
  public FilamentAsset getFilamentAsset() {
    return filamentAsset;
  }

  /**
   * Get the {@link Renderable} to display for this {@link RenderableInstance}.
   *
   * @return {@link Renderable} asset, usually a 3D model.
   */
  public Renderable getRenderable() {
    return renderable;
  }

  public @Entity int getEntity() {
    return entity;
  }

  public @Entity int getRenderedEntity() {
    return (childEntity == 0) ? entity : childEntity;
  }

  void setModelMatrix(TransformManager transformManager, @Size(min = 16) float[] transform) {
    // Use entity, rather than childEntity; setting the latter would slam the local transform which
    // corrects for scaling and offset.
    @EntityInstance int instance = transformManager.getInstance(entity);
    transformManager.setTransform(instance, transform);
  }

  /** @hide */
  public Matrix getWorldModelMatrix() {
    return renderable.getFinalModelMatrix(transformProvider.getWorldModelMatrix());
  }

  public void setSkinningModifier(@Nullable SkinningModifier skinningModifier) {
    this.skinningModifier = skinningModifier;
  }

  















  
  private void setupSkeleton(IRenderableInternalData renderableInternalData) {return ;}



  /** @hide */
  public void prepareForDraw() {
    renderable.prepareForDraw();

    ChangeId changeId = renderable.getId();
    if (changeId.checkChanged(renderableId)) {
      IRenderableInternalData renderableInternalData = renderable.getRenderableData();
      setupSkeleton(renderableInternalData);
      renderableInternalData.buildInstanceData(renderable, getRenderedEntity());
      renderableId = changeId.get();
      // First time we're rendering, so always update the skinning even if we aren't animating and
      // there is no skinModifier.
      updateSkinning(true);
    } else {
      // Will only update the skinning if the renderable is animating or there is a skinModifier
      // that has been changed since the last draw.
      updateSkinning(false);
    }
  }

  
  private void attachFilamentAssetToRenderer() {
    FilamentAsset currentFilamentAsset = filamentAsset;
    if (currentFilamentAsset != null) {
      int[] entities = currentFilamentAsset.getEntities();
      Preconditions.checkNotNull(attachedRenderer)
          .getFilamentScene()
          .addEntity(currentFilamentAsset.getRoot());
      Preconditions.checkNotNull(attachedRenderer).getFilamentScene().addEntities(entities);
    }
  }

  /** @hide */
  public void attachToRenderer(Renderer renderer) {
    renderer.addInstance(this);
    attachedRenderer = renderer;
    renderable.attachToRenderer(renderer);
    attachFilamentAssetToRenderer();
  }

  
  void detachFilamentAssetFromRenderer() {
    FilamentAsset currentFilamentAsset = filamentAsset;
    if (currentFilamentAsset != null) {
      int[] entities = currentFilamentAsset.getEntities();
      for (int entity : entities) {
        Preconditions.checkNotNull(attachedRenderer).getFilamentScene().removeEntity(entity);
      }
      int root = currentFilamentAsset.getRoot();
      Preconditions.checkNotNull(attachedRenderer).getFilamentScene().removeEntity(root);
    }
  }

  /** @hide */
  public void detachFromRenderer() {
    Renderer rendererToDetach = attachedRenderer;
    if (rendererToDetach != null) {
      detachFilamentAssetFromRenderer();
      rendererToDetach.removeInstance(this);
      renderable.detatchFromRenderer();
    }
  }

  /**
   * Returns the transform of this renderable relative to it's node. This will be non-null if the
   * .sfa file includes a scale other than 1 or has recentering turned on.
   *
   * @hide
   */
  @Nullable
  public Matrix getRelativeTransform() {
    if (cachedRelativeTransform != null) {
      return cachedRelativeTransform;
    }

    IRenderableInternalData renderableData = renderable.getRenderableData();
    float scale = renderableData.getTransformScale();
    Vector3 offset = renderableData.getTransformOffset();
    if (scale == 1f && Vector3.equals(offset, Vector3.zero())) {
      return null;
    }

    cachedRelativeTransform = new Matrix();
    cachedRelativeTransform.makeScale(scale);
    cachedRelativeTransform.setTranslation(offset);
    return cachedRelativeTransform;
  }

  /**
   * Returns the inverse transform of this renderable relative to it's node. This will be non-null
   * if the .sfa file includes a scale other than 1 or has recentering turned on.
   *
   * @hide
   */
  @Nullable
  public Matrix getRelativeTransformInverse() {
    if (cachedRelativeTransformInverse != null) {
      return cachedRelativeTransformInverse;
    }

    Matrix relativeTransform = getRelativeTransform();
    if (relativeTransform == null) {
      return null;
    }

    cachedRelativeTransformInverse = new Matrix();
    Matrix.invert(relativeTransform, cachedRelativeTransformInverse);
    return cachedRelativeTransformInverse;
  }

  
  private void updateSkinning(boolean force) {return ;}































  void setBlendOrderAt(int index, int blendOrder) {
    RenderableManager renderableManager = EngineInstance.getEngine().getRenderableManager();
    @EntityInstance int renderableInstance = renderableManager.getInstance(getRenderedEntity());
    renderableManager.setBlendOrderAt(renderableInstance, index, blendOrder);
  }

  @Entity
  private static int createFilamentEntity(IEngine engine) {
    EntityManager entityManager = EntityManager.get();
    @Entity int entity = entityManager.create();
    TransformManager transformManager = engine.getTransformManager();
    transformManager.create(entity);
    return entity;
  }

  @Entity
  private static int createFilamentChildEntity(
      IEngine engine, @Entity int entity, Matrix relativeTransform) {
    EntityManager entityManager = EntityManager.get();
    @Entity int childEntity = entityManager.create();
    TransformManager transformManager = engine.getTransformManager();
    transformManager.create(
        childEntity, transformManager.getInstance(entity), relativeTransform.data);
    return childEntity;
  }

  /** Releases resources held by a {@link RenderableInstance} */
  private static final class CleanupCallback implements Runnable {
    private final int childEntity;
    private final int entity;

    CleanupCallback(int childEntity, int entity) {
      this.childEntity = childEntity;
      this.entity = entity;
    }

    @Override
    public void run() {
      AndroidPreconditions.checkUiThread();

      IEngine engine = EngineInstance.getEngine();

      if (engine == null || !engine.isValid()) {
        return;
      }

      RenderableManager renderableManager = engine.getRenderableManager();

      if (childEntity != 0) {
        renderableManager.destroy(childEntity);
      }
      if (entity != 0) {
        renderableManager.destroy(entity);
      }
    }
  }
}
