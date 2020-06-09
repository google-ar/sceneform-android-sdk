package com.google.ar.sceneform.rendering;

import android.content.Context;
import android.os.Build;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.google.ar.sceneform.collision.Box;
import com.google.ar.sceneform.common.TransformProvider;
import com.google.ar.sceneform.math.Matrix;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.resources.ResourceRegistry;
import com.google.ar.sceneform.utilities.AndroidPreconditions;
import com.google.ar.sceneform.utilities.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Renders a 2D Android view in 3D space by attaching it to a {@link com.google.ar.sceneform.Node}
 * with {@link com.google.ar.sceneform.Node#setRenderable(Renderable)}. By default, the size of the
 * view is 1 meter in the {@link com.google.ar.sceneform.Scene} per 250dp in the layout. Use a
 * {@link ViewSizer} to control how the size of the view in the {@link
 * com.google.ar.sceneform.Scene} is calculated.
 *
 * <pre>{@code
 * future = ViewRenderable.builder().setView(context, R.layout.view).build();
 * viewRenderable = future.thenAccept(...);
 * }</pre>
 */
@RequiresApi(api = Build.VERSION_CODES.N)

public class ViewRenderable extends Renderable {
  private static final String TAG = ViewRenderable.class.getSimpleName();

  /**
   * Controls the horizontal alignment of the {@link ViewRenderable} relative to the {@link
   * com.google.ar.sceneform.Node} it is attached to. The default value is CENTER.
   */
  public enum HorizontalAlignment {
    LEFT,
    CENTER,
    RIGHT
  }

  /**
   * Controls the vertical alignment of the {@link ViewRenderable} relative to the {@link
   * com.google.ar.sceneform.Node} it is attached to. The default value is BOTTOM.
   */
  public enum VerticalAlignment {
    BOTTOM,
    CENTER,
    TOP
  }

  @Nullable private ViewRenderableInternalData viewRenderableData;
  private final View view;

  // Used to apply a final scale to the renderable that makes it render at an appropriate size based
  // on the size of the view.
  private final Matrix viewScaleMatrix = new Matrix();

  private ViewSizer viewSizer;
  private VerticalAlignment verticalAlignment = VerticalAlignment.BOTTOM;
  private HorizontalAlignment horizontalAlignment = HorizontalAlignment.CENTER;

  @Nullable private Renderer renderer;
  private boolean isInitialized;

  @SuppressWarnings({"initialization"})
  private final RenderViewToExternalTexture.OnViewSizeChangedListener onViewSizeChangedListener =
      (int width, int height) -> {
        if (isInitialized) {
          updateSuggestedCollisionShapeAsync();
        }
      };

  /** The 2D Android {@link View} that is rendered by this {@link ViewRenderable}. */
  public View getView() {
    return view;
  }

  /**
   * Creates a new instance of this ViewRenderable.
   *
   * <p>The new renderable will have unique copy of all mutable state. All materials referenced by
   * the ViewRenderable will also be instanced. Immutable data will be shared between the instances.
   * The new ViewRenderable will reference the same getFilamentEngine View as the original
   * ViewRenderable.
   */
  @Override
  public ViewRenderable makeCopy() {
    return new ViewRenderable(this);
  }

  /** @hide */
  @SuppressWarnings({"initialization"})
  // Suppress @UnderInitialization warning.
  ViewRenderable(Builder builder, View view) {
    super(builder);

    Preconditions.checkNotNull(view, "Parameter \"view\" was null.");

    this.view = view;
    viewSizer = builder.viewSizer;
    horizontalAlignment = builder.horizontalAlignment;
    verticalAlignment = builder.verticalAlignment;
    RenderViewToExternalTexture renderView =
        new RenderViewToExternalTexture(view.getContext(), view);
    renderView.addOnViewSizeChangedListener(onViewSizeChangedListener);
    viewRenderableData = new ViewRenderableInternalData(renderView);
    viewRenderableData.retain();

    // Empty collision box. Will be modified to fit the size of the view after the view is measured.
    // If the size of the view changes, the collision shape will change too.
    collisionShape = new Box(Vector3.zero());
  }

  ViewRenderable(ViewRenderable other) {
    super(other);

    view = other.view;
    viewSizer = other.viewSizer;
    horizontalAlignment = other.horizontalAlignment;
    verticalAlignment = other.verticalAlignment;
    viewRenderableData = Preconditions.checkNotNull(other.viewRenderableData);
    viewRenderableData.retain();
    viewRenderableData.getRenderView().addOnViewSizeChangedListener(onViewSizeChangedListener);
  }

  /**
   * Gets the {@link ViewSizer} that controls the size of this {@link ViewRenderable} in the {@link
   * com.google.ar.sceneform.Scene}.
   */
  public ViewSizer getSizer() {
    return viewSizer;
  }

  /**
   * Sets the {@link ViewSizer} that controls the size of this {@link ViewRenderable} in the {@link
   * com.google.ar.sceneform.Scene}.
   */
  public void setSizer(ViewSizer viewSizer) {
    Preconditions.checkNotNull(viewSizer, "Parameter \"viewSizer\" was null.");
    this.viewSizer = viewSizer;
    updateSuggestedCollisionShape();
  }

  /**
   * Gets the {@link HorizontalAlignment} that controls where the {@link ViewRenderable} is
   * positioned relative to the {@link com.google.ar.sceneform.Node} it is attached to along the
   * x-axis. The default is {@link HorizontalAlignment#CENTER}.
   */
  public HorizontalAlignment getHorizontalAlignment() {
    return horizontalAlignment;
  }

  /**
   * Sets the {@link HorizontalAlignment} that controls where the {@link ViewRenderable} is
   * positioned relative to the {@link com.google.ar.sceneform.Node} it is attached to along the
   * x-axis. The default is {@link HorizontalAlignment#CENTER}.
   */
  public void setHorizontalAlignment(HorizontalAlignment horizontalAlignment) {
    this.horizontalAlignment = horizontalAlignment;
    updateSuggestedCollisionShape();
  }

  /**
   * Gets the {@link VerticalAlignment} that controls where the {@link ViewRenderable} is positioned
   * relative to the {@link com.google.ar.sceneform.Node} it is attached to along the y-axis. The
   * default is {@link VerticalAlignment#BOTTOM}.
   */
  public VerticalAlignment getVerticalAlignment() {
    return verticalAlignment;
  }

  /**
   * Sets the {@link VerticalAlignment} that controls where the {@link ViewRenderable} is positioned
   * relative to the {@link com.google.ar.sceneform.Node} it is attached to along the y-axis. The
   * default is {@link VerticalAlignment#BOTTOM}.
   */
  public void setVerticalAlignment(VerticalAlignment verticalAlignment) {
    this.verticalAlignment = verticalAlignment;
    updateSuggestedCollisionShape();
  }

  /**
   * Takes the model matrix from the {@link TransformProvider} for rendering this {@link
   * com.google.ar.sceneform.Node} and scales it to size it appropriately based on the meters to
   * pixel ratio for the view.
   *
   * @hide
   * @param originalMatrix
   */
  @Override
  public Matrix getFinalModelMatrix(final Matrix originalMatrix) {
    Preconditions.checkNotNull(originalMatrix, "Parameter \"originalMatrix\" was null.");
    // May be better to cache this when the transform provider's model matrix changes.
    // This would require saving the matrix on a per-instance basis instead of a per-renderable
    // basis as well.

    Vector3 size = viewSizer.getSize(view);
    viewScaleMatrix.makeScale(new Vector3(size.x, size.y, 1.0f));

    // Set the translation of the matrix based on the alignment pre-scaled by the size.
    // This is much more efficient than allocating an additional matrix and doing a matrix multiply.
    viewScaleMatrix.setTranslation(
        new Vector3(
            getOffsetRatioForAlignment(horizontalAlignment) * size.x,
            getOffsetRatioForAlignment(verticalAlignment) * size.y,
            0.0f));

    Matrix.multiply(originalMatrix, viewScaleMatrix, viewScaleMatrix);

    return viewScaleMatrix;
  }

  /** @hide */
  @Override
  @SuppressWarnings({"AndroidApiChecker", "FutureReturnValueIgnored"}) // CompletableFuture
  void prepareForDraw() {
    if (getId().isEmpty()) {
      return;
    }

    ViewRenderableInternalData data = Preconditions.checkNotNull(viewRenderableData);
    RenderViewToExternalTexture renderViewToExternalTexture = data.getRenderView();

    if (!renderViewToExternalTexture.isAttachedToWindow()
        || !renderViewToExternalTexture.isLaidOut()) {
      // Wait for the view to finish attachment.
      return;
    }

    // Wait until one frame after the surface texture has been drawn to for the first time.
    // Fixes an issue where the ViewRenderable would render black for a frame before displaying.
    boolean hasDrawnToSurfaceTexture = renderViewToExternalTexture.hasDrawnToSurfaceTexture();
    if (!hasDrawnToSurfaceTexture) {
      return;
    }

    if (!isInitialized) {
      getMaterial()
          .setExternalTexture("viewTexture", renderViewToExternalTexture.getExternalTexture());
      updateSuggestedCollisionShape();

      isInitialized = true;
    }

    if (renderer != null && renderer.isFrontFaceWindingInverted()) {
      getMaterial().setFloat2("offsetUv", 1, 0);
    }

    super.prepareForDraw();
  }

  @Override
  void attachToRenderer(Renderer renderer) {
    Preconditions.checkNotNull(viewRenderableData)
        .getRenderView()
        .attachView(renderer.getViewAttachmentManager());
    this.renderer = renderer;
  }

  @Override
  void detatchFromRenderer() {
    Preconditions.checkNotNull(viewRenderableData).getRenderView().detachView();
    this.renderer = null;
  }

  private void updateSuggestedCollisionShapeAsync() {
    view.post(this::updateSuggestedCollisionShape);
  }

  private void updateSuggestedCollisionShape() {
    if (getId().isEmpty()) {
      return;
    }

    Box box = (Box) collisionShape;
    if (box == null) {
      return;
    }

    IRenderableInternalData renderableData = getRenderableData();
    Vector3 viewSize = viewSizer.getSize(view);

    Vector3 size = renderableData.getSizeAabb();
    size.x *= viewSize.x;
    size.y *= viewSize.y;

    Vector3 center = renderableData.getCenterAabb();
    center.x *= viewSize.x;
    center.y *= viewSize.y;

    // Offset the collision shape based on the alignment.
    center.x += getOffsetRatioForAlignment(horizontalAlignment) * size.x;
    center.y += getOffsetRatioForAlignment(verticalAlignment) * size.y;

    box.setSize(size);
    box.setCenter(center);
  }

  private float getOffsetRatioForAlignment(HorizontalAlignment horizontalAlignment) {
    IRenderableInternalData data = getRenderableData();
    Vector3 centerAabb = data.getCenterAabb();
    Vector3 extentsAabb = data.getExtentsAabb();

    switch (horizontalAlignment) {
      case LEFT:
        return -centerAabb.x + extentsAabb.x;
      case CENTER:
        return -centerAabb.x;
      case RIGHT:
        return -centerAabb.x - extentsAabb.x;
    }
    throw new IllegalStateException("Invalid HorizontalAlignment: " + horizontalAlignment);
  }

  private float getOffsetRatioForAlignment(VerticalAlignment verticalAlignment) {
    IRenderableInternalData data = getRenderableData();
    Vector3 centerAabb = data.getCenterAabb();
    Vector3 extentsAabb = data.getExtentsAabb();

    switch (verticalAlignment) {
      case BOTTOM:
        return -centerAabb.y + extentsAabb.y;
      case CENTER:
        return -centerAabb.y;
      case TOP:
        return -centerAabb.y - extentsAabb.y;
    }
    throw new IllegalStateException("Invalid VerticalAlignment: " + verticalAlignment);
  }

  /** @hide */
  @Override
  protected void finalize() throws Throwable {
    try {
      ThreadPools.getMainExecutor().execute(() -> dispose());
    } catch (Exception e) {
      Log.e(TAG, "Error while Finalizing View Renderable.", e);
    } finally {
      super.finalize();
    }
  }

  /** @hide */
  void dispose() {
    AndroidPreconditions.checkUiThread();

    ViewRenderableInternalData viewRenderableData = this.viewRenderableData;
    if (viewRenderableData != null) {
      viewRenderableData.getRenderView().removeOnViewSizeChangedListener(onViewSizeChangedListener);
      viewRenderableData.release();
      this.viewRenderableData = null;
    }
  }

  /** Constructs a {@link ViewRenderable} */
  public static Builder builder() {
    AndroidPreconditions.checkMinAndroidApiLevel();
    return new Builder();
  }

  /** Factory class for {@link ViewRenderable} */
  public static final class Builder extends Renderable.Builder<ViewRenderable, Builder> {
    private static final int DEFAULT_DP_TO_METERS = 250;
    @Nullable private View view;
    private ViewSizer viewSizer = new DpToMetersViewSizer(DEFAULT_DP_TO_METERS);
    private VerticalAlignment verticalAlignment = VerticalAlignment.BOTTOM;
    private HorizontalAlignment horizontalAlignment = HorizontalAlignment.CENTER;

    @SuppressWarnings("AndroidApiChecker")
    private OptionalInt resourceId = OptionalInt.empty();

    private Builder() {}

    public Builder setView(Context context, View view) {
      this.view = view;
      this.context = context;
      registryId = view;
      return this;
    }

    @SuppressWarnings("AndroidApiChecker")
    public Builder setView(Context context, int resourceId) {
      this.resourceId = OptionalInt.of(resourceId);
      this.context = context;
      registryId = null;
      return this;
    }

    /**
     * Set the {@link ViewSizer} that controls the size of the built {@link ViewRenderable} in the
     * {@link com.google.ar.sceneform.Scene}.
     */
    public Builder setSizer(ViewSizer viewSizer) {
      Preconditions.checkNotNull(viewSizer, "Parameter \"viewSizer\" was null.");
      this.viewSizer = viewSizer;
      return this;
    }

    /**
     * Sets the {@link HorizontalAlignment} that controls where the {@link ViewRenderable} is
     * positioned relative to the {@link com.google.ar.sceneform.Node} it is attached to along the
     * x-axis. The default is {@link HorizontalAlignment#CENTER}.
     */
    public Builder setHorizontalAlignment(HorizontalAlignment horizontalAlignment) {
      this.horizontalAlignment = horizontalAlignment;
      return this;
    }

    /**
     * Sets the {@link VerticalAlignment} that controls where the {@link ViewRenderable} is
     * positioned relative to the {@link com.google.ar.sceneform.Node} it is attached to along the
     * y-axis. The default is {@link VerticalAlignment#BOTTOM}.
     */
    public Builder setVerticalAlignment(VerticalAlignment verticalAlignment) {
      this.verticalAlignment = verticalAlignment;
      return this;
    }

    @Override
    @SuppressWarnings("AndroidApiChecker") // java.util.concurrent.CompletableFuture
    public CompletableFuture<ViewRenderable> build() {
      if (!hasSource() && context != null) {
        // For ViewRenderables, the registryId must come from the View, not the RCB source.
        // If the source is a View, use that as the registryId. If the view is null, then the source
        // is a resource id and the registryId should also be null.
        registryId = view;

        CompletableFuture<Void> setSourceFuture = Material.builder()
                .setSource(
                        context,
                        RenderingResources.GetSceneformResource(
                                context, RenderingResources.Resource.VIEW_RENDERABLE_MATERIAL))
                .build()
                .thenAccept(
                        material -> {

                          ArrayList<Vertex> vertices = new ArrayList<>();
                          vertices.add(Vertex.builder()
                                  .setPosition(new Vector3(-0.5f, 0.0f, 0.0f))
                                  .setNormal(new Vector3(0.0f, 0.0f, 1.0f))
                                  .setUvCoordinate(new Vertex.UvCoordinate(0.0f, 0.0f))
                                  .build());
                          vertices.add(Vertex.builder()
                                  .setPosition(new Vector3(0.5f, 0.0f, 0.0f))
                                  .setNormal(new Vector3(0.0f, 0.0f, 1.0f))
                                  .setUvCoordinate(new Vertex.UvCoordinate(1.0f, 0.0f))
                                  .build());
                          vertices.add(Vertex.builder()
                                  .setPosition(new Vector3(-0.5f, 1.0f, 0.0f))
                                  .setNormal(new Vector3(0.0f, 0.0f, 1.0f))
                                  .setUvCoordinate(new Vertex.UvCoordinate(0.0f, 1.0f))
                                  .build());
                          vertices.add(Vertex.builder()
                                  .setPosition(new Vector3(0.5f, 1.0f, 0.0f))
                                  .setNormal(new Vector3(0.0f, 0.0f, 1.0f))
                                  .setUvCoordinate(new Vertex.UvCoordinate(1.0f, 1.0f))
                                  .build());
                          ArrayList<Integer> triangleIndices = new ArrayList<>();
                          triangleIndices.add(0);
                          triangleIndices.add(1);
                          triangleIndices.add(2);
                          triangleIndices.add(1);
                          triangleIndices.add(3);
                          triangleIndices.add(2);
                          RenderableDefinition.Submesh submesh =
                                  RenderableDefinition.Submesh.builder().setTriangleIndices(triangleIndices).setMaterial(material).build();
                          setSource(
                                  RenderableDefinition.builder()
                                          .setVertices(vertices)
                                          .setSubmeshes(Arrays.asList(submesh))
                                          .build()
                          );
                        }
                );
        return setSourceFuture.thenCompose((Void) -> super.build());
      }

      return super.build();
    }

    @Override
    protected ViewRenderable makeRenderable() {
      if (this.view != null) {
        return new ViewRenderable(this, view);
      } else {
        return new ViewRenderable(this, inflateViewFromResourceId());
      }
    }

    /** @hide */
    @Override
    protected Class<ViewRenderable> getRenderableClass() {
      return ViewRenderable.class;
    }

    /** @hide */
    @Override
    protected ResourceRegistry<ViewRenderable> getRenderableRegistry() {
      return ResourceManager.getInstance().getViewRenderableRegistry();
    }

    /** @hide */
    @Override
    protected Builder getSelf() {
      return this;
    }

    /** @hide */
    @SuppressWarnings("AndroidApiChecker")
    @Override
    protected void checkPreconditions() {
      super.checkPreconditions();

      boolean hasView = resourceId.isPresent() || view != null;

      if (!hasView) {
        throw new AssertionError("ViewRenderable must have a source.");
      }

      if (resourceId.isPresent() && view != null) {
        throw new AssertionError(
            "ViewRenderable must have a resourceId or a view as a source. This one has both.");
      }
    }

    @SuppressWarnings("AndroidApiChecker")
    private View inflateViewFromResourceId() {
      if (context == null) {
        throw new AssertionError("Context cannot be null");
      }

      // Inflate the view in a detached state.
      // We need a dummy ViewGroup as the root so that the layout params of the view are loaded.
      ViewGroup dummy = new FrameLayout(context);
      return LayoutInflater.from(context).inflate(resourceId.getAsInt(), dummy, false);
    }
  }
}
