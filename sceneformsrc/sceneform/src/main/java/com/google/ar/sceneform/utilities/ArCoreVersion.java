package com.google.ar.sceneform.utilities;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * Utilities for detecting and handling the version of Ar Core.
 *
 * @hide
 */
public class ArCoreVersion {
  public static final int VERSION_CODE_1_3 = 180604036;

  private static final String METADATA_KEY_MIN_APK_VERSION = "com.google.ar.core.min_apk_version";

  public static int getMinArCoreVersionCode(Context context) {
    PackageManager packageManager = context.getPackageManager();
    String packageName = context.getPackageName();

    Bundle metadata;
    try {
      metadata =
          packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData;
    } catch (PackageManager.NameNotFoundException e) {
      throw new IllegalStateException("Could not load application package metadata.", e);
    }

    if (metadata.containsKey(METADATA_KEY_MIN_APK_VERSION)) {
      return metadata.getInt(METADATA_KEY_MIN_APK_VERSION);
    } else {
      throw new IllegalStateException(
          "Application manifest must contain meta-data." + METADATA_KEY_MIN_APK_VERSION);
    }
  }
}
