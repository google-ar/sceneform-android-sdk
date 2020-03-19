package com.google.ar.sceneform;

import android.support.annotation.ColorInt;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.Color;
import com.google.ar.sceneform.rendering.Light;
import com.google.ar.sceneform.utilities.EnvironmentalHdrParameters;
import com.google.ar.sceneform.utilities.Preconditions;

/**
 * Represents the "sun" - the default directional light in the scene.
 *
 * <p>The following method will throw {@link UnsupportedOperationException} when called: {@link
 * #setParent(NodeParent)} - Sunlight's parent cannot be changed, it is always the scene.
 *
 * <p>All other functionality in Node is supported. You can access the position and rotation of the
 * sun, assign a collision shape to the sun, or add children to the sun. Disabling the sun turns off
 * the default directional light.
 */
public class Sun extends Node {
  @ColorInt static final int DEFAULT_SUNLIGHT_COLOR = 0xfff2d3c4;
  static final Vector3 DEFAULT_SUNLIGHT_DIRECTION = new Vector3(0.7f, -1.0f, -0.8f);

  // The Light estimate scale and offset allow the final change in intensity to be controlled to
  // avoid over darkening or changes that are too drastic: appliedEstimate = estimate*scale + offset
  private static final float LIGHT_ESTIMATE_SCALE = 1.8f;
  private static final float LIGHT_ESTIMATE_OFFSET = 0.0f;
  private float baseIntensity = 0.0f;

  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  Sun() {}

  @SuppressWarnings("initialization") // Suppress @UnderInitialization warning.
  Sun(Scene scene) {
    super();
    Preconditions.checkNotNull(scene, "Parameter \"scene\" was null.");
    super.setParent(scene);

    setupDefaultLighting(scene.getView());
  }

  @Override
  public void setParent(@Nullable NodeParent parent) {
    throw new UnsupportedOperationException(
        "Sun's parent cannot be changed, it is always the scene.");
  }

  /**
   * Applies the Environmental HDR light estimate to the directional light
   *
   * <p>The exposure used here is calculated as 1.0f / (1.2f * aperture^2 / shutter speed * 100.0f /
   * iso);
   *
   * @param direction directional light orientation as returned from light estimation.
   * @param color relative color returned from light estimation.
   * @param environmentalHdrIntensity maximum intensity from light estimation.
   * @param exposure Exposure value from Filament.
   * @hide intended for use by other Sceneform packages which update Hdr lighting every frame.
   */
  
  void setEnvironmentalHdrLightEstimate(
      float[] direction,
      Color color,
      float environmentalHdrIntensity,
      float exposure,
      EnvironmentalHdrParameters environmentalHdrParameters) {
    Light light = getLight();
    if (light == null) {
      return;
    }

    // Convert from Environmetal hdr's relative value to lux for filament using hard coded value.
    float filamentIntensity =
        environmentalHdrIntensity
            * environmentalHdrParameters.getDirectIntensityForFilament()
            / exposure;

    light.setColor(color);
    light.setIntensity(filamentIntensity);

    // If light is detected as shining up from below, we flip the Y component so that we always end
    // up with a shadow on the ground to fulfill UX requirements.
    Vector3 lookDirection =
        new Vector3(-direction[0], -Math.abs(direction[1]), -direction[2]).normalized();
    Quaternion lookRotation = Quaternion.rotationBetweenVectors(Vector3.forward(), lookDirection);
    setWorldRotation(lookRotation);
  }

  void setLightEstimate(Color colorCorrection, float pixelIntensity) {
    Light light = getLight();
    if (light == null) {
      return;
    }

    // If we don't know the base intensity of the light, get it now.
    if (baseIntensity == 0.0f) {
      baseIntensity = light.getIntensity();
    }

    // Scale and bias the estimate to avoid over darkening.
    float lightIntensity =
        baseIntensity
            * Math.min(pixelIntensity * LIGHT_ESTIMATE_SCALE + LIGHT_ESTIMATE_OFFSET, 1.0f);

    // Modulates sun color by color correction.
    Color lightColor = new Color(DEFAULT_SUNLIGHT_COLOR);
    lightColor.r *= colorCorrection.r;
    lightColor.g *= colorCorrection.g;
    lightColor.b *= colorCorrection.b;

    // Modifies light color and intensity by light estimate.
    light.setColor(lightColor);
    light.setIntensity(lightIntensity);
  }

  @SuppressWarnings("initialization") // Suppress @UnderInitialization warning.
  private void setupDefaultLighting(SceneView view) {
    Preconditions.checkNotNull(view, "Parameter \"view\" was null.");

    final Color sunlightColor = new Color(DEFAULT_SUNLIGHT_COLOR);
    if (sunlightColor == null) {
      throw new AssertionError("Sunlight color is null.");
    }

    // Set the Node direction to point the sunlight in the desired direction.
    setLookDirection(DEFAULT_SUNLIGHT_DIRECTION.normalized());

    // Create and set the directional light.
    Light sunlight =
        Light.builder(Light.Type.DIRECTIONAL)
            .setColor(sunlightColor)
            .setShadowCastingEnabled(true)
            .build();

    if (sunlight == null) {
      throw new AssertionError("Failed to create the default sunlight.");
    }
    this.setLight(sunlight);
  }
}
