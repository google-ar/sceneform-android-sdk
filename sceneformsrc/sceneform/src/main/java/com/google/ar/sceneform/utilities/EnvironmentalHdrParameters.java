package com.google.ar.sceneform.utilities;

/**
 * Provides scaling factors from Environmental Hdr to Filament.
 *
 * <p>A conversion is required to convert between Environmental Hdr units and an intensity value
 * Filament can use.
 *
 * @hide This class may be removed eventually.
 */
// TODO: Replace each of these values with principled numbers.
public class EnvironmentalHdrParameters {
  public static final float DEFAULT_AMBIENT_SH_SCALE_FOR_FILAMENT = 1.0f;
  public static final float DEFAULT_DIRECT_INTENSITY_FOR_FILAMENT = 1.0f;
  public static final float DEFAULT_REFLECTION_SCALE_FOR_FILAMENT = 1.0f;

  /** Builds ViewerConfig, a collection of runtime config options for the viewer. */
  public static class Builder {
    public Builder() {}

    public EnvironmentalHdrParameters build() {
      return new EnvironmentalHdrParameters(this);
    }

    /** Conversion factor for directional lighting. */
    public Builder setDirectIntensityForFilament(float directIntensityForFilament) {
      this.directIntensityForFilament = directIntensityForFilament;
      return this;
    }

    /** Conversion factor for ambient spherical harmonics. */
    public Builder setAmbientShScaleForFilament(float ambientShScaleForFilament) {
      this.ambientShScaleForFilament = ambientShScaleForFilament;
      return this;
    }

    /** Conversion factor for reflections. */
    public Builder setReflectionScaleForFilament(float reflectionScaleForFilament) {
      this.reflectionScaleForFilament = reflectionScaleForFilament;
      return this;
    }

    private float ambientShScaleForFilament;
    private float directIntensityForFilament;
    private float reflectionScaleForFilament;
  }

  /** Constructs a builder, all required fields must be specified. */
  public static EnvironmentalHdrParameters.Builder builder() {
    return new EnvironmentalHdrParameters.Builder();
  }

  public static EnvironmentalHdrParameters makeDefault() {
    return builder()
        .setAmbientShScaleForFilament(DEFAULT_AMBIENT_SH_SCALE_FOR_FILAMENT)
        .setDirectIntensityForFilament(DEFAULT_DIRECT_INTENSITY_FOR_FILAMENT)
        .setReflectionScaleForFilament(DEFAULT_REFLECTION_SCALE_FOR_FILAMENT)
        .build();
  }

  private EnvironmentalHdrParameters(Builder builder) {
    ambientShScaleForFilament = builder.ambientShScaleForFilament;
    directIntensityForFilament = builder.directIntensityForFilament;
    reflectionScaleForFilament = builder.reflectionScaleForFilament;
  }

  /**
   * A scale factor bridging Environmental Hdr's ambient sh to Filament's ambient sh values.
   *
   * <p>This number has been hand tuned by comparing lighting to reference app
   * /third_party/arcore/unity/apps/whitebox
   */
  public float getAmbientShScaleForFilament() {
    return ambientShScaleForFilament;
  }

  /** Environmental Hdr provides a relative intensity, a number above zero and often below 8. */
  public float getDirectIntensityForFilament() {
    return directIntensityForFilament;
  }

  /**
   * A scale factor bridging Environmental Hdr's relative intensity to a lux based intensity for
   * reflections only.
   */
  public float getReflectionScaleForFilament() {
    return reflectionScaleForFilament;
  }

  private final float ambientShScaleForFilament;
  private final float directIntensityForFilament;
  private final float reflectionScaleForFilament;
}
