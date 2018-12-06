/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ar.sceneform.samples.videorecording;

import android.content.res.Configuration;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Environment;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import com.google.ar.sceneform.SceneView;
import java.io.File;
import java.io.IOException;

/**
 * Video Recorder class handles recording the contents of a SceneView. It uses MediaRecorder to
 * encode the video. The quality settings can be set explicitly or simply use the CamcorderProfile
 * class to select a predefined set of parameters.
 */
public class VideoRecorder {
  private static final String TAG = "VideoRecorder";
  private static final int DEFAULT_BITRATE = 10000000;
  private static final int DEFAULT_FRAMERATE = 30;

  // recordingVideoFlag is true when the media recorder is capturing video.
  private boolean recordingVideoFlag;

  private MediaRecorder mediaRecorder;

  private Size videoSize;

  private SceneView sceneView;
  private int videoCodec;
  private File videoDirectory;
  private String videoBaseName;
  private File videoPath;
  private int bitRate = DEFAULT_BITRATE;
  private int frameRate = DEFAULT_FRAMERATE;
  private Surface encoderSurface;

  private static final int[] FALLBACK_QUALITY_LEVELS = {
    CamcorderProfile.QUALITY_HIGH,
    CamcorderProfile.QUALITY_2160P,
    CamcorderProfile.QUALITY_1080P,
    CamcorderProfile.QUALITY_720P,
    CamcorderProfile.QUALITY_480P
  };

  public VideoRecorder() {
    recordingVideoFlag = false;
  }

  public File getVideoPath() {
    return videoPath;
  }

  public void setBitRate(int bitRate) {
    this.bitRate = bitRate;
  }

  public void setFrameRate(int frameRate) {
    this.frameRate = frameRate;
  }

  public void setSceneView(SceneView sceneView) {
    this.sceneView = sceneView;
  }

  /**
   * Toggles the state of video recording.
   *
   * @return true if recording is now active.
   */
  public boolean onToggleRecord() {
    if (recordingVideoFlag) {
      stopRecordingVideo();
    } else {
      startRecordingVideo();
    }
    return recordingVideoFlag;
  }

  private void startRecordingVideo() {
    if (mediaRecorder == null) {
      mediaRecorder = new MediaRecorder();
    }

    try {
      buildFilename();
      setUpMediaRecorder();
    } catch (IOException e) {
      Log.e(TAG, "Exception setting up recorder", e);
      return;
    }

    // Set up Surface for the MediaRecorder
    encoderSurface = mediaRecorder.getSurface();

    sceneView.startMirroringToSurface(
        encoderSurface, 0, 0, videoSize.getWidth(), videoSize.getHeight());

    recordingVideoFlag = true;
  }

  private void buildFilename() {
    if (videoDirectory == null) {
      videoDirectory =
          new File(
              Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                  + "/Sceneform");
    }
    if (videoBaseName == null || videoBaseName.isEmpty()) {
      videoBaseName = "Sample";
    }
    videoPath =
        new File(
            videoDirectory, videoBaseName + Long.toHexString(System.currentTimeMillis()) + ".mp4");
    File dir = videoPath.getParentFile();
    if (!dir.exists()) {
      dir.mkdirs();
    }
  }

  private void stopRecordingVideo() {
    // UI
    recordingVideoFlag = false;

    if (encoderSurface != null) {
      sceneView.stopMirroringToSurface(encoderSurface);
      encoderSurface = null;
    }
    // Stop recording
    mediaRecorder.stop();
    mediaRecorder.reset();
  }

  private void setUpMediaRecorder() throws IOException {

    mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
    mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

    mediaRecorder.setOutputFile(videoPath.getAbsolutePath());
    mediaRecorder.setVideoEncodingBitRate(bitRate);
    mediaRecorder.setVideoFrameRate(frameRate);
    mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
    mediaRecorder.setVideoEncoder(videoCodec);

    mediaRecorder.prepare();

    try {
      mediaRecorder.start();
    } catch (IllegalStateException e) {
      Log.e(TAG, "Exception starting capture: " + e.getMessage(), e);
    }
  }

  public void setVideoSize(int width, int height) {
    videoSize = new Size(width, height);
  }

  public void setVideoQuality(int quality, int orientation) {
    CamcorderProfile profile = null;
    if (CamcorderProfile.hasProfile(quality)) {
      profile = CamcorderProfile.get(quality);
    }
    if (profile == null) {
      // Select a quality  that is available on this device.
      for (int level : FALLBACK_QUALITY_LEVELS) {
        if (CamcorderProfile.hasProfile(level)) {
          profile = CamcorderProfile.get(level);
          break;
        }
      }
    }
    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
      setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
    } else {
      setVideoSize(profile.videoFrameHeight, profile.videoFrameWidth);
    }
    setVideoCodec(profile.videoCodec);
    setBitRate(profile.videoBitRate);
    setFrameRate(profile.videoFrameRate);
  }

  public void setVideoCodec(int videoCodec) {
    this.videoCodec = videoCodec;
  }

  public boolean isRecording() {
    return recordingVideoFlag;
  }
}
