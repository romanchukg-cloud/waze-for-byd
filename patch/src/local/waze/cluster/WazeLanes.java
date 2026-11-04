// SPDX-License-Identifier: GPL-3.0-only
package local.waze.cluster;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Diagnostic for lane guidance: records what Waze knows, to decide whether it can reach the HUD. Nothing is
 * sent to the car. The log and images stay in Waze's own external folder (Android/data/com.waze/files/lanes),
 * with no road names or positions, so a tester can collect them after a drive without a laptop in the car.
 * Names pinned to Waze 5.26.0.0. A failure here must never affect Waze or the guidance.
 */
final class WazeLanes {
    private static final String TAG = "WazeLanes";
    private static final int MAX_IMAGES = 8;
    private static final long MAX_LOG = 256 * 1024;
    private static final ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "WazeLanes"));
    private static String lastLanes = "";
    private static Bitmap lastImage;
    private static int images;
    private WazeLanes() { }

    /** Called from a pinned hook when Waze's engine reports its lanes; null when there are none. */
    static synchronized void onLanes(Context c, Object list) {
        try {
            String now = list == null ? "none" : describe(list);
            if (now.equals(lastLanes)) return;
            lastLanes = now;
            note(c, "engine lanes: " + now);
        } catch (Throwable e) { note(c, "engine lanes unreadable: " + e); }
    }
    private static void note(Context c, String line) {
        Log.i(TAG, line);
        final File folder = c.getExternalFilesDir("lanes");
        if (folder == null) return;
        final String stamped = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + "  " + line + "\n";
        writer.execute(() -> {
            File log = new File(folder, "lanes-log.txt");
            if (log.length() >= MAX_LOG) return;
            try (FileOutputStream out = new FileOutputStream(log, true)) { out.write(stamped.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            catch (Throwable e) { Log.w(TAG, "lanes log not saved: " + e); }
        });
    }
    private static String describe(Object list) throws ReflectiveOperationException {
        List<?> lanes = (List<?>) list.getClass().getMethod("getNavigationLaneList").invoke(list);
        int[][] angles = new int[lanes.size()][];
        boolean[][] selected = new boolean[lanes.size()][];
        int[] index = new int[lanes.size()];
        for (int i = 0; i < lanes.size(); i++) {
            Object lane = lanes.get(i);
            index[i] = (Integer) lane.getClass().getMethod("getIndex").invoke(lane);
            List<?> directions = (List<?>) lane.getClass().getMethod("getAngleList").invoke(lane);
            angles[i] = new int[directions.size()];
            selected[i] = new boolean[directions.size()];
            for (int j = 0; j < directions.size(); j++) {
                Object direction = directions.get(j);
                angles[i][j] = (Integer) direction.getClass().getMethod("getAngle").invoke(direction);
                selected[i][j] = (Boolean) direction.getClass().getMethod("getIsSelected").invoke(direction);
            }
        }
        return format(index, angles, selected);
    }
    /** For example "3 lanes: 0[-45*,0] 1[0*] 2[45]"; * marks the directions Waze recommends. */
    static String format(int[] index, int[][] angles, boolean[][] selected) {
        StringBuilder out = new StringBuilder().append(index.length).append(index.length == 1 ? " lane:" : " lanes:");
        for (int i = 0; i < index.length; i++) {
            out.append(' ').append(index[i]).append('[');
            for (int j = 0; j < angles[i].length; j++) {
                if (j > 0) out.append(',');
                out.append(angles[i][j]);
                if (selected[i][j]) out.append('*');
            }
            out.append(']');
        }
        return out.toString();
    }
    /** The image Waze draws for the car screens. Keeps the first few as PNG to compare with the engine's lanes. */
    static synchronized void onImage(Context c, Bitmap image) {
        if (image == lastImage) return;
        lastImage = image;
        if (image == null) { note(c, "lanes image: none"); return; }
        note(c, "lanes image " + images + ": " + image.getWidth() + "x" + image.getHeight());
        if (images >= MAX_IMAGES || image.isRecycled()) return;
        final Bitmap copy = image.copy(image.getConfig() == null ? Bitmap.Config.ARGB_8888 : image.getConfig(), false);
        final File folder = c.getExternalFilesDir("lanes");
        final int number = images++;
        if (copy == null || folder == null) return;
        writer.execute(() -> {
            try (FileOutputStream out = new FileOutputStream(new File(folder, "lanes-" + number + ".png"))) {
                copy.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Throwable e) { Log.w(TAG, "lanes image not saved: " + e); }
        });
    }
}
