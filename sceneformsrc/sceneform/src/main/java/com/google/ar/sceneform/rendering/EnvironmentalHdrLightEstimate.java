package com.google.ar.sceneform.rendering;

import android.media.Image;
import android.media.Image.Plane;
import android.support.annotation.Nullable;
import com.google.ar.core.annotations.UsedByReflection;
import java.io.Serializable;
import java.nio.ByteBuffer;

/**
 * Serialization structure for saving light estimate state for offline use, e.g. in tests.
 *
 * @hide
 */
public class EnvironmentalHdrLightEstimate implements Serializable {
  @UsedByReflection("EnvironmentalHdrLightEstimate.java")
  static class CubeMapImage implements Serializable {
    @UsedByReflection("EnvironmentalHdrLightEstimate.java")
    static class CubeMapPlane implements Serializable {
      @UsedByReflection("EnvironmentalHdrLightEstimate.java")
      final int pixelStride;

      @UsedByReflection("EnvironmentalHdrLightEstimate.java")
      final int rowStride;

      @UsedByReflection("EnvironmentalHdrLightEstimate.java")
      final byte[] bytes;

      public CubeMapPlane(Plane plane) {
        ByteBuffer rgbaBuffer = plane.getBuffer();
        bytes = new byte[rgbaBuffer.remaining()];
        rgbaBuffer.get(bytes);
        pixelStride = plane.getPixelStride();
        rowStride = plane.getRowStride();
      }
    }

    @UsedByReflection("EnvironmentalHdrLightEstimate.java")
    final int format;

    @UsedByReflection("EnvironmentalHdrLightEstimate.java")
    final CubeMapPlane[] planes;

    @UsedByReflection("EnvironmentalHdrLightEstimate.java")
    final int height;

    @UsedByReflection("EnvironmentalHdrLightEstimate.java")
    final int width;

    @UsedByReflection("EnvironmentalHdrLightEstimate.java")
    final long timestamp;

    CubeMapImage(Image image) {
      format = image.getFormat();
      Plane[] imagePlanes = image.getPlanes();
      planes = new CubeMapPlane[imagePlanes.length];
      for (int i = 0; i < imagePlanes.length; ++i) {
        planes[i] = new CubeMapPlane(imagePlanes[i]);
      }
      height = image.getHeight();
      width = image.getWidth();
      timestamp = image.getTimestamp();
    }
  }

  @UsedByReflection("EnvironmentalHdrLightEstimate.java")
  @Nullable
  private final float[] sphericalHarmonics;

  @UsedByReflection("EnvironmentalHdrLightEstimate.java")
  @Nullable
  private final float[] direction;

  @UsedByReflection("EnvironmentalHdrLightEstimate.java")
  private final float colorR;

  @UsedByReflection("EnvironmentalHdrLightEstimate.java")
  private final float colorG;

  @UsedByReflection("EnvironmentalHdrLightEstimate.java")
  private final float colorB;

  @UsedByReflection("EnvironmentalHdrLightEstimate.java")
  private final float colorA;

  @UsedByReflection("EnvironmentalHdrLightEstimate.java")
  private final float relativeIntensity;

  @UsedByReflection("EnvironmentalHdrLightEstimate.java")
  @Nullable
  private final CubeMapImage[] cubeMap;

  // incompatible types in argument.
  // incompatible types in assignment.
  @SuppressWarnings({
    "nullness:argument.type.incompatible",
    "nullness:assignment.type.incompatible"
  })
  public EnvironmentalHdrLightEstimate(
      @Nullable float[] sphericalHarmonics,
      @Nullable float[] direction,
      Color colorCorrection,
      float relativeIntensity,
      @Nullable Image[] cubeMap) {
    if (sphericalHarmonics != null) {
      this.sphericalHarmonics = new float[sphericalHarmonics.length];
      System.arraycopy(
          sphericalHarmonics, 0, this.sphericalHarmonics, 0, sphericalHarmonics.length);
    } else {
      this.sphericalHarmonics = null;
    }
    if (direction != null) {
      this.direction = new float[direction.length];
      System.arraycopy(direction, 0, this.direction, 0, direction.length);
    } else {
      this.direction = null;
    }
    colorR = colorCorrection.r;
    colorG = colorCorrection.g;
    colorB = colorCorrection.b;
    colorA = colorCorrection.a;
    this.relativeIntensity = relativeIntensity;
    if (cubeMap != null) {
      this.cubeMap = new CubeMapImage[cubeMap.length];
      for (int i = 0; i < cubeMap.length; ++i) {
        this.cubeMap[i] = new CubeMapImage(cubeMap[i]);
      }
    } else {
      this.cubeMap = null;
    }
  }

  @Nullable
  public float[] getSphericalHarmonics() {
    return sphericalHarmonics;
  }

  @Nullable
  public float[] getDirection() {
    return direction;
  }

  public Color getColor() {
    return new Color(colorR, colorG, colorB, colorA);
  }

  public float getRelativeIntensity() {
    return relativeIntensity;
  }

  // incompatible types in return.
  @SuppressWarnings("nullness:return.type.incompatible")
  @Nullable
  public CubeMapImage[] getCubeMap() {
    return cubeMap;
  }
}
