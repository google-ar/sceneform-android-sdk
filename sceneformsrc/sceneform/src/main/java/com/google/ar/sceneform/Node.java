package com.google.ar.sceneform;

import android.support.annotation.Nullable;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.google.ar.sceneform.collision.Collider;
import com.google.ar.sceneform.collision.CollisionShape;
import com.google.ar.sceneform.collision.Ray;
import com.google.ar.sceneform.common.TransformProvider;
import com.google.ar.sceneform.math.Matrix;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.Light;
import com.google.ar.sceneform.rendering.LightInstance;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.Renderable;
import com.google.ar.sceneform.rendering.RenderableInstance;
import com.google.ar.sceneform.rendering.Renderer;

import com.google.ar.sceneform.utilities.AndroidPreconditions;
import com.google.ar.sceneform.utilities.ChangeId;
import com.google.ar.sceneform.utilities.Preconditions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A Node represents a transformation within the scene graph's hierarchy. It can contain a
 * renderable for the rendering engine to render.
 *
 * <p>Each node can have an arbitrary number of child nodes and one parent. The parent may be
 * another node, or the scene.
 */
public class Node extends NodeParent implements TransformProvider {
  /**
   * Interface definition for a callback to be invoked when a touch event is dispatched to this
   * node. The callback will be invoked before {@link #onTouchEvent(HitTestResult, MotionEvent)} is
   * called.
   */
  public interface OnTouchListener {
    /**
     * Handles when a touch event has been dispatched to a node.
     *
     * <p>On {@link MotionEvent#ACTION_DOWN} events, {@link HitTestResult#getNode()} will always be
     * this node or one of its children. On other events, the touch may have moved causing the
     * {@link HitTestResult#getNode()} to change (or possibly be null).
     *
     * @param hitTestResult represents the node that was touched and information about where it was
     *     touched
     * @param motionEvent the MotionEvent object containing full information about the event
     * @return true if the listener has consumed the event, false otherwise
     */
    boolean onTouch(HitTestResult hitTestResult, MotionEvent motionEvent);
  }

  /** Interface definition for a callback to be invoked when a node is tapped. */
  public interface OnTapListener {
    /**
     * Handles when a node has been tapped.
     *
     * <p>{@link HitTestResult#getNode()} will always be this node or one of its children.
     *
     * @param hitTestResult represents the node that was tapped and information about where it was
     *     touched
     * @param motionEvent the {@link MotionEvent#ACTION_UP} MotionEvent that caused the tap
     */
    void onTap(HitTestResult hitTestResult, MotionEvent motionEvent);
  }

  /** Interface definition for callbacks to be invoked when node lifecycle events occur. */
  public interface LifecycleListener {
    /**
     * Notifies the listener that {@link #onActivate()} was called.
     *
     * @param node the node that was activated
     */
    void onActivated(Node node);

    /**
     * Notifies the listener that {@link #onUpdate(FrameTime)} was called.
     *
     * @param node the node that was updated
     * @param frameTime provides time information for the current frame
     */
    void onUpdated(Node node, FrameTime frameTime);

    /**
     * Notifies the listener that {@link #onDeactivate()} was called.
     *
     * @param node the node that was deactivated
     */
    void onDeactivated(Node node);
  }

  /**
   * Interface definition for callbacks to be invoked when the transformation of the node changes.
   */
  public interface TransformChangedListener {

    /**
     * Notifies the listener that the transformation of the {@link Node} has changed. Called right
     * after {@link #onTransformChange(Node)}.
     *
     * <p>The originating node is the most top-level node in the hierarchy that triggered the node
     * to change. It will always be either the same node or one of its' parents. i.e. if node A's
     * position is changed, then that will trigger {@link #onTransformChanged(Node, Node)} to be
     * called for all of it's descendants with the originatingNode being node A.
     *
     * @param node the node that changed
     * @param originatingNode the node that triggered the transformation to change
     */
    void onTransformChanged(Node node, Node originatingNode);
  }

  /** Used to keep track of data for detecting if a tap gesture has occurred on this node. */
  private static class TapTrackingData {
    // The node that was being touched when ACTION_DOWN occurred.
    final Node downNode;

    // The screen-space position that was being touched when ACTION_DOWN occurred.
    final Vector3 downPosition;

    TapTrackingData(Node downNode, Vector3 downPosition) {
      this.downNode = downNode;
      this.downPosition = new Vector3(downPosition);
    }
  }

  private static final float DIRECTION_UP_EPSILON = 0.99f;

  // This is the default from the ViewConfiguration class.
  private static final int DEFAULT_TOUCH_SLOP = 8;

  private static final String DEFAULT_NAME = "Node";

  private static final int LOCAL_TRANSFORM_DIRTY = 1;
  private static final int WORLD_TRANSFORM_DIRTY = 1 << 1;
  private static final int WORLD_INVERSE_TRANSFORM_DIRTY = 1 << 2;
  private static final int WORLD_POSITION_DIRTY = 1 << 3;
  private static final int WORLD_ROTATION_DIRTY = 1 << 4;
  private static final int WORLD_SCALE_DIRTY = 1 << 5;

  private static final int WORLD_DIRTY_FLAGS =
      WORLD_TRANSFORM_DIRTY
          | WORLD_INVERSE_TRANSFORM_DIRTY
          | WORLD_POSITION_DIRTY
          | WORLD_ROTATION_DIRTY
          | WORLD_SCALE_DIRTY;

  private static final int LOCAL_DIRTY_FLAGS = LOCAL_TRANSFORM_DIRTY | WORLD_DIRTY_FLAGS;

  // Scene Graph fields.
  @Nullable private Scene scene;
  // Stores the parent as a node (if the parent is a node) to avoid casting.
  @Nullable private Node parentAsNode;

  // the name of the node to identify it in the hierarchy
  @SuppressWarnings("unused")
  private String name = DEFAULT_NAME;

  // name hash for comparison
  private int nameHash = DEFAULT_NAME.hashCode();

  /**
   * WARNING: Do not assign this property directly unless you know what you are doing. Instead, call
   * setParent. This field is only exposed in the package to be accessible to the class NodeParent.
   *
   * <p>In addition to setting this field, setParent will also do the following things:
   *
   * <ul>
   *   <li>Remove this node from its previous parent's children.
   *   <li>Add this node to its new parent's children.
   *   <li>Recursively update the node's transformation to reflect the change in parent
   *   <li>Recursively update the scene field to match the new parent's scene field.
   * </ul>
   */
  // The node's parent could be a Node or the scene.
  @Nullable NodeParent parent;

  // Local transformation fields.
  private final Vector3 localPosition = new Vector3();
  private final Quaternion localRotation = new Quaternion();
  private final Vector3 localScale = new Vector3();
  private final Matrix cachedLocalModelMatrix = new Matrix();

  // World transformation fields.
  private final Vector3 cachedWorldPosition = new Vector3();
  private final Quaternion cachedWorldRotation = new Quaternion();
  private final Vector3 cachedWorldScale = new Vector3();
  private final Matrix cachedWorldModelMatrix = new Matrix();
  private final Matrix cachedWorldModelMatrixInverse = new Matrix();

  /** Determines when various aspects of the node's transform are dirty and must be recalculated. */
  private int dirtyTransformFlags = LOCAL_DIRTY_FLAGS;

  // Status fields.
  private boolean enabled = true;
  private boolean active = false;

  // Rendering fields.
  private int renderableId = ChangeId.EMPTY_ID;
  @Nullable private RenderableInstance renderableInstance;
  // TODO: Right now, lightInstance can cause leaks because it subscribes to event
  // listeners on Light that will not be disposed unless setLight(null) is called.
  @Nullable private LightInstance lightInstance;

  // Collision fields.
  @Nullable private CollisionShape collisionShape;
  @Nullable private Collider collider;

  // Listeners.
  @Nullable private OnTouchListener onTouchListener;
  @Nullable private OnTapListener onTapListener;
  private final ArrayList<LifecycleListener> lifecycleListeners = new ArrayList<>();
  private final ArrayList<TransformChangedListener> transformChangedListeners = new ArrayList<>();
  private boolean allowDispatchTransformChangedListeners = true;

  // Stores data used for detecting when a tap has occurred on this node.
  @Nullable private TapTrackingData tapTrackingData = null;

  /** Creates a node with no parent. */
  @SuppressWarnings("initialization") // Suppress @UnderInitialization warning.
  public Node() {
    AndroidPreconditions.checkUiThread();

    localScale.set(1, 1, 1);
    cachedWorldScale.set(localScale);
  }

  /**
   * Sets the name of this node. Nodes can be found using their names. Multiple nodes may have the
   * same name, in which case calling {@link NodeParent#findByName(String)} will return the first
   * node with the given name.
   *
   * @param name The name of the node.
   */
  public final void setName(String name) {
    Preconditions.checkNotNull(name, "Parameter \"name\" was null.");

    this.name = name;
    nameHash = name.hashCode();
  }

  /** Returns the name of the node. The default value is "Node". */
  public final String getName() {
    return name;
  }

  /**
   * Changes the parent node of this node. If set to null, this node will be detached from its
   * parent. The local position, rotation, and scale of this node will remain the same. Therefore,
   * the world position, rotation, and scale of this node may be different after the parent changes.
   *
   * <p>The parent may be another {@link Node} or a {@link Scene}. If it is a scene, then this
   * {@link Node} is considered top level. {@link #getParent()} will return null, and {@link
   * #getScene()} will return the scene.
   *
   * @see #getParent()
   * @see #getScene()
   * @param parent The new parent that this node will be a child of. If null, this node will be
   *     detached from its parent.
   */
  public void setParent(@Nullable NodeParent parent) {
    AndroidPreconditions.checkUiThread();

    if (parent == this.parent) {
      return;
    }

    // Disallow dispatching transformed changed here so we don't
    // send it multiple times when setParent is called.
    allowDispatchTransformChangedListeners = false;
    if (parent != null) {
      // If this node already has a parent, addChild automatically removes it from its old parent.
      parent.addChild(this);
    } else if (this.parent != null) {
      this.parent.removeChild(this);
    }
    allowDispatchTransformChangedListeners = true;

    // Make sure transform changed is dispatched.
    markTransformChangedRecursively(WORLD_DIRTY_FLAGS, this);
  }

  /**
   * Returns the scene that this node is part of, null if it isn't part of any scene. A node is part
   * of a scene if its highest level ancestor is a {@link Scene}
   */
  @Nullable
  public final Scene getScene() {
    return scene;
  }

  /**
   * Returns the parent of this node. If this {@link Node} has a parent, and that parent is a {@link
   * Node} or {@link Node} subclass, then this function returns the parent as a {@link Node}.
   * Returns null if the parent is a {@link Scene}, use {@link #getScene()} to retrieve the parent
   * instead.
   *
   * @return the parent as a {@link Node}, if the parent is a {@link Node}.
   */
  @Nullable
  public final Node getParent() {
    return parentAsNode;
  }

  /**
   * Returns true if this node is top level. A node is considered top level if it has no parent or
   * if the parent is the scene.
   *
   * @return true if the node is top level
   */
  public boolean isTopLevel() {
    return parent == null || parent == scene;
  }

  /**
   * Checks whether the given node parent is an ancestor of this node recursively.
   *
   * @param ancestor the node parent to check
   * @return true if the node is an ancestor of this node
   */
  public final boolean isDescendantOf(NodeParent ancestor) {
    Preconditions.checkNotNull(ancestor, "Parameter \"ancestor\" was null.");

    NodeParent currentAncestor = parent;

    // Used to iterate up through the hierarchy because NodeParent is just a container for children
    // and doesn't have its own parent.
    Node currentAncestorAsNode = parentAsNode;

    while (currentAncestor != null) {
      // Make sure to do the equality check against currentAncestor instead of currentAncestorAsNode
      // so that this works with any NodeParent and not just Node.
      if (currentAncestor == ancestor) {
        return true;
      }

      if (currentAncestorAsNode != null) {
        currentAncestor = currentAncestorAsNode.parent;
        currentAncestorAsNode = currentAncestorAsNode.parentAsNode;
      } else {
        break;
      }
    }
    return false;
  }

  /**
   * Sets the enabled state of this node. Note that a Node may be enabled but still inactive if it
   * isn't part of the scene or if its parent is inactive.
   *
   * @see #isActive()
   * @param enabled the new enabled status of the node
   */
  public final void setEnabled(boolean enabled) {
    AndroidPreconditions.checkUiThread();

    if (this.enabled == enabled) {
      return;
    }

    this.enabled = enabled;
    updateActiveStatusRecursively();
  }

  /**
   * Gets the enabled state of this node. Note that a Node may be enabled but still inactive if it
   * isn't part of the scene or if its parent is inactive.
   *
   * @see #isActive()
   * @return the node's enabled status.
   */
  public final boolean isEnabled() {
    return enabled;
  }

  /**
   * Returns true if the node is active. A node is considered active if it meets ALL of the
   * following conditions:
   *
   * <ul>
   *   <li>The node is part of a scene.
   *   <li>the node's parent is active.
   *   <li>The node is enabled.
   * </ul>
   *
   * An active Node has the following behavior:
   *
   * <ul>
   *   <li>The node's {@link #onUpdate(FrameTime)} function will be called every frame.
   *   <li>The node's {@link #getRenderable()} will be rendered.
   *   <li>The node's {@link #getCollisionShape()} will be checked in calls to Scene.hitTest.
   *   <li>The node's {@link #onTouchEvent(HitTestResult, MotionEvent)} function will be called when
   *       the node is touched.
   * </ul>
   *
   * @see #onActivate()
   * @see #onDeactivate()
   * @return the node's active status
   */
  public final boolean isActive() {
    return active;
  }

  /**
   * Registers a callback to be invoked when a touch event is dispatched to this node. The way that
   * touch events are propagated mirrors the way touches are propagated to Android Views. This is
   * only called when the node is active.
   *
   * <p>When an ACTION_DOWN event occurs, that represents the start of a gesture. ACTION_UP or
   * ACTION_CANCEL represents when a gesture ends. When a gesture starts, the following is done:
   *
   * <ul>
   *   <li>Dispatch touch events to the node that was touched as detected by {@link
   *       Scene#hitTest(MotionEvent)}.
   *   <li>If the node doesn't consume the event, recurse upwards through the node's parents and
   *       dispatch the touch event until one of the node's consumes the event.
   *   <li>If no nodes consume the event, the gesture is ignored and subsequent events that are part
   *       of the gesture will not be passed to any nodes.
   *   <li>If one of the node's consumes the event, then that node will consume all future touch
   *       events for the gesture.
   * </ul>
   *
   * When a touch event is dispatched to a node, the event is first passed to the node's {@link
   * OnTouchListener}. If the {@link OnTouchListener} doesn't handle the event, it is passed to
   * {@link #onTouchEvent(HitTestResult, MotionEvent)}.
   *
   * @see OnTouchListener
   */
  public void setOnTouchListener(@Nullable OnTouchListener onTouchListener) {
    this.onTouchListener = onTouchListener;
  }

  /**
   * Registers a callback to be invoked when this node is tapped. If there is a callback registered,
   * then touch events will not bubble to this node's parent. If the Node.onTouchEvent is overridden
   * and super.onTouchEvent is not called, then the tap will not occur.
   *
   * @see OnTapListener
   */
  public void setOnTapListener(@Nullable OnTapListener onTapListener) {
    if (onTapListener != this.onTapListener) {
      tapTrackingData = null;
    }

    this.onTapListener = onTapListener;
  }

  /**
   * Adds a listener that will be called when node lifecycle events occur. The listeners will be
   * called in the order in which they were added.
   */
  public void addLifecycleListener(LifecycleListener lifecycleListener) {
    if (!lifecycleListeners.contains(lifecycleListener)) {
      lifecycleListeners.add(lifecycleListener);
    }
  }

  /** Removes a listener that will be called when node lifecycle events occur. */
  public void removeLifecycleListener(LifecycleListener lifecycleListener) {
    lifecycleListeners.remove(lifecycleListener);
  }

  /** Adds a listener that will be called when the node's transformation changes. */
  public void addTransformChangedListener(TransformChangedListener transformChangedListener) {
    if (!transformChangedListeners.contains(transformChangedListener)) {
      transformChangedListeners.add(transformChangedListener);
    }
  }

  /** Removes a listener that will be called when the node's transformation changes. */
  public void removeTransformChangedListener(TransformChangedListener transformChangedListener) {
    transformChangedListeners.remove(transformChangedListener);
  }

  @Override
  protected final boolean canAddChild(Node child, StringBuilder failureReason) {
    if (!super.canAddChild(child, failureReason)) {
      return false;
    }

    if (isDescendantOf(child)) {
      failureReason.append("Cannot add child: A node's parent cannot be one of its descendants.");
      return false;
    }

    return true;
  }

  @Override
  protected final void onAddChild(Node child) {
    super.onAddChild(child);
    child.parentAsNode = this;
    child.markTransformChangedRecursively(WORLD_DIRTY_FLAGS, child);
    child.setSceneRecursively(scene);
  }

  @Override
  protected final void onRemoveChild(Node child) {
    super.onRemoveChild(child);
    child.parentAsNode = null;
    child.markTransformChangedRecursively(WORLD_DIRTY_FLAGS, child);
    child.setSceneRecursively(null);
  }

  private final void markTransformChangedRecursively(int flagsToMark, Node originatingNode) {
    boolean needsRecursion = false;

    if ((dirtyTransformFlags & flagsToMark) != flagsToMark) {
      dirtyTransformFlags |= flagsToMark;

      if ((dirtyTransformFlags & WORLD_TRANSFORM_DIRTY) == WORLD_TRANSFORM_DIRTY
          && collider != null) {
        collider.markWorldShapeDirty();
      }

      needsRecursion = true;
    }

    if (originatingNode.allowDispatchTransformChangedListeners) {
      dispatchTransformChanged(originatingNode);
      needsRecursion = true;
    }

    if (needsRecursion) {
      // Uses for instead of foreach to avoid unecessary allocations.
      List<Node> children = getChildren();
      for (int i = 0; i < children.size(); i++) {
        Node node = children.get(i);
        node.markTransformChangedRecursively(flagsToMark, originatingNode);
      }
    }
  }

  /**
   * Gets a copy of the nodes position relative to its parent (local-space). If {@link
   * #isTopLevel()} is true, then this is the same as {@link #getWorldPosition()}.
   *
   * @see #setLocalPosition(Vector3)
   * @return a new vector that represents the node's local-space position
   */
  public final Vector3 getLocalPosition() {
    return new Vector3(localPosition);
  }

  /**
   * Gets a copy of the nodes rotation relative to its parent (local-space). If {@link
   * #isTopLevel()} is true, then this is the same as {@link #getWorldRotation()}.
   *
   * @see #setLocalRotation(Quaternion)
   * @return a new quaternion that represents the node's local-space rotation
   */
  public final Quaternion getLocalRotation() {
    return new Quaternion(localRotation);
  }

  /**
   * Gets a copy of the nodes scale relative to its parent (local-space). If {@link #isTopLevel()}
   * is true, then this is the same as {@link #getWorldScale()}.
   *
   * @see #setLocalScale(Vector3)
   * @return a new vector that represents the node's local-space scale
   */
  public final Vector3 getLocalScale() {
    return new Vector3(localScale);
  }

  /**
   * Get a copy of the nodes world-space position.
   *
   * @see #setWorldPosition(Vector3)
   * @return a new vector that represents the node's world-space position
   */
  public final Vector3 getWorldPosition() {
    return new Vector3(getWorldPositionInternal());
  }

  /**
   * Gets a copy of the nodes world-space rotation.
   *
   * @see #setWorldRotation(Quaternion)
   * @return a new quaternion that represents the node's world-space rotation
   */
  public final Quaternion getWorldRotation() {
    return new Quaternion(getWorldRotationInternal());
  }

  /**
   * Gets a copy of the nodes world-space scale. Some precision will be lost if the node is skewed.
   *
   * @see #setWorldScale(Vector3)
   * @return a new vector that represents the node's world-space scale
   */
  public final Vector3 getWorldScale() {
    return new Vector3(getWorldScaleInternal());
  }

  /**
   * Sets the position of this node relative to its parent (local-space). If {@link #isTopLevel()}
   * is true, then this is the same as {@link #setWorldPosition(Vector3)}.
   *
   * @see #getLocalPosition()
   * @param position The position to apply.
   */
  public void setLocalPosition(Vector3 position) {
    Preconditions.checkNotNull(position, "Parameter \"position\" was null.");

    localPosition.set(position);
    markTransformChangedRecursively(LOCAL_DIRTY_FLAGS, this);
  }

  /**
   * Sets the rotation of this node relative to its parent (local-space). If {@link #isTopLevel()}
   * is true, then this is the same as {@link #setWorldRotation(Quaternion)}.
   *
   * @see #getLocalRotation()
   * @param rotation The rotation to apply.
   */
  public void setLocalRotation(Quaternion rotation) {
    Preconditions.checkNotNull(rotation, "Parameter \"rotation\" was null.");

    localRotation.set(rotation);
    markTransformChangedRecursively(LOCAL_DIRTY_FLAGS, this);
  }

  /**
   * Sets the scale of this node relative to its parent (local-space). If {@link #isTopLevel()} is
   * true, then this is the same as {@link #setWorldScale(Vector3)}.
   *
   * @see #getLocalScale()
   * @param scale The scale to apply.
   */
  public void setLocalScale(Vector3 scale) {
    Preconditions.checkNotNull(scale, "Parameter \"scale\" was null.");

    localScale.set(scale);
    markTransformChangedRecursively(LOCAL_DIRTY_FLAGS, this);
  }

  /**
   * Sets the world-space position of this node.
   *
   * @see #getWorldPosition()
   * @param position The position to apply.
   */
  public void setWorldPosition(Vector3 position) {
    Preconditions.checkNotNull(position, "Parameter \"position\" was null.");

    if (parentAsNode == null) {
      localPosition.set(position);
    } else {
      localPosition.set(parentAsNode.worldToLocalPoint(position));
    }

    markTransformChangedRecursively(LOCAL_DIRTY_FLAGS, this);

    // We already know the world position, cache it immediately so we don't
    // need to decompose it.
    cachedWorldPosition.set(position);
    dirtyTransformFlags &= ~WORLD_POSITION_DIRTY;
  }

  /**
   * Sets the world-space rotation of this node.
   *
   * @see #getWorldRotation()
   * @param rotation The rotation to apply.
   */
  public void setWorldRotation(Quaternion rotation) {
    Preconditions.checkNotNull(rotation, "Parameter \"rotation\" was null.");

    if (parentAsNode == null) {
      localRotation.set(rotation);
    } else {
      localRotation.set(
          Quaternion.multiply(parentAsNode.getWorldRotationInternal().inverted(), rotation));
    }

    markTransformChangedRecursively(LOCAL_DIRTY_FLAGS, this);

    // We already know the world rotation, cache it immediately so we don't
    // need to decompose it.
    cachedWorldRotation.set(rotation);
    dirtyTransformFlags &= ~WORLD_ROTATION_DIRTY;
  }

  /**
   * Sets the world-space scale of this node.
   *
   * @see #getWorldScale()
   * @param scale The scale to apply.
   */
  public void setWorldScale(Vector3 scale) {
    Preconditions.checkNotNull(scale, "Parameter \"scale\" was null.");

    if (parentAsNode != null) {
      Node parentAsNode = this.parentAsNode;

      // Compute local matrix with scale = 1.
      // Disallow dispatch transform changed here so we don't send the event multiple times
      // during setWorldScale.
      allowDispatchTransformChangedListeners = false;
      setLocalScale(Vector3.one());
      allowDispatchTransformChangedListeners = true;
      Matrix localModelMatrix = getLocalModelMatrixInternal();

      Matrix.multiply(
          parentAsNode.getWorldModelMatrixInternal(), localModelMatrix, cachedWorldModelMatrix);

      // Both matrices get recomputed, so we can use them as temporary storage.
      Matrix worldS = localModelMatrix;
      worldS.makeScale(scale);

      Matrix inv = cachedWorldModelMatrix;
      Matrix.invert(cachedWorldModelMatrix, inv);

      Matrix.multiply(inv, worldS, inv);
      inv.decomposeScale(localScale);
      setLocalScale(localScale);
    } else {
      setLocalScale(scale);
    }

    // We already know the world scale, cache it immediately so we don't
    // need to decompose it.
    cachedWorldScale.set(scale);
    dirtyTransformFlags &= ~WORLD_SCALE_DIRTY;
  }

  /**
   * Converts a point in the local-space of this node to world-space.
   *
   * @param point the point in local-space to convert
   * @return a new vector that represents the point in world-space
   */
  public final Vector3 localToWorldPoint(Vector3 point) {
    Preconditions.checkNotNull(point, "Parameter \"point\" was null.");

    return getWorldModelMatrixInternal().transformPoint(point);
  }

  /**
   * Converts a point in world-space to the local-space of this node.
   *
   * @param point the point in world-space to convert
   * @return a new vector that represents the point in local-space
   */
  public final Vector3 worldToLocalPoint(Vector3 point) {
    Preconditions.checkNotNull(point, "Parameter \"point\" was null.");

    return getWorldModelMatrixInverseInternal().transformPoint(point);
  }

  /**
   * Converts a direction from the local-space of this node to world-space. Not impacted by the
   * position or scale of the node.
   *
   * @param direction the direction in local-space to convert
   * @return a new vector that represents the direction in world-space
   */
  public final Vector3 localToWorldDirection(Vector3 direction) {
    Preconditions.checkNotNull(direction, "Parameter \"direction\" was null.");

    return Quaternion.rotateVector(getWorldRotationInternal(), direction);
  }

  /**
   * Converts a direction from world-space to the local-space of this node. Not impacted by the
   * position or scale of the node.
   *
   * @param direction the direction in world-space to convert
   * @return a new vector that represents the direction in local-space
   */
  public final Vector3 worldToLocalDirection(Vector3 direction) {
    Preconditions.checkNotNull(direction, "Parameter \"direction\" was null.");

    return Quaternion.inverseRotateVector(getWorldRotationInternal(), direction);
  }

  /**
   * Gets the world-space forward vector (-z) of this node.
   *
   * @return a new vector that represents the node's forward direction in world-space
   */
  public final Vector3 getForward() {
    return localToWorldDirection(Vector3.forward());
  }

  /**
   * Gets the world-space back vector (+z) of this node.
   *
   * @return a new vector that represents the node's back direction in world-space
   */
  public final Vector3 getBack() {
    return localToWorldDirection(Vector3.back());
  }

  /**
   * Gets the world-space right vector (+x) of this node.
   *
   * @return a new vector that represents the node's right direction in world-space
   */
  public final Vector3 getRight() {
    return localToWorldDirection(Vector3.right());
  }

  /**
   * Gets the world-space left vector (-x) of this node.
   *
   * @return a new vector that represents the node's left direction in world-space
   */
  public final Vector3 getLeft() {
    return localToWorldDirection(Vector3.left());
  }

  /**
   * Gets the world-space up vector (+y) of this node.
   *
   * @return a new vector that represents the node's up direction in world-space
   */
  public final Vector3 getUp() {
    return localToWorldDirection(Vector3.up());
  }

  /**
   * Gets the world-space down vector (-y) of this node.
   *
   * @return a new vector that represents the node's down direction in world-space
   */
  public final Vector3 getDown() {
    return localToWorldDirection(Vector3.down());
  }

  /**
   * Sets the {@link Renderable} to display for this node. If {@link
   * Node#setCollisionShape(CollisionShape)} is not set, then {@link Renderable#getCollisionShape()}
   * is used to detect collisions for this {@link Node}.
   *
   * @see ModelRenderable
   * @see com.google.ar.sceneform.rendering.ViewRenderable
   * @param renderable Usually a 3D model. If null, this node's current renderable will be removed.
   */
  public void setRenderable(@Nullable Renderable renderable) {
    AndroidPreconditions.checkUiThread();

    // Renderable hasn't changed, return early.
    if (renderableInstance != null && renderableInstance.getRenderable() == renderable) {
      return;
    }

    if (renderableInstance != null) {
      if (active) {
        renderableInstance.detachFromRenderer();
      }
      renderableInstance = null;
    }

    if (renderable != null) {
      RenderableInstance instance = renderable.createInstance(this);
      if (active && (scene != null && !scene.isUnderTesting())) {
        instance.attachToRenderer(getRendererOrDie());
      }
      renderableInstance = instance;
      renderableId = renderable.getId().get();
    } else {
      renderableId = ChangeId.EMPTY_ID;
    }

    refreshCollider();
  }

  /**
   * Gets the renderable to display for this node.
   *
   * @return renderable to display for this node
   */
  @Nullable
  public Renderable getRenderable() {
    if (renderableInstance == null) {
      return null;
    }

    return renderableInstance.getRenderable();
  }

  /**
   * Sets the shape to used to detect collisions for this {@link Node}. If the shape is not set and
   * {@link Node#setRenderable(Renderable)} is set, then {@link Renderable#getCollisionShape()} is
   * used to detect collisions for this {@link Node}.
   *
   * @see Scene#hitTest(Ray)
   * @see Scene#hitTestAll(Ray)
   * @see Scene#overlapTest(Node)
   * @see Scene#overlapTestAll(Node)
   * @param collisionShape represents a geometric shape, i.e. sphere, box, convex hull. If null,
   *     this node's current collision shape will be removed.
   */
  public void setCollisionShape(@Nullable CollisionShape collisionShape) {
    AndroidPreconditions.checkUiThread();

    this.collisionShape = collisionShape;
    refreshCollider();
  }

  /**
   * Gets the shape to use for collisions with this node. If the shape is null and {@link
   * Node#setRenderable(Renderable)} is set, then {@link Renderable#getCollisionShape()} is used to
   * detect collisions for this {@link Node}.
   *
   * @see Scene#hitTest(Ray)
   * @see Scene#hitTestAll(Ray)
   * @see Scene#overlapTest(Node)
   * @see Scene#overlapTestAll(Node)
   * @return represents a geometric shape, i.e. sphere, box, convex hull.
   */
  @Nullable
  public CollisionShape getCollisionShape() {
    if (collider != null) {
      return collider.getShape();
    }

    return null;
  }

  /**
   * Sets the {@link Light} to display. To use, first create a {@link Light} using {@link
   * Light.Builder}. Set the parameters you care about and then attach it to the node using this
   * function. A node may have a renderable and a light or just act as a {@link Light}.
   *
   * @param light Properties of the {@link Light} to render, pass null to remove the light.
   */
  public void setLight(@Nullable Light light) {
    // If this is the same light already set there is nothing to do.
    if (getLight() == light) {
      return;
    }

    // Null-op if the lightInstance is null
    destroyLightInstance();

    if (light != null) {
      createLightInstance(light);
    }
  }

  /** Gets the current light, which is mutable. */
  @Nullable
  public Light getLight() {
    if (lightInstance != null) {
      return lightInstance.getLight();
    }
    return null;
  }

  /**
   * Sets the direction that the node is looking at in world-space. After calling this, {@link
   * Node#getForward()} will match the look direction passed in. The up direction will determine the
   * orientation of the node around the direction. The look direction and up direction cannot be
   * coincident (parallel) or the orientation will be invalid.
   *
   * @param lookDirection a vector representing the desired look direction in world-space
   * @param upDirection a vector representing a valid up vector to use, such as Vector3.up()
   */
  public final void setLookDirection(Vector3 lookDirection, Vector3 upDirection) {
    final Quaternion rotation = Quaternion.lookRotation(lookDirection, upDirection);
    setWorldRotation(rotation);
  }

  /**
   * Sets the direction that the node is looking at in world-space. After calling this, {@link
   * Node#getForward()} will match the look direction passed in. World-space up (0, 1, 0) will be
   * used to determine the orientation of the node around the direction.
   *
   * @param lookDirection a vector representing the desired look direction in world-space
   */
  public final void setLookDirection(Vector3 lookDirection) {
    // Default up direction
    Vector3 upDirection = Vector3.up();

    // First determine if the look direction and default up direction are far enough apart to
    // produce a numerically stable cross product.
    final float directionUpMatch = Math.abs(Vector3.dot(lookDirection, upDirection));
    if (directionUpMatch > DIRECTION_UP_EPSILON) {
      // If the direction vector and up vector coincide choose a new up vector.
      upDirection = new Vector3(0.0f, 0.0f, 1.0f);
    }

    // Finally build the rotation with the proper up vector.
    setLookDirection(lookDirection, upDirection);
  }

  /** @hide */
  @Override
  public final Matrix getWorldModelMatrix() {
    return getWorldModelMatrixInternal();
  }

  /**
   * Handles when this node becomes active. A Node is active if it's enabled, part of a scene, and
   * its parent is active.
   *
   * <p>Override to perform any setup that needs to occur when the node is activated.
   *
   * @see #isActive()
   * @see #isEnabled()
   */
  public void onActivate() {
    // Optionally override.
  }

  /**
   * Handles when this node becomes inactivate. A Node is inactive if it's disabled, not part of a
   * scene, or its parent is inactive.
   *
   * <p>Override to perform any setup that needs to occur when the node is deactivated.
   *
   * @see #isActive()
   * @see #isEnabled()
   */
  public void onDeactivate() {
    // Optionally override.
  }

  /**
   * Handles when this node is updated. A node is updated before rendering each frame. This is only
   * called when the node is active.
   *
   * <p>Override to perform any updates that need to occur each frame.
   *
   * @param frameTime provides time information for the current frame
   */
  public void onUpdate(FrameTime frameTime) {
    // Optionally override.
  }

  /**
   * Handles when this node is touched.
   *
   * <p>Override to perform any logic that should occur when this node is touched. The way that
   * touch events are propagated mirrors the way touches are propagated to Android Views. This is
   * only called when the node is active.
   *
   * <p>When an ACTION_DOWN event occurs, that represents the start of a gesture. ACTION_UP or
   * ACTION_CANCEL represents when a gesture ends. When a gesture starts, the following is done:
   *
   * <ul>
   *   <li>Dispatch touch events to the node that was touched as detected by {@link
   *       Scene#hitTest(MotionEvent)}.
   *   <li>If the node doesn't consume the event, recurse upwards through the node's parents and
   *       dispatch the touch event until one of the node's consumes the event.
   *   <li>If no nodes consume the event, the gesture is ignored and subsequent events that are part
   *       of the gesture will not be passed to any nodes.
   *   <li>If one of the node's consumes the event, then that node will consume all future touch
   *       events for the gesture.
   * </ul>
   *
   * When a touch event is dispatched to a node, the event is first passed to the node's {@link
   * OnTouchListener}. If the {@link OnTouchListener} doesn't handle the event, it is passed to
   * {@link #onTouchEvent(HitTestResult, MotionEvent)}.
   *
   * @param hitTestResult Represents the node that was touched, and information about where it was
   *     touched. On ACTION_DOWN events, {@link HitTestResult#getNode()} will always be this node or
   *     one of its children. On other events, the touch may have moved causing the {@link
   *     HitTestResult#getNode()} to change (or possibly be null).
   * @param motionEvent The motion event.
   * @return True if the event was handled, false otherwise.
   */
  public boolean onTouchEvent(HitTestResult hitTestResult, MotionEvent motionEvent) {
    Preconditions.checkNotNull(hitTestResult, "Parameter \"hitTestResult\" was null.");
    Preconditions.checkNotNull(motionEvent, "Parameter \"motionEvent\" was null.");

    boolean handled = false;

    // Reset tap tracking data if a new gesture has started or if the Node has become inactive.
    int actionMasked = motionEvent.getActionMasked();
    if (actionMasked == MotionEvent.ACTION_DOWN || !isActive()) {
      tapTrackingData = null;
    }

    switch (actionMasked) {
      case MotionEvent.ACTION_DOWN:
        // Only start tacking the tap gesture if there is a tap listener set.
        // This allows the event to bubble up to the node's parent when there is no listener.
        if (onTapListener == null) {
          break;
        }

        Node hitNode = hitTestResult.getNode();
        if (hitNode == null) {
          break;
        }

        Vector3 downPosition = new Vector3(motionEvent.getX(), motionEvent.getY(), 0.0f);
        tapTrackingData = new TapTrackingData(hitNode, downPosition);
        handled = true;
        break;
        // For both ACTION_MOVE and ACTION_UP, we need to make sure the tap gesture is still valid.
      case MotionEvent.ACTION_MOVE:
      case MotionEvent.ACTION_UP:
        // Assign to local variable for static analysis.
        TapTrackingData tapTrackingData = this.tapTrackingData;
        if (tapTrackingData == null) {
          break;
        }

        // Determine how much the touch has moved.
        float touchSlop = getScaledTouchSlop();
        Vector3 upPosition = new Vector3(motionEvent.getX(), motionEvent.getY(), 0.0f);
        float touchDelta = Vector3.subtract(tapTrackingData.downPosition, upPosition).length();

        // Determine if this node or a child node is still being touched.
        hitNode = hitTestResult.getNode();
        boolean isHitValid = hitNode == tapTrackingData.downNode;

        // Determine if this is a valid tap.
        boolean isTapValid = isHitValid || touchDelta < touchSlop;
        if (isTapValid) {
          handled = true;
          // If this is an ACTION_UP event, it's time to call the listener.
          if (actionMasked == MotionEvent.ACTION_UP && onTapListener != null) {
            onTapListener.onTap(hitTestResult, motionEvent);
            this.tapTrackingData = null;
          }
        } else {
          this.tapTrackingData = null;
        }
        break;
      default:
        // Do nothing.
    }

    return handled;
  }

  /**
   * Handles when this node's transformation is changed.
   *
   * <p>The originating node is the most top-level node in the hierarchy that triggered this node to
   * change. It will always be either the same node or one of its' parents. i.e. if node A's
   * position is changed, then that will trigger {@link #onTransformChange(Node)} to be called for
   * all of it's children with the originatingNode being node A.
   *
   * @param originatingNode the node that triggered this node's transformation to change
   */
  public void onTransformChange(Node originatingNode) {
    // Optionally Override.
  }

  /**
   * Traverses the hierarchy and call a method on each node (including this node). Traversal is
   * depth first.
   *
   * @param consumer the method to call on each node
   */
  @SuppressWarnings("AndroidApiChecker")
  @Override
  public void callOnHierarchy(Consumer<Node> consumer) {
    consumer.accept(this);
    super.callOnHierarchy(consumer);
  }

  /**
   * Traverses the hierarchy to find the first node (including this node) that meets a condition.
   * Once the predicate is met, the traversal stops. Traversal is depth first.
   *
   * @param condition predicate the defines the conditions of the node to search for.
   * @return the first node that matches the conditions of the predicate, otherwise null is returned
   */
  @SuppressWarnings("AndroidApiChecker")
  @Override
  @Nullable
  public Node findInHierarchy(Predicate<Node> condition) {
    if (condition.test(this)) {
      return this;
    }

    return super.findInHierarchy(condition);
  }

  @Override
  public String toString() {
    return name + "(" + super.toString() + ")";
  }

  /** Returns the parent of this node. */
  @Nullable
  final NodeParent getNodeParent() {
    return parent;
  }

  @Nullable
  final Collider getCollider() {
    return collider;
  }

  int getNameHash() {
    return nameHash;
  }

  /**
   * Calls onUpdate if the node is active. Used by SceneView to dispatch updates.
   *
   * @param frameTime provides time information for the current frame
   */
  final void dispatchUpdate(FrameTime frameTime) {
    if (!isActive()) {
      return;
    }

    // Update state when the renderable has changed.
    Renderable renderable = getRenderable();
    if (renderable != null && renderable.getId().checkChanged(renderableId)) {
      // Refresh the collider to ensure it is using the correct collision shape now that the
      // renderable has changed.
      refreshCollider();
      renderableId = renderable.getId().get();
    }

    onUpdate(frameTime);

    for (LifecycleListener lifecycleListener : lifecycleListeners) {
      lifecycleListener.onUpdated(this, frameTime);
    }
  }

  /**
   * Calls onTouchEvent if the node is active. Used by TouchEventSystem to dispatch touch events.
   *
   * @param hitTestResult Represents the node that was touched, and information about where it was
   *     touched. On ACTION_DOWN events, {@link HitTestResult#getNode()} will always be this node or
   *     one of its children. On other events, the touch may have moved causing the {@link
   *     HitTestResult#getNode()} to change (or possibly be null).
   * @param motionEvent The motion event.
   * @return True if the event was handled, false otherwise.
   */
  boolean dispatchTouchEvent(HitTestResult hitTestResult, MotionEvent motionEvent) {
    Preconditions.checkNotNull(hitTestResult, "Parameter \"hitTestResult\" was null.");
    Preconditions.checkNotNull(motionEvent, "Parameter \"motionEvent\" was null.");

    if (!isActive()) {
      return false;
    }

    // TODO: It feels wrong to give Node direct knowledge of Views/ViewRenderable.
    // It also feels wrong to have a 'Renderable' receive touch events. This hints at a larger
    // API
    // problem of Renderable representing more than just rendering information (we have this
    // problem
    // with collision shapes too). Investigate a way to refactor this.
    if (dispatchToViewRenderable(motionEvent)) {
      return true;
    }

    if (onTouchListener != null && onTouchListener.onTouch(hitTestResult, motionEvent)) {
      return true;
    }

    return onTouchEvent(hitTestResult, motionEvent);
  }

  
  private boolean dispatchToViewRenderable(MotionEvent motionEvent) {
    return ViewTouchHelpers.dispatchTouchEventToView(this, motionEvent);
  }

  /**
   * WARNING: Do not call this function directly unless you know what you are doing. Sets the scene
   * field and propagates it to all children recursively. this is called automatically when the node
   * is added/removed from the scene or its parent changes.
   *
   * @param scene The scene to set. If null, the scene is set to null.
   */
  final void setSceneRecursively(@Nullable Scene scene) {
    AndroidPreconditions.checkUiThread();

    // First, set the scene of this node and all child nodes.
    setSceneRecursivelyInternal(scene);

    // Then, recursively update the active status of this node and all child nodes.
    updateActiveStatusRecursively();
  }

  














  // TODO: Gltf animation api should be consistent with Sceneform.
  @Nullable
  public RenderableInstance getRenderableInstance() {
    return renderableInstance;
  }

  Matrix getLocalModelMatrixInternal() {
    if ((dirtyTransformFlags & LOCAL_TRANSFORM_DIRTY) == LOCAL_TRANSFORM_DIRTY) {
      cachedLocalModelMatrix.makeTrs(localPosition, localRotation, localScale);
      dirtyTransformFlags &= ~LOCAL_TRANSFORM_DIRTY;
    }

    return cachedLocalModelMatrix;
  }

  Matrix getWorldModelMatrixInverseInternal() {
    if ((dirtyTransformFlags & WORLD_INVERSE_TRANSFORM_DIRTY) == WORLD_INVERSE_TRANSFORM_DIRTY) {
      // Cache the inverse of the world model matrix.
      // Used for converting from world-space to local-space.
      Matrix.invert(getWorldModelMatrixInternal(), cachedWorldModelMatrixInverse);
      dirtyTransformFlags &= ~WORLD_INVERSE_TRANSFORM_DIRTY;
    }

    return cachedWorldModelMatrixInverse;
  }

  private void setSceneRecursivelyInternal(@Nullable Scene scene) {
    this.scene = scene;
    for (Node node : getChildren()) {
      node.setSceneRecursively(scene);
    }
  }

  private void updateActiveStatusRecursively() {
    final boolean shouldBeActive = shouldBeActive();
    if (active != shouldBeActive) {
      if (shouldBeActive) {
        activate();
      } else {
        deactivate();
      }
    }

    for (Node node : getChildren()) {
      node.updateActiveStatusRecursively();
    }
  }

  private boolean shouldBeActive() {
    if (!enabled) {
      return false;
    }

    if (scene == null) {
      return false;
    }

    if (parentAsNode != null && !parentAsNode.isActive()) {
      return false;
    }

    return true;
  }

  private void activate() {
    AndroidPreconditions.checkUiThread();

    if (active) {
      // This should NEVER be thrown because updateActiveStatusRecursively checks to make sure
      // that the active status has changed before calling this. If this exception is thrown, a bug
      // was introduced.
      throw new AssertionError("Cannot call activate while already active.");
    }

    active = true;

    if ((scene != null && !scene.isUnderTesting()) && renderableInstance != null) {
      renderableInstance.attachToRenderer(getRendererOrDie());
    }

    if (lightInstance != null) {
      lightInstance.attachToRenderer(getRendererOrDie());
    }

    if (collider != null && scene != null) {
      collider.setAttachedCollisionSystem(scene.collisionSystem);
    }

    onActivate();

    for (LifecycleListener lifecycleListener : lifecycleListeners) {
      lifecycleListener.onActivated(this);
    }
  }

  private void deactivate() {
    AndroidPreconditions.checkUiThread();

    if (!active) {
      // This should NEVER be thrown because updateActiveStatusRecursively checks to make sure
      // that the active status has changed before calling this. If this exception is thrown, a bug
      // was introduced.
      throw new AssertionError("Cannot call deactivate while already inactive.");
    }

    active = false;

    if (renderableInstance != null) {
      renderableInstance.detachFromRenderer();
    }

    if (lightInstance != null) {
      lightInstance.detachFromRenderer();
    }

    if (collider != null) {
      collider.setAttachedCollisionSystem(null);
    }

    onDeactivate();

    for (LifecycleListener lifecycleListener : lifecycleListeners) {
      lifecycleListener.onDeactivated(this);
    }
  }

  private void dispatchTransformChanged(Node originatingNode) {
    onTransformChange(originatingNode);

    for (int i = 0; i < transformChangedListeners.size(); i++) {
      transformChangedListeners.get(i).onTransformChanged(this, originatingNode);
    }
  }

  private void refreshCollider() {
    CollisionShape finalCollisionShape = collisionShape;

    // If no collision shape has been set, fall back to the collision shape from the renderable, if
    // there is a renderable.
    Renderable renderable = getRenderable();
    if (finalCollisionShape == null && renderable != null) {
      finalCollisionShape = renderable.getCollisionShape();
    }

    if (finalCollisionShape != null) {
      // Create the collider if it doesn't already exist.
      if (collider == null) {
        collider = new Collider(this, finalCollisionShape);

        // Attach the collider to the collision system if the node is already active.
        if (active && scene != null) {
          collider.setAttachedCollisionSystem(scene.collisionSystem);
        }
      } else if (collider.getShape() != finalCollisionShape) {
        // Set the collider's shape to the new shape if needed.
        collider.setShape(finalCollisionShape);
      }
    } else if (collider != null) {
      // Dispose of the old collider.
      collider.setAttachedCollisionSystem(null);
      collider = null;
    }
  }

  private int getScaledTouchSlop() {
    Scene scene = getScene();
    if (scene == null
        || !AndroidPreconditions.isAndroidApiAvailable()
        || AndroidPreconditions.isUnderTesting()) {
      return DEFAULT_TOUCH_SLOP;
    }

    SceneView view = scene.getView();
    ViewConfiguration viewConfiguration = ViewConfiguration.get(view.getContext());
    return viewConfiguration.getScaledTouchSlop();
  }

  private Matrix getWorldModelMatrixInternal() {
    if ((dirtyTransformFlags & WORLD_TRANSFORM_DIRTY) == WORLD_TRANSFORM_DIRTY) {
      if (parentAsNode == null) {
        cachedWorldModelMatrix.set(getLocalModelMatrixInternal().data);
      } else {
        Matrix.multiply(
            parentAsNode.getWorldModelMatrixInternal(),
            getLocalModelMatrixInternal(),
            cachedWorldModelMatrix);
      }

      dirtyTransformFlags &= ~WORLD_TRANSFORM_DIRTY;
    }

    return cachedWorldModelMatrix;
  }

  /**
   * Internal Convenience function for accessing cachedWorldPosition that ensures the cached value
   * is updated before it is accessed. Used internally instead of getWorldPosition because
   * getWorldPosition is written to be immutable and therefore requires allocating a new Vector for
   * each use.
   *
   * @return The cachedWorldPosition.
   */
  private Vector3 getWorldPositionInternal() {
    if ((dirtyTransformFlags & WORLD_POSITION_DIRTY) == WORLD_POSITION_DIRTY) {
      if (parentAsNode != null) {
        getWorldModelMatrixInternal().decomposeTranslation(cachedWorldPosition);
      } else {
        cachedWorldPosition.set(localPosition);
      }
      dirtyTransformFlags &= ~WORLD_POSITION_DIRTY;
    }

    return cachedWorldPosition;
  }

  /**
   * Internal Convenience function for accessing cachedWorldRotation that ensures the cached value
   * is updated before it is accessed. Used internally instead of getWorldRotation because
   * getWorldRotation is written to be immutable and therefore requires allocating a new Quaternion
   * for each use.
   *
   * @return The cachedWorldRotation.
   */
  private Quaternion getWorldRotationInternal() {
    if ((dirtyTransformFlags & WORLD_ROTATION_DIRTY) == WORLD_ROTATION_DIRTY) {
      if (parentAsNode != null) {
        getWorldModelMatrixInternal()
            .decomposeRotation(getWorldScaleInternal(), cachedWorldRotation);
      } else {
        cachedWorldRotation.set(localRotation);
      }
      dirtyTransformFlags &= ~WORLD_ROTATION_DIRTY;
    }

    return cachedWorldRotation;
  }

  /**
   * Internal Convenience function for accessing cachedWorldScale that ensures the cached value is
   * updated before it is accessed. Used internally instead of getWorldScale because getWorldScale
   * is written to be immutable and therefore requires allocating a new Vector3 for each use.
   *
   * @return The cachedWorldScale.
   */
  private Vector3 getWorldScaleInternal() {
    if ((dirtyTransformFlags & WORLD_SCALE_DIRTY) == WORLD_SCALE_DIRTY) {
      if (parentAsNode != null) {
        getWorldModelMatrixInternal().decomposeScale(cachedWorldScale);
      } else {
        cachedWorldScale.set(localScale);
      }
      dirtyTransformFlags &= ~WORLD_SCALE_DIRTY;
    }

    return cachedWorldScale;
  }

  private void createLightInstance(Light light) {
    lightInstance = light.createInstance(this);
    if (lightInstance == null) {
      throw new NullPointerException("light.createInstance() failed - which should not happen.");
    }
    if (active) {
      lightInstance.attachToRenderer(getRendererOrDie());
    }
  }

  private void destroyLightInstance() {
    // If the light instance is already null, then there is nothing to do so just return.
    if (lightInstance == null) {
      return;
    }

    if (active) {
      lightInstance.detachFromRenderer();
    }
    lightInstance.dispose();
    lightInstance = null;
  }

  private Renderer getRendererOrDie() {
    if (scene == null) {
      throw new IllegalStateException("Unable to get Renderer.");
    }

    return Preconditions.checkNotNull(scene.getView().getRenderer());
  }
}
