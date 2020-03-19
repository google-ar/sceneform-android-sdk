package com.google.ar.sceneform.rendering;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.net.Uri;
import android.support.annotation.Nullable;
import android.util.Log;
import com.google.android.filament.IndirectLight;
import com.google.android.filament.Texture;

import com.google.ar.sceneform.math.Matrix;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.rendering.SceneformBundle.VersionException;
import com.google.ar.sceneform.utilities.AndroidPreconditions;
import com.google.ar.sceneform.utilities.ChangeId;
import com.google.ar.sceneform.utilities.EnvironmentalHdrParameters;
import com.google.ar.sceneform.utilities.LoadHelper;
import com.google.ar.sceneform.utilities.Preconditions;
import com.google.ar.sceneform.utilities.SceneformBufferUtils;
import com.google.ar.schemas.lull.Vec3;
import com.google.ar.schemas.sceneform.LightingCubeDef;
import com.google.ar.schemas.sceneform.LightingCubeFaceDef;
import com.google.ar.schemas.sceneform.LightingCubeFaceType;
import com.google.ar.schemas.sceneform.LightingDef;
import com.google.ar.schemas.sceneform.SceneformBundleDef;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Loads "light probe" data needed for Image Based Lighting. This includes a cubemap with mip maps
 * generated to match the lighting model used by Sceneform and Spherical Harmonics coefficients for
 * diffuse image based lighting.
 *
 * @hide for 1.0 as we don't yet have tools support
 */
public class LightProbe {
  private static final String TAG = LightProbe.class.getSimpleName();
  private static final int CUBEMAP_MIN_WIDTH = 4;
  private static final int CUBEMAP_FACE_COUNT = 6;
  private static final int RGBM_BYTES_PER_PIXEL = 4;
  private static final int FLOATS_PER_VECTOR = 3;
  private static final int SH_ORDER = 3;
  private static final int BYTES_PER_FLOAT16 = 2;
  private static final int RGBA_BYTES_PER_PIXEL = 8;
  private static final int RGB_BYTES_PER_PIXEL = 6;
  private static final int RGB_CHANNEL_COUNT = 3;
  // The Light estimate scale and offset allow the final change in intensity to be controlled to
  // avoid over darkening or changes that are too drastic: appliedEstimate = estimate*scale + offset
  private static final float LIGHT_ESTIMATE_SCALE = 1.8f;
  private static final float LIGHT_ESTIMATE_OFFSET = 0.0f;
  // The number of required SH vectors = SH_ORDER^2
  private static final int SH_VECTORS_FOR_THIRD_ORDER = SH_ORDER * SH_ORDER;
  // Filament expects the face order to be "px", "nx", "py", "ny", "pz", "nz"
  private static final int[] FACE_TO_FILAMENT_MAPPING = {
    LightingCubeFaceType.px,
    LightingCubeFaceType.nx,
    LightingCubeFaceType.py,
    LightingCubeFaceType.ny,
    LightingCubeFaceType.pz,
    LightingCubeFaceType.nz
  };
  private static final int EXPECTED_SPHERICAL_HARMONICS_LENGTH = 27;

  /**
   * Convert Environmental HDR's spherical harmonics to Filament spherical harmonics.
   *
   * <p>This conversion is calculated to include the following:
   *
   * <ul>
   *   <li>pre-scaling by SH basis normalization factor [shader optimization]
   *   <li>sqrt(2) factor coming from keeping only the real part of the basis [shader optimization]
   *   <li>1/pi factor for the diffuse lambert BRDF [shader optimization]
   *   <li>|dot(n,l)| spherical harmonics [irradiance]
   *   <li>scaling for convolution of SH function by radially symmetrical SH function [irradiance]
   * </ul>
   *
   * <p>ENVIRONMENTAL_HDR_TO_FILAMENT_SH_INDEX_MAP must be applied change ordering of coeffients
   * from Environmental HDR to filament.
   */
  private static final float[] ENVIRONMENTAL_HDR_TO_FILAMENT_SH_COEFFIECIENTS = {
    0.282095f,
    -0.325735f,
    0.325735f,
    -0.325735f,
    0.273137f,
    -0.273137f,
    0.078848f, // notice index 6 & 7 swapped
    -0.273137f, // notice index 6 & 7 swapped
    0.136569f
  };

  // SH coefficients are not in the same order in Filament and Environmental HDR.
  // SH coefficients at indices 6 and 7 are swapped between the two implementations.
  private static final int[] ENVIRONMENTAL_HDR_TO_FILAMENT_SH_INDEX_MAP = {
    0, 1, 2, 3, 4, 5, 7, 6, 8
  };

  private ByteBuffer cubemapBuffer = ByteBuffer.allocate(10000);
  @Nullable private Texture reflectCubemap = null;
  private final Color colorCorrection = new Color(1f, 1f, 1f);
  private final Color ambientColor = new Color();
  private float[] irradianceData;
  @Nullable private String name = null;

  private ChangeId changeId = new ChangeId();

  private float intensity;
  private float lightEstimate = 1.0f;
  @Nullable private Quaternion rotation;

  /** Constructs a default LightProbe, if nothing else is set */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Set the overall intensity of the indirect light.
   *
   * @param intensity the intensity of indirect lighting, the default is 220.0
   */
  public void setIntensity(float intensity) {
    this.intensity = intensity;
  }

  /** Get the overall intensity of the indirect light. */
  public float getIntensity() {
    return intensity;
  }

  /**
   * Sets the rotation of the indirect light.
   *
   * @param rotation the rotation of the indirect light, identity when null
   */
  public void setRotation(@Nullable Quaternion rotation) {
    this.rotation = rotation;
  }

  /** Gets the rotation of the indirect light, identity if null. */
  @Nullable
  public Quaternion getRotation() {
    return rotation;
  }

  int getId() {
    return changeId.get();
  }

  /** Returns true if the LightProbe is ready to be used for rendering. */
  public boolean isReady() {
    return !changeId.isEmpty();
  }

  /** @hide */
  @Nullable
  com.google.android.filament.IndirectLight buildIndirectLight() {
    Preconditions.checkNotNull(irradianceData, "\"irradianceData\" was null.");
    Preconditions.checkState(
        irradianceData.length >= FLOATS_PER_VECTOR,
        "\"irradianceData\" does not have enough components to store a vector");

    if (reflectCubemap == null) {
      throw new IllegalStateException("reflectCubemap is null.");
    }

    // Modulates ambient color with modulation factor. irradianceData must have at least one vector
    // of three floats.
    irradianceData[0] = ambientColor.r * colorCorrection.r;
    irradianceData[1] = ambientColor.g * colorCorrection.g;
    irradianceData[2] = ambientColor.b * colorCorrection.b;

    IndirectLight indirectLight =
        new IndirectLight.Builder()
            .reflections(reflectCubemap)
            .irradiance(SH_ORDER, irradianceData)
            .intensity(intensity * lightEstimate)
            .build(EngineInstance.getEngine().getFilamentEngine());

    // There is a bug in filament where setting the rotation doesn't work if it is done using
    // the builder. It must be done on the actual indirect light object.
    if (rotation != null) {
      indirectLight.setRotation(quaternionToRotationMatrix(rotation));
    }

    if (indirectLight == null) {
      throw new IllegalStateException("Light Probe is invalid.");
    }
    return indirectLight;
  }

  private LightProbe(Builder builder) {
    intensity = builder.intensity;
    rotation = builder.rotation;
    name = builder.name;
  }

  private void buildFilamentResource(LightingDef lightingDef) {
    dispose();
    changeId.update();

    // For some reason the static analysis cannot see the check for null above for LightingDef,
    // so we check again here...
    if (lightingDef == null) {
      throw new IllegalStateException(
          "buildFilamentResource called but no resource is available to load.");
    }

    // Build the resources here.
    final Texture cubemap = loadReflectCubemapFromLightingDef(lightingDef);
    if (cubemap == null) {
      throw new IllegalStateException("Load reflection cubemap failed.");
    }
    setCubeMapFromTexture(cubemap);

    final int shVectorCount = lightingDef.shCoefficientsLength();
    if (shVectorCount < SH_VECTORS_FOR_THIRD_ORDER) {
      throw new IllegalStateException("Too few SH vectors for the current Order (3).");
    }

    final int requiredFloatCount = shVectorCount * FLOATS_PER_VECTOR;
    if (irradianceData == null || irradianceData.length != requiredFloatCount) {
      irradianceData = new float[requiredFloatCount];
    }

    for (int v = 0; v < shVectorCount; ++v) {
      final Vec3 shVector = lightingDef.shCoefficients(v);
      // filament SH coefficient have changed for 1.6, since we use hardcoded coefficients for now
      // we have to scale them to the new format.

      // LINT.IfChange
      irradianceData[v * 3 + 0] = shVector.x() / (float) Math.PI;
      irradianceData[v * 3 + 1] = shVector.y() / (float) Math.PI;
      irradianceData[v * 3 + 2] = shVector.z() / (float) Math.PI;
      // LINT.ThenChange(//depot/google3/third_party/arcore/ar/imp/core/lighting/lighting_data.cc)
    }

    // Gets ambient color of irradiance data as the first sh coefficient.
    ambientColor.set(irradianceData[0], irradianceData[1], irradianceData[2]);
  }

  @Override
  protected void finalize() throws Throwable {
    try {
      ThreadPools.getMainExecutor().execute(() -> dispose());
    } catch (Exception e) {
      Log.e(TAG, "Error while Finalizing Light Probe.", e);
    } finally {
      super.finalize();
    }
  }

  /** @hide */
  @SuppressWarnings("nullness")
  public void dispose() {
    AndroidPreconditions.checkUiThread();

    setCubeMapFromTexture(null);

    changeId = new ChangeId();
  }

  private void setCubeMapFromTexture(com.google.android.filament.Texture nextCubemap) {
    com.google.android.filament.Texture prevTexture = reflectCubemap;
    IEngine engine = EngineInstance.getEngine();
    if (prevTexture != null && engine != null && engine.isValid()) {
      engine.destroyTexture(prevTexture);
    }
    reflectCubemap = nextCubemap;
  }

  /**
   * Updates spherical harmonics with values not premultiplied by the SH basis.
   *
   * @hide intended for use by other Sceneform packages which update Hdr lighting every frame.
   */
  
  public void setEnvironmentalHdrSphericalHarmonics(
      float[] sphericalHarmonics,
      float exposure,
      EnvironmentalHdrParameters environmentalHdrParameters) {
    float scaleFactor =
        environmentalHdrParameters.getAmbientShScaleForFilament()
            / (exposure * environmentalHdrParameters.getReflectionScaleForFilament());
    if (sphericalHarmonics.length != EXPECTED_SPHERICAL_HARMONICS_LENGTH) {
      throw new RuntimeException(
          "Expected " + EXPECTED_SPHERICAL_HARMONICS_LENGTH + " spherical Harmonics coefficients");
    }

    if (irradianceData == null || irradianceData.length != sphericalHarmonics.length) {
      irradianceData = new float[EXPECTED_SPHERICAL_HARMONICS_LENGTH];
    }

    for (int srcIndex = 0; srcIndex < 9; ++srcIndex) {
      int destIndex = ENVIRONMENTAL_HDR_TO_FILAMENT_SH_INDEX_MAP[srcIndex];
      irradianceData[destIndex * 3] =
          sphericalHarmonics[srcIndex * 3]
              * ENVIRONMENTAL_HDR_TO_FILAMENT_SH_COEFFIECIENTS[destIndex]
              * scaleFactor;
      irradianceData[destIndex * 3 + 1] =
          sphericalHarmonics[srcIndex * 3 + 1]
              * ENVIRONMENTAL_HDR_TO_FILAMENT_SH_COEFFIECIENTS[destIndex]
              * scaleFactor;

      irradianceData[destIndex * 3 + 2] =
          sphericalHarmonics[srcIndex * 3 + 2]
              * ENVIRONMENTAL_HDR_TO_FILAMENT_SH_COEFFIECIENTS[destIndex]
              * scaleFactor;
    }
    ambientColor.set(irradianceData[0], irradianceData[1], irradianceData[2]);
    this.colorCorrection.set(new Color(1, 1, 1));
    this.lightEstimate = environmentalHdrParameters.getReflectionScaleForFilament();
    this.intensity = 1.0f;
  }

  /**
   * Modify light intensity using ArCore light estimation. ArCore light estimation is not compatible
   * with Environmental HDR, only one may be used.
   *
   * @hide
   */
  public void setLightEstimate(Color colorCorrection, float estimate) {
    // Scale and bias the estimate to avoid over darkening.
    lightEstimate = Math.min(estimate * LIGHT_ESTIMATE_SCALE + LIGHT_ESTIMATE_OFFSET, 1.0f);
    this.colorCorrection.set(colorCorrection);
  }

  /**
   * Constructs a {@link LightProbe} when the data is unavailable, and must be requested
   * asynchronously
   */
  @SuppressWarnings("AndroidApiChecker") // java.util.concurrent.CompletableFuture
  private CompletableFuture<LightingDef> loadInBackground(
      Callable<InputStream> inputStreamCreator) {
    return CompletableFuture.supplyAsync(
        () -> {
          if (inputStreamCreator == null) {
            throw new IllegalArgumentException("Invalid source.");
          }

          @Nullable ByteBuffer assetData = null;

          // Open and read the texture file.
          try (InputStream inputStream = inputStreamCreator.call()) {
            assetData = SceneformBufferUtils.readStream(inputStream);
          } catch (Exception e) {
            throw new CompletionException(e);
          }

          if (assetData == null) {
            throw new AssertionError(
                "The Sceneform bundle containing the Light Probe could not be loaded.");
          }

          SceneformBundleDef rcb;
          try {
            rcb = SceneformBundle.tryLoadSceneformBundle(assetData);
          } catch (VersionException e) {
            throw new CompletionException(e);
          }

          if (rcb == null) {
            throw new AssertionError(
                "The Sceneform bundle containing the Light Probe could not be loaded.");
          }

          final int lightingDefsLength = rcb.lightingDefsLength();
          if (lightingDefsLength < 1) {
            throw new IllegalStateException("Content does not contain any Light Probe data.");
          }

          // If the name is non-null, look for the correct Light Probe to use.
          // If the name is null then the first Light Probe is used.
          int lightProbeIndex = -1;
          if (name != null) {
            for (int i = 0; i < lightingDefsLength; ++i) {
              LightingDef lightingDef = rcb.lightingDefs(i);
              if (lightingDef.name().equals(name)) {
                lightProbeIndex = i;
                break;
              }
            }

            if (lightProbeIndex < 0) {
              throw new IllegalArgumentException(
                  "Light Probe asset \"" + name + "\" not found in bundle.");
            }
          } else {
            lightProbeIndex = 0;
          }

          LightingDef lightingDef = rcb.lightingDefs(lightProbeIndex);
          if (lightingDef == null) {
            throw new IllegalStateException("LightingDef is invalid.");
          }

          return lightingDef;
        },
        ThreadPools.getThreadPoolExecutor());
  }

  /** Factory class for {@link LightProbe} */
  @SuppressWarnings("AndroidApiChecker") // java.util.concurrent.CompletableFuture
  public static final class Builder {
    /** The {@link LightProbe} will be constructed from the contents of this callable */
    @Nullable private Callable<InputStream> inputStreamCreator = null;

    /** intensity of the indirect lighting */
    private float intensity = 220.0f;

    @Nullable private Quaternion rotation;

    /**
     * Name of the Light Probe to load if the file contains more than one. If no name is specified
     * than the first Light Probe found will be used.
     */
    @Nullable private String name = null;

    /** Constructor for asynchronous building. */
    private Builder() {}

    /**
     * Set the intensity of the indirect lighting.
     *
     * @param intensity intensity of the indirect lighting, the default is 220.
     */
    public Builder setIntensity(float intensity) {
      this.intensity = intensity;
      return this;
    }

    /**
     * Sets the rotation of the indirect light.
     *
     * @param rotation the rotation of the indirect light, identity when null
     */
    public Builder setRotation(@Nullable Quaternion rotation) {
      this.rotation = rotation;
      return this;
    }

    /**
     * Set the name of the Light Probe to load if the binary bundle file contains more than one.
     *
     * @param name the name of the Light Probe to load.
     */
    public Builder setAssetName(String name) {
      this.name = name;
      return this;
    }

    /**
     * Allows a {@link LightProbe} to be constructed from {@link Uri}. Construction will be
     * asynchronous.
     *
     * @param context a context used for loading the resource
     * @param sourceUri a remote Uri or android resource Uri.
     * @hide Hide until we have a documented way to build custom light probes.
     */
    public Builder setSource(Context context, Uri sourceUri) {
      Preconditions.checkNotNull(sourceUri, "Parameter \"sourceUri\" was null.");

      setSource(LoadHelper.fromUri(context, sourceUri));
      return this;
    }

    /**
     * Allows a {@link LightProbe} to be constructed from resource. Construction will be
     * asynchronous.
     *
     * @param context a context used for loading the resource
     * @param resource an android resource with raw type.
     */
    public Builder setSource(Context context, int resource) {
      setSource(LoadHelper.fromResource(context, resource));
      return this;
    }

    /**
     * Allows a {@link LightProbe} to be constructed via callable function.
     *
     * @hide Hide until we have a documented way to build custom light probes.
     */
    public Builder setSource(Callable<InputStream> inputStreamCreator) {
      Preconditions.checkNotNull(
          inputStreamCreator, "Parameter \"sourceInputStreamCallable\" was null.");

      this.inputStreamCreator = inputStreamCreator;
      return this;
    }

    /** Creates a new {@link LightProbe} based on the parameters set previously */
    @SuppressWarnings("FutureReturnValueIgnored") // CompletableFuture
    public CompletableFuture<LightProbe> build() {
      // At this point sourceInputStreamCallable should never be null.
      if (inputStreamCreator == null) {
        throw new IllegalStateException("Light Probe source is NULL, this should never happen.");
      }

      @Nullable LightProbe lightProbe = new LightProbe(this);
      CompletableFuture<LightProbe> result =
          lightProbe
              .loadInBackground(inputStreamCreator)
              .thenApplyAsync(
                  lightingDef -> {
                    // Call to buildFilamentResource on the Filament thread
                    lightProbe.buildFilamentResource(lightingDef);
                    return lightProbe;
                  },
                  ThreadPools.getMainExecutor());

      if (result == null) {
        throw new IllegalStateException("CompletableFuture result is null.");
      }

      return FutureHelper.logOnException(
          TAG, result, "Unable to load LightProbe: name='" + name + "'");
    }
  }

  public void setCubeMap(Image[] cubemapImageArray) {
    // TODO: Update once Filament updates past v1.3.0.
    if (cubemapImageArray.length != CUBEMAP_FACE_COUNT) {
      throw new IllegalArgumentException(
          "Unexpected cubemap array length: " + cubemapImageArray.length);
    }

    int width = cubemapImageArray[0].getWidth();
    int height = cubemapImageArray[0].getHeight();
    int bufferCapacity =
        width * height * CUBEMAP_FACE_COUNT * RGB_CHANNEL_COUNT * BYTES_PER_FLOAT16;
    if (cubemapBuffer.capacity() < bufferCapacity) {
      cubemapBuffer = ByteBuffer.allocate(bufferCapacity);
    } else {
      cubemapBuffer.clear();
    }

    int[] faceOffsets = new int[CUBEMAP_FACE_COUNT];
    for (int i = 0; i < CUBEMAP_FACE_COUNT; i++) {
      faceOffsets[i] = cubemapBuffer.position();
      Image.Plane[] planes = cubemapImageArray[i].getPlanes();
      if (planes.length != 1) {
        throw new IllegalArgumentException(
            "Unexpected number of Planes in cubemap Image array: " + planes.length);
      }
      Image.Plane currentPlane = planes[0];
      if (currentPlane.getPixelStride() != RGBA_BYTES_PER_PIXEL) {
        throw new IllegalArgumentException(
            "Unexpected pixel stride in cubemap data: expected "
                + RGBA_BYTES_PER_PIXEL
                + ", got "
                + currentPlane.getPixelStride());
      }
      if (currentPlane.getRowStride() != width * RGBA_BYTES_PER_PIXEL) {
        throw new IllegalArgumentException(
            "Unexpected row stride in cubemap data: expected "
                + (width * RGBA_BYTES_PER_PIXEL)
                + ", got "
                + currentPlane.getRowStride());
      }
      ByteBuffer rgbaBuffer = currentPlane.getBuffer();
      while (rgbaBuffer.hasRemaining()) {
        for (int byt = 0; byt < RGBA_BYTES_PER_PIXEL; byt++) {
          byte b = rgbaBuffer.get();
          if (byt < RGB_BYTES_PER_PIXEL) {
            cubemapBuffer.put(b);
          }
        }
      }
    }
    cubemapBuffer.flip();

    IEngine engine = EngineInstance.getEngine();
    int levels = (int) (1 + Math.log(width) / Math.log(2.0));
    Texture cubemapTexture =
        new com.google.android.filament.Texture.Builder()
            .width(width)
            .height(height)
            .levels(levels)
            .sampler(com.google.android.filament.Texture.Sampler.SAMPLER_CUBEMAP)
            .format(com.google.android.filament.Texture.InternalFormat.R11F_G11F_B10F)
            .build(engine.getFilamentEngine());
    com.google.android.filament.Texture.PixelBufferDescriptor pixelBuf =
        new com.google.android.filament.Texture.PixelBufferDescriptor(
            cubemapBuffer,
            com.google.android.filament.Texture.Format.RGB,
            com.google.android.filament.Texture.Type.HALF);
    com.google.android.filament.Texture.PrefilterOptions options =
        new com.google.android.filament.Texture.PrefilterOptions();
    options.mirror = false;
    cubemapTexture.generatePrefilterMipmap(
        engine.getFilamentEngine(), pixelBuf, faceOffsets, options);
    setCubeMapFromTexture(cubemapTexture);
  }

  private static Texture loadReflectCubemapFromLightingDef(LightingDef lightingDef) {
    Preconditions.checkNotNull(lightingDef, "Parameter \"lightingDef\" was null.");

    IEngine engine = EngineInstance.getEngine();

    final int mipCount = lightingDef.cubeLevelsLength();
    if (mipCount < 1) {
      throw new IllegalStateException("Lighting cubemap has no image data.");
    }

    // Get the size of each face from the first mip map.
    final LightingCubeDef baseLevel = lightingDef.cubeLevels(0);
    final LightingCubeFaceDef baseFace = baseLevel.faces(0);

    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inPremultiplied = false;
    options.inScaled = false;
    options.inJustDecodeBounds = true;
    final ByteBuffer data = baseFace.dataAsByteBuffer();

    final byte[] dataArray = data.array();
    final int offset = data.arrayOffset() + data.position();
    final int length = data.limit() - data.position();
    BitmapFactory.decodeByteArray(dataArray, offset, length, options);

    // Width and Height must be non-zero and equal.
    int width = options.outWidth;
    int height = options.outHeight;
    if (width < CUBEMAP_MIN_WIDTH || height < CUBEMAP_MIN_WIDTH || width != height) {
      throw new IllegalStateException(
          "Lighting cubemap has invalid dimensions: " + width + " x " + height);
    }

    // Create the Filament texture resource.
    Texture filamentTexture =
        new com.google.android.filament.Texture.Builder()
            .width(width)
            .height(height)
            .levels(mipCount)
            .format(com.google.android.filament.Texture.InternalFormat.R11F_G11F_B10F)
            .sampler(com.google.android.filament.Texture.Sampler.SAMPLER_CUBEMAP)
            .build(engine.getFilamentEngine());

    // Loop through all of the mip maps and load the image data.
    int faceSize = width * height * RGBM_BYTES_PER_PIXEL;
    final int[] faceOffsetsInBytes = new int[CUBEMAP_FACE_COUNT];

    options.inJustDecodeBounds = false;
    for (int m = 0; m < mipCount; ++m) {
      // Now load all of the image data into the buffer.
      final ByteBuffer buffer = ByteBuffer.allocateDirect(faceSize * CUBEMAP_FACE_COUNT);
      LightingCubeDef level = lightingDef.cubeLevels(m);

      for (int f = 0; f < CUBEMAP_FACE_COUNT; ++f) {
        final int sourceFaceIndex = FACE_TO_FILAMENT_MAPPING[f];
        final LightingCubeFaceDef face = level.faces(sourceFaceIndex);
        faceOffsetsInBytes[f] = faceSize * f;

        final ByteBuffer faceData = face.dataAsByteBuffer();
        final byte[] faceDataArray = faceData.array();
        final int faceOffset = faceData.arrayOffset() + faceData.position();
        final int faceLength = faceData.limit() - faceData.position();

        final Bitmap faceBitmap =
            BitmapFactory.decodeByteArray(faceDataArray, faceOffset, faceLength, options);

        if (faceBitmap.getWidth() != width || faceBitmap.getHeight() != height) {
          throw new AssertionError("All cube map textures must have the same size");
        }
        faceBitmap.copyPixelsToBuffer(buffer);
      }
      buffer.rewind();

      final com.google.android.filament.Texture.PixelBufferDescriptor descriptor =
          new com.google.android.filament.Texture.PixelBufferDescriptor(
              buffer,
              com.google.android.filament.Texture.Format.RGB,
              com.google.android.filament.Texture.Type.UINT_10F_11F_11F_REV);

      filamentTexture.setImage(engine.getFilamentEngine(), m, descriptor, faceOffsetsInBytes);

      width >>= 1;
      height >>= 1;
      faceSize = width * height * RGBM_BYTES_PER_PIXEL;
    }

    return filamentTexture;
  }

  private static float[] quaternionToRotationMatrix(Quaternion quaternion) {
    Matrix matrix = new Matrix();
    matrix.makeRotation(quaternion);

    // Convert to a 3x3 matrix packed in a float-array.
    float[] floatArray = new float[9];
    floatArray[0] = matrix.data[0];
    floatArray[1] = matrix.data[1];
    floatArray[2] = matrix.data[2];

    floatArray[3] = matrix.data[4];
    floatArray[4] = matrix.data[5];
    floatArray[5] = matrix.data[6];

    floatArray[6] = matrix.data[8];
    floatArray[7] = matrix.data[9];
    floatArray[8] = matrix.data[10];

    return floatArray;
  }
}
