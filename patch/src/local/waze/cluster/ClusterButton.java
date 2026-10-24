// SPDX-License-Identifier: GPL-3.0-only
package local.waze.cluster;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;

/**
 * The Instrument cluster button, drawn like Waze's round map buttons beside it: a 56 dp circle with an
 * outline icon, white with #202124 by day and black with #E8EAED at night (colours from Waze's palette).
 */
final class ClusterButton extends View {
    static final int SIZE_DP = 56;
    private static final int DAY_FILL = 0xffffffff, DAY_ICON = 0xff202124, NIGHT_FILL = 0xff000000, NIGHT_ICON = 0xffe8eaed;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), hub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GradientDrawable circle = new GradientDrawable();
    private final RectF dial = new RectF();
    private final float density;
    private Boolean night;
    // Waze switches day and night without recreating the activity.
    private final Runnable watch = new Runnable() { public void run() { update(); postDelayed(this, 1000); } };

    ClusterButton(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        circle.setShape(GradientDrawable.OVAL);
        setBackground(circle);
        setElevation(3 * density);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeWidth(2.5f * density);
        setContentDescription("Instrument cluster");
        if (Build.VERSION.SDK_INT >= 26) setTooltipText("Instrument cluster");
        setClickable(true);
        setFocusable(true);
        update();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(watch); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(watch); super.onDetachedFromWindow(); }
    private void update() {
        Boolean map = ClusterTheme.wazeMapNight();
        boolean dark = map != null ? map
                : (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night != null && night == dark) return;
        night = dark;
        circle.setColor(dark ? NIGHT_FILL : DAY_FILL);
        stroke.setColor(dark ? NIGHT_ICON : DAY_ICON);
        hub.setColor(dark ? NIGHT_ICON : DAY_ICON);
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        // A gauge on a 24-unit grid, about the size of Waze's music and sound icons: a 240° dial, a needle, a hub.
        float u = 30 * density / 24, cx = getWidth() / 2f, cy = getHeight() / 2f + u, r = 10 * u;
        dial.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(dial, 150, 240, false, stroke);
        float needle = 6.5f * u, angle = (float) Math.toRadians(-50);
        canvas.drawLine(cx, cy, cx + needle * (float) Math.cos(angle), cy + needle * (float) Math.sin(angle), stroke);
        canvas.drawCircle(cx, cy, 2 * u, hub);
    }
}
