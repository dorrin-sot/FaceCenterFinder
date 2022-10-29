package com.google.mediapipe.examples.facemesh;

import android.content.Context;

import com.google.mediapipe.framework.Packet;
import com.google.mediapipe.framework.TextureFrame;
import com.google.mediapipe.solutions.facemesh.FaceMesh;
import com.google.mediapipe.solutions.facemesh.FaceMeshOptions;

public class CustomFaceMesh extends FaceMesh {
  public Packet cacheImagePacket;

  public CustomFaceMesh(Context context, FaceMeshOptions options) {
    super(context, options);
  }

  public void cacheImage(TextureFrame imageObj) {
    cacheImagePacket = this.packetCreator.createImage((TextureFrame) imageObj);
  }

  public void clearCache() {
    cacheImagePacket = null;
  }
}
