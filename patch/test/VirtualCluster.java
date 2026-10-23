// SPDX-License-Identifier: GPL-3.0-only
import android.content.Context;
import android.hardware.display.*;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.os.Looper;
public class VirtualCluster {
 public static void main(String[] args) throws Exception {
  Looper.prepareMainLooper();
  Class<?> cls = Class.forName("android.app.ActivityThread");
  Object thread = cls.getMethod("systemMain").invoke(null);
  Context context = (Context)cls.getMethod("getSystemContext").invoke(thread);
  context = context.createPackageContext("com.android.shell", 0);
  SurfaceTexture texture = new SurfaceTexture(false);
  texture.setDefaultBufferSize(1920,720);
  Surface surface = new Surface(texture);
  VirtualDisplay display = ((DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE)).createVirtualDisplay("shared_fission_bg_XDJAScreenProjection_0",1920,720,160,surface,1|2|8);
  System.out.println("Display: " + display.getDisplay());
  Looper.loop();
 }
}
