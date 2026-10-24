// SPDX-License-Identifier: GPL-3.0-only
package local.waze.cluster;

/**
 * Day or night for the cluster map, resolved like Google Maps for BYD. BYD switches Android's system
 * night mode with the centre-screen theme; automatic mode follows civil twilight at the car's position.
 * Pure logic; positions are only used locally and never logged.
 */
public final class ClusterTheme {
    // UiModeManager.getNightMode() values.
    public static final int MODE_AUTO = 0, MODE_NO = 1, MODE_YES = 2, MODE_CUSTOM = 3;
    private static final double DAY_MS = 86400000d, J2000_UTC_MS = 946728000000d;
    private static final double CIVIL_TWILIGHT = Math.toRadians(-6), OBLIQUITY = Math.toRadians(23.44);
    private ClusterTheme() { }

    /** @param minuteOfDay local time; customStart/customEnd in minutes (custom mode only); lat/lon null when unknown. */
    public static boolean night(int mode, long nowUtcMs, int minuteOfDay, int customStart, int customEnd, Double lat, Double lon) {
        if (mode == MODE_NO) return false;
        if (mode == MODE_YES) return true;
        if (mode == MODE_CUSTOM) return inWindow(minuteOfDay, customStart, customEnd);
        if (lat != null && lon != null) return twilightNight(nowUtcMs, lat, lon);
        return inWindow(minuteOfDay, 18 * 60, 6 * 60); // No position yet: night 18:00-06:00.
    }

    static boolean inWindow(int minute, int start, int end) {
        return start <= end ? minute >= start && minute < end : minute >= start || minute < end;
    }

    /** Sunrise equation with civil-twilight altitude; polar day/night handled by the hour-angle bounds. */
    public static boolean twilightNight(long nowUtcMs, double latitude, double longitude) {
        double days = (nowUtcMs - J2000_UTC_MS) / DAY_MS;
        double cycle = Math.round(days - 0.0009 + longitude / 360);
        double approxNoon = cycle + 0.0009 - longitude / 360;
        double anomaly = Math.toRadians((357.5291 + 0.98560028 * approxNoon) % 360);
        double center = Math.toRadians(1.9148 * Math.sin(anomaly) + 0.02 * Math.sin(2 * anomaly) + 0.0003 * Math.sin(3 * anomaly));
        double eclipticLongitude = anomaly + center + Math.toRadians(180 + 102.9372);
        double noon = approxNoon + 0.0053 * Math.sin(anomaly) - 0.0069 * Math.sin(2 * eclipticLongitude);
        double declination = Math.asin(Math.sin(eclipticLongitude) * Math.sin(OBLIQUITY));
        double lat = Math.toRadians(latitude);
        double cosHour = (Math.sin(CIVIL_TWILIGHT) - Math.sin(lat) * Math.sin(declination)) / (Math.cos(lat) * Math.cos(declination));
        if (cosHour >= 1) return true;   // Sun stays below the twilight altitude all day.
        if (cosHour <= -1) return false; // Sun stays above it all day.
        double halfDay = Math.acos(cosHour) / (2 * Math.PI);
        double sunrise = J2000_UTC_MS + (noon - halfDay) * DAY_MS, sunset = J2000_UTC_MS + (noon + halfDay) * DAY_MS;
        return nowUtcMs < sunrise || nowUtcMs >= sunset;
    }

    /** Waze's visible area for the cluster surface, moved by the owner's pointer offset: {left, top, right, bottom}. */
    public static int[] visibleArea(int width, int height, DiLink51Layout.Plan plan, int pointerPercent) {
        return visibleArea(width, height, plan, pointerPercent, 0);
    }
    /** Also moves the area horizontally by -25..+25 % of the width (- left, + right), staying inside the surface. */
    public static int[] visibleArea(int width, int height, DiLink51Layout.Plan plan, int pointerPercent, int horizontalPercent) {
        int left, top, right, bottom;
        if (plan == null) { // DiLink 5 layout verified in test.3-test.5.
            left = Math.round(width * .35f); top = Math.round(height * .16f);
            right = Math.round(width * .64f); bottom = Math.round(height * .75f);
        } else {
            left = plan.visibleLeft; top = plan.visibleTop; right = plan.visibleRight; bottom = plan.visibleBottom;
        }
        int shift = Math.round(height * Math.max(-25, Math.min(25, pointerPercent)) / 100f);
        // Keep the area's size while it fits; never leave the surface.
        if (shift > 0) shift = Math.min(shift, height - bottom);
        else shift = Math.max(shift, -top);
        int sideways = Math.round(width * Math.max(-25, Math.min(25, horizontalPercent)) / 100f);
        if (sideways > 0) sideways = Math.min(sideways, width - right);
        else sideways = Math.max(sideways, -left);
        return new int[]{left + sideways, top + shift, right + sideways, bottom + shift};
    }
    /**
     * Waze adds its own gutter (16 dp) beside any non-zero side inset; measured on the emulator at 16, 30,
     * 32 and 48 dp. Returns the side inset that puts Waze's controls margin px from the edge (at least the
     * gutter plus 1 px once on), and 0 when the margin is off.
     */
    public static int wazeSideInset(int margin, int gutter) { return margin <= 0 ? 0 : Math.max(1, margin - gutter); }
    /** Where Waze's controls end up for a side inset from wazeSideInset. */
    public static int wazeSideShift(int inset, int gutter) { return inset <= 0 ? 0 : inset + gutter; }
    private static java.lang.reflect.Field wazeNight;
    private static java.lang.reflect.Method flowValue;
    /** Waze's shared map night flag (centre screen and cluster); null when unknown or not set yet. */
    static Boolean wazeMapNight() {
        try {
            if (flowValue == null) {
                java.lang.reflect.Field field = Class.forName("com.waze.nightmode.u").getDeclaredField("g");
                field.setAccessible(true);
                flowValue = Class.forName("kotlinx.coroutines.b.bi").getMethod("d");
                wazeNight = field;
            }
            Object value = flowValue.invoke(wazeNight.get(null));
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (Throwable e) { return null; }
    }
}
