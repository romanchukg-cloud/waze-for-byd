import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/** Emulator-only: recreate the three BYD cluster layers (1920x720, 320 dpi) and save their frames as PNG.
 * Run with app_process as shell or root; never on a vehicle. */
public class ClusterCapture {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Context context = (Context) thread.getMethod("getSystemContext").invoke(thread.getMethod("systemMain").invoke(null));
        context = context.createPackageContext("com.android.shell", 0);
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        Handler handler = new Handler(Looper.myLooper());
        String[] names = {"fission_bg_XDJAScreenProjection", "shared_fission_bg_XDJAScreenProjection_0", "shared_fission_bg_XDJAScreenProjection_1"};
        for (String name : names) {
            ImageReader reader = ImageReader.newInstance(1920, 720, PixelFormat.RGBA_8888, 2);
            // PUBLIC | PRESENTATION | OWN_CONTENT_ONLY, like the vehicle's containerservice layers.
            System.out.println(name + " -> " + manager.createVirtualDisplay(name, 1920, 720, 320, reader.getSurface(), 1 | 2 | 8).getDisplay());
            File out = new File("/data/local/tmp/" + name + ".png");
            handler.post(new Runnable() {
                public void run() {
                    try (Image image = reader.acquireLatestImage()) {
                        if (image != null) save(image, out);
                    } catch (Exception e) { e.printStackTrace(); }
                    handler.postDelayed(this, 1000);
                }
            });
        }
        Looper.loop();
    }
    private static void save(Image image, File out) throws Exception {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        Bitmap padded = Bitmap.createBitmap(plane.getRowStride() / plane.getPixelStride(), image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap bitmap = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        File temp = new File(out.getPath() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(temp)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        if (!temp.renameTo(out)) throw new IllegalStateException("rename " + out);
    }
}
