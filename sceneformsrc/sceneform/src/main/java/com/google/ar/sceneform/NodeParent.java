package com.google.ar.sceneform;

import android.support.annotation.CallSuper;
import android.support.annotation.Nullable;
import com.google.ar.sceneform.utilities.AndroidPreconditions;
import com.google.ar.sceneform.utilities.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Base class for all classes that can contain a set of nodes as children.
 *
 * <p>The classes {@link Node} and {@link Scene} are both NodeParents. To make a {@link Node} the
 * child of another {@link Node} or a {@link Scene}, use {@link Node#setParent(NodeParent)}.
 */
public abstract class NodeParent {
  private final ArrayList<Node> children = new ArrayList<>();
  private final List<Node> unmodifiableChildren = Collections.unmodifiableList(children);

  // List of children that can be iterated over
  private final ArrayList<Node> iterableChildren = new ArrayList<>();

  // True if the list of children has changed since the last time iterableChildren was updated.
  private boolean isIterableChildrenDirty;

  // Used to track if the list of iterableChildren is currently being iterated over.
  // This is an integer instead of a boolean to handle re-entrance (iteration inside of iteration).
  private int iteratingCounter;

  /** Returns an immutable list of this parent's children. */
  public final List<Node> getChildren() {
    return unmodifiableChildren;
  }

  /**
   * Adds a node as a child of this NodeParent. If the node already has a parent, it is removed from
   * its old parent. If the node is already a direct child of this NodeParent, no change is made.
   *
   * @param child the node to add as a child
   * @throws IllegalArgumentException if the child is the same object as the parent, or if the
   *     parent is a descendant of the child
   */
  public final void addChild(Node child) {
    Preconditions.checkNotNull(child, "Parameter \"child\" was null.");
    AndroidPreconditions.checkUiThread();

    // Return early if the parent hasn't changed.
    if (child.parent == this) {
      return;
    }

    StringBuilder failureReason = new StringBuilder();
    if (!canAddChild(child, failureReason)) {
      throw new IllegalArgumentException(failureReason.toString());
    }

    onAddChild(child);
  }

  /**
   * Removes a node from the children of this NodeParent. If the node is not a direct child of this
   * NodeParent, no change is made.
   *
   * @param child the node to remove from the children
   */
  public final void removeChild(Node child) {
    Preconditions.checkNotNull(child, "Parameter \"child\" was null.");
    AndroidPreconditions.checkUiThread();

    // Return early if this parent doesn't contain the child.
    if (!children.contains(child)) {
      return;
    }

    onRemoveChild(child);
  }

  /**
   * Traverse the hierarchy and call a method on each node. Traversal is depth first. If this
   * NodeParent is a Node, traversal starts with this NodeParent, otherwise traversal starts with
   * its children.
   *
   * @param consumer The method to call on each node.
   */
  @SuppressWarnings("AndroidApiChecker")
  public void callOnHierarchy(Consumer<Node> consumer) {
    Preconditions.checkNotNull(consumer, "Parameter \"consumer\" was null.");

    ArrayList<Node> iterableChildren = getIterableChildren();
    startIterating();
    for (int i = 0; i < iterableChildren.size(); i++) {
      Node child = iterableChildren.get(i);
      child.callOnHierarchy(consumer);
    }
    stopIterating();
  }

  /**
   * Traverse the hierarchy to find the first node that meets a condition. Traversal is depth first.
   * If this NodeParent is a Node, traversal starts with this NodeParent, otherwise traversal starts
   * with its children.
   *
   * @param condition predicate the defines the conditions of the node to search for.
   * @return the first node that matches the conditions of the predicate, otherwise null is returned
   */
  @SuppressWarnings("AndroidApiChecker")
  @Nullable
  public Node findInHierarchy(Predicate<Node> condition) {
    Preconditions.checkNotNull(condition, "Parameter \"condition\" was null.");

    ArrayList<Node> iterableChildren = getIterableChildren();
    Node found = null;
    startIterating();
    for (int i = 0; i < iterableChildren.size(); i++) {
      Node child = iterableChildren.get(i);
      found = child.findInHierarchy(condition);
      if (found != null) {
        break;
      }
    }
    stopIterating();
    return found;
  }

  /**
   * Traverse the hierarchy to find the first node with a given name. Traversal is depth first. If
   * this NodeParent is a Node, traversal starts with this NodeParent, otherwise traversal starts
   * with its children.
   *
   * @param name The name of the node to find
   * @return the node if it's found, otherwise null
   */
  @SuppressWarnings("AndroidApiChecker")
  @Nullable
  public Node findByName(String name) {
    if (name == null || name.isEmpty()) {
      return null;
    }

    int hashToFind = name.hashCode();
    Node found =
        findInHierarchy(
            (node) -> {
              String nodeName = node.getName();
              return (node.getNameHash() != 0 && node.getNameHash() == hashToFind)
                  || (nodeName != null && nodeName.equals(name));
            });

    return found;
  }

  protected boolean canAddChild(Node child, StringBuilder failureReason) {
    Preconditions.checkNotNull(child, "Parameter \"child\" was null.");
    Preconditions.checkNotNull(failureReason, "Parameter \"failureReason\" was null.");

    if (child == this) {
      failureReason.append("Cannot add child: Cannot make a node a child of itself.");
      return false;
    }

    return true;
  }

  @CallSuper
  protected void onAddChild(Node child) {
    Preconditions.checkNotNull(child, "Parameter \"child\" was null.");

    NodeParent previousParent = child.getNodeParent();
    if (previousParent != null) {
      previousParent.removeChild(child);
    }

    children.add(child);
    child.parent = this;

    isIterableChildrenDirty = true;
  }

  @CallSuper
  protected void onRemoveChild(Node child) {
    Preconditions.checkNotNull(child, "Parameter \"child\" was null.");

    children.remove(child);
    child.parent = null;

    isIterableChildrenDirty = true;
  }

  private ArrayList<Node> getIterableChildren() {
    if (isIterableChildrenDirty && !isIterating()) {
      iterableChildren.clear();
      iterableChildren.addAll(children);
      isIterableChildrenDirty = false;
    }

    return iterableChildren;
  }

  private void startIterating() {
    iteratingCounter++;
  }

  private void stopIterating() {
    iteratingCounter--;
    if (iteratingCounter < 0) {
      throw new AssertionError("stopIteration was called without calling startIteration.");
    }
  }

  private boolean isIterating() {
    return iteratingCounter > 0;
  }
}
