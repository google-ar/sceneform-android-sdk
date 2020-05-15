# Migrating from Sceneform 1.15.0 to 1.16.0

As of ARCore release 1.16.0, Google open-sourced the implementation of Sceneform
to allow you to extend Sceneform's features and capabilities. If you're
interested in using the latest Sceneform release, we recommend upgrading to
version 1.16.0 and invite you to make your own modifications to the SDK.

To migrate an existing project using Sceneform version 1.15.0 to version 1.16.0,
follow the following steps:


## Convert existing `SFA` / `SFB` assets

In the interest of adopting open standards, the support for `SFA` and `SFB`
files has been removed in 1.16.0 and replaced with
[`glTF`](https://www.khronos.org/gltf/) support. Refer to the [GltfActivity](https://github.com/google-ar/sceneform-android-sdk/blob/master/samples/gltf/app/src/main/java/com/google/ar/sceneform/samples/gltf/GltfActivity.java#L104)
activity to see an example of how to load a `glTF` file.

## Migrate Sceneform dependencies

1. Remove the Sceneform plugin from your project's `build.gradle`:
   * `classpath 'com.google.ar.sceneform:plugin:1.15.0'`
1. Remove the Sceneform dependencies from your app's `build.gradle`:
   * `implementation 'com.google.ar.sceneform.ux:sceneform-ux:1.15.0'`
   * `implementation 'com.google.ar.sceneform:core:1.15.0'`
   * `implementation 'com.google.ar.sceneform:animation:1.15.0'`.
1. Remove any Sceneform asset references from your app's `build.gradle`:
   * `apply plugin: 'com.google.ar.sceneform.plugin'`
   * All `sceneform.asset(…)` directives
1. Follow the setup guide in the [README.md](https://github.com/google-ar/sceneform-android-sdk/tree/master/README.md) to include the 1.160.0 Sceneform SDK in your app.
