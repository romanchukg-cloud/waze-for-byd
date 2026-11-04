// SPDX-License-Identifier: GPL-3.0-only
package local.waze.cluster;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.widget.Toast;

/**
 * Steering-wheel zoom, shared by the key service and the Waze windows. The custom button turns zoom mode on
 * and off; in zoom mode volume + and - zoom the cluster map, otherwise every key keeps its normal action.
 */
final class WheelKeys {
    private static final String TAG = "WazeWheel", SERVICE = "local.waze.cluster.WheelKeyService";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final WheelKeyPolicy.Learning learning = new WheelKeyPolicy.Learning();
    private static final WheelKeyPolicy.Recent recent = new WheelKeyPolicy.Recent();
    private static WheelKeyPolicy policy;
    private static WheelKeyPolicy.Bindings bindings;
    private static String assignment = "";
    private static volatile boolean connected;
    private WheelKeys() { }

    static void connected(boolean value) {
        connected = value;
        Log.i(TAG, value ? "key service connected" : "key service disconnected");
    }
    /** Whether the owner enabled the key service in Android, connected or not. */
    static boolean listed(Context c) {
        String enabled = Settings.Secure.getString(c.getContentResolver(), "enabled_accessibility_services");
        if (enabled == null) return false;
        String own = c.getPackageName() + "/" + SERVICE;
        for (String entry : enabled.split(":")) if (own.equals(entry)) return true;
        return false;
    }
    private static synchronized WheelKeyPolicy.Bindings bindings(Context c) {
        if (bindings == null) bindings = ClusterSettings.wheelBindings(c);
        return bindings;
    }
    private static synchronized void replace(Context c, WheelKeyPolicy.Bindings next) {
        ClusterSettings.saveWheelBindings(c, next);
        bindings = next;
        if (policy != null) policy.updateBindings(next);
    }
    /** Returns true when the key was used for zoom and must not reach the car. */
    static synchronized boolean handle(Context c, KeyEvent key, String source) {
        // Keys from the on-screen keyboard are not wheel presses, and what is typed stays out of the log.
        if (key.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD || (key.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0) return false;
        InputDevice device = InputDevice.getDevice(key.getDeviceId());
        WheelKeyPolicy.Event event = new WheelKeyPolicy.Event(key.getAction(), key.getKeyCode(), key.getScanCode(), key.getDeviceId(),
                device == null ? null : device.getName(), key.getDownTime(), key.getRepeatCount(), key.isCanceled());
        long now = SystemClock.uptimeMillis();
        WheelKeyPolicy.Learning.Capture capture = learning.handle(event, now);
        if (capture.binding != null) {
            WheelKeyPolicy.Bindings next = WheelKeyPolicy.assign(bindings(c), capture.role, capture.binding);
            replace(c, next);
            assignment = roleName(capture.role) + " saved: " + capture.binding + ".";
        }
        boolean consumed = capture.consume || zoom(c, event);
        recent.add(System.currentTimeMillis(), event, source, consumed);
        if (event.action == WheelKeyPolicy.DOWN && event.repeat == 0)
            Log.i(TAG, "key " + event.code + "/" + event.scan + " " + event.device + " via " + source + (consumed ? " used" : " passed"));
        return consumed;
    }
    private static boolean zoom(Context c, WheelKeyPolicy.Event event) {
        if (policy == null) policy = new WheelKeyPolicy(bindings(c));
        boolean before = policy.mode();
        WheelKeyPolicy.Result result = policy.handle(event, ClusterSettings.state(c).wheelZoom, ClusterHost.zoomReady(), audioSafe(c));
        final Context app = c.getApplicationContext();
        if (result.mode != before && result.consume)
            main.post(() -> Toast.makeText(app, result.mode ? "Cluster zoom on" : "Volume controls restored", Toast.LENGTH_SHORT).show());
        if (result.delta != 0) main.post(() -> ClusterHost.zoom(result.delta > 0));
        return result.consume;
    }
    /** Calls keep the volume buttons. */
    private static boolean audioSafe(Context c) {
        AudioManager audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        return audio != null && audio.getMode() == AudioManager.MODE_NORMAL && !audio.isBluetoothScoOn();
    }
    static String status(Context c) {
        if (!ClusterSettings.state(c).wheelZoom) return "Off. The custom and volume buttons keep their normal actions.";
        if (connected) return "On. The key service is connected.";
        return listed(c) ? "On. The key service is enabled in Android but not connected."
                : "On. The key service is not enabled in Android, so keys arrive only while a Waze window is on the centre screen, if at all.";
    }
    static synchronized String roles(Context c) {
        WheelKeyPolicy.Bindings b = bindings(c);
        return "Custom button: " + b.mode + "\nZoom in: " + b.in + "\nZoom out: " + b.out;
    }
    static String recentKeys() { return recent.describe(java.util.TimeZone.getDefault()); }
    static String roleName(int role) { return role == 1 ? "Custom button" : role == 2 ? "Zoom in" : "Zoom out"; }
    static synchronized void learn(int role) {
        assignment = "";
        learning.start(role, SystemClock.uptimeMillis());
    }
    static synchronized void cancelLearning() { learning.cancel(); }
    static synchronized String assignment() {
        long now = SystemClock.uptimeMillis();
        int role = learning.role(now);
        return role != 0 ? "Press the steering-wheel button for " + roleName(role) + " now (" + (learning.remaining(now) + 999) / 1000 + " s)." : assignment;
    }
    static synchronized void restoreTang(Context c) {
        learning.cancel();
        replace(c, WheelKeyPolicy.Bindings.tang());
        assignment = "BYD Tang buttons restored.";
    }
}
