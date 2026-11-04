// SPDX-License-Identifier: GPL-3.0-only
package local.waze.cluster;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Key-only accessibility service: no window content, no gestures, no key rewriting. The owner enables it over ADB. */
public final class WheelKeyService extends AccessibilityService {
    @Override protected void onServiceConnected() { WheelKeys.connected(true); }
    @Override protected boolean onKeyEvent(KeyEvent event) {
        try { return WheelKeys.handle(this, event, "service"); }
        catch (RuntimeException e) { return false; } // A failure here must never swallow the car's keys.
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(Intent intent) {
        WheelKeys.connected(false);
        return super.onUnbind(intent);
    }
}
