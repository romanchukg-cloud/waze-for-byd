// SPDX-License-Identifier: GPL-3.0-only
package local.waze.cluster;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

/** The Instrument cluster button and settings panel, matching Google Maps for BYD. Changes apply live. */
public final class ClusterPanel {
    private static final String MAIN = "com.waze.MainActivity", TAG = "local.cluster.settings.button";
    private static SharedPreferences preferences;
    private ClusterPanel() { }

    public static synchronized void install(Application app) {
        if (preferences != null) return;
        preferences = ClusterSettings.prefs(app);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity a) {
                if (MAIN.equals(a.getClass().getName())) a.getWindow().getDecorView().post(() -> attach(a));
            }
            public void onActivityCreated(Activity a, Bundle b) { }
            public void onActivityStarted(Activity a) { }
            public void onActivityPaused(Activity a) { }
            public void onActivityStopped(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
    }
    private static int dp(Context c, int value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    private static void attach(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        if (root.findViewWithTag(TAG) != null) return;
        Button button = new Button(activity);
        button.setTag(TAG);
        button.setText("Instrument cluster");
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setPadding(dp(activity, 16), 0, dp(activity, 16), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xee303438);
        background.setCornerRadius(dp(activity, 24));
        button.setBackground(background);
        button.setElevation(dp(activity, 6));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, dp(activity, 44), Gravity.TOP | Gravity.RIGHT);
        int inset = root.getRootWindowInsets() == null ? dp(activity, 48) : root.getRootWindowInsets().getStableInsetTop();
        // Top row, left of Waze's round music and sound buttons (56 dp each, 8 dp apart, measured on the car).
        lp.topMargin = inset + dp(activity, 6);
        lp.rightMargin = dp(activity, 128);
        button.setOnClickListener(v -> show(activity));
        root.addView(button, lp);
    }
    private static TextView label(Context c, String text, int size) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(size);
        v.setPadding(0, dp(c, 10), 0, dp(c, 4));
        return v;
    }
    private static void toggle(LinearLayout panel, Context c, String title, String key, boolean value) {
        Switch control = new Switch(c);
        control.setText(title);
        control.setTextSize(18);
        control.setMinHeight(dp(c, 56));
        control.setChecked(value);
        control.setOnCheckedChangeListener((v, on) -> preferences.edit().putBoolean(key, on).apply());
        panel.addView(control);
    }
    private static void options(LinearLayout panel, Context c, String title, String[] labels, int selected, Selection selection) {
        panel.addView(label(c, title, 17));
        RadioGroup group = new RadioGroup(c);
        group.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(c);
            b.setId(View.generateViewId());
            b.setText(labels[i]);
            b.setTextSize(16);
            b.setTag(i);
            group.addView(b, new RadioGroup.LayoutParams(0, -2, 1f));
            if (i == selected) b.setChecked(true);
        }
        group.setOnCheckedChangeListener((g, id) -> { View v = g.findViewById(id); if (v != null) selection.select((Integer) v.getTag()); });
        panel.addView(group);
    }
    private interface Selection { void select(int index); }
    /** A ScrollView that stops growing at maxHeight, so the windowed panel keeps clear of the screen edges. */
    private static final class LimitedScroll extends ScrollView {
        int maxHeight;
        LimitedScroll(Context c) { super(c); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            if (maxHeight > 0) heightSpec = View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST);
            super.onMeasure(widthSpec, heightSpec);
        }
    }
    private static Button button(Context c, String text, View.OnClickListener click) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setMinHeight(dp(c, 56));
        b.setOnClickListener(click);
        return b;
    }
    private static LinearLayout.LayoutParams spaced(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, 12);
        return lp;
    }
    /**
     * Sizes the panel inside the area not covered by the head unit's status and dock bars.
     * Window: at most 720 dp wide and 85 % of that area high, the rest scrolls. Full screen: the whole area less a margin.
     */
    private static void layout(Activity activity, AlertDialog dialog, LimitedScroll scroll, boolean full) {
        Rect area = new Rect();
        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(area);
        if (area.width() <= 0 || area.height() <= 0) {
            area.set(0, 0, activity.getResources().getDisplayMetrics().widthPixels, activity.getResources().getDisplayMetrics().heightPixels);
        }
        int margin = dp(activity, 24);
        Window window = dialog.getWindow();
        if (full) {
            scroll.maxHeight = 0;
            window.setLayout(area.width() - 2 * margin, area.height() - 2 * margin);
        } else {
            // Title and buttons take about 140 dp; the content scrolls within the rest.
            scroll.maxHeight = Math.max(dp(activity, 200), (int) (area.height() * .85f) - dp(activity, 140));
            window.setLayout(Math.min(dp(activity, 720), (int) (area.width() * .85f)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        scroll.requestLayout();
        Button mode = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (mode != null) mode.setText(full ? "Window" : "Full screen");
    }
    private static void slider(LinearLayout panel, Context c, String title, String key, int min, int max, int value) {
        TextView text = label(c, title + ": " + value + "%", 17);
        panel.addView(text);
        SeekBar seek = new SeekBar(c);
        seek.setMax(max - min);
        seek.setProgress(value - min);
        seek.setContentDescription(title);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int progress, boolean user) {
                int n = progress + min;
                text.setText(title + ": " + n + "%");
                if (user) preferences.edit().putInt(key, n).apply();
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        panel.addView(seek, new LinearLayout.LayoutParams(-1, dp(c, 48)));
    }
    /** Restarts Waze; a guidance card left by the old process is cleared by the next launch. */
    static void restart(Activity a) {
        Intent launch = a.getPackageManager().getLaunchIntentForPackage(a.getPackageName());
        if (launch == null || launch.getComponent() == null) {
            Toast.makeText(a, "Close Waze completely and open it again.", Toast.LENGTH_LONG).show();
            return;
        }
        android.util.Log.i("WazeClusterTest", "restarting Waze from cluster settings");
        a.startActivity(Intent.makeRestartActivityTask(launch.getComponent()));
        Runtime.getRuntime().exit(0);
    }
    static void show(Activity activity) {
        Context c = new ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        // Generous padding keeps switches and buttons away from the panel edges on the car screen.
        panel.setPadding(dp(c, 32), dp(c, 16), dp(c, 32), dp(c, 16));
        ClusterSettings.State s = ClusterSettings.state(activity);
        toggle(panel, c, "Show map on instrument cluster", ClusterSettings.MAP_ENABLED, s.mapEnabled);
        toggle(panel, c, "Send turn guidance to cluster and HUD", ClusterSettings.VEHICLE_GUIDANCE, s.vehicleGuidance);
        panel.addView(label(c, ClusterStatus.hud() + " Use only one HUD navigation app at a time.", 14));
        if (ClusterStatus.untestedHudAvailable()) {
            Switch hud = new Switch(c);
            hud.setText("Try standalone HUD on this firmware (experimental)");
            hud.setTextSize(18);
            hud.setMinHeight(dp(c, 56));
            hud.setChecked(s.hudUntested);
            Button apply = button(c, "Restart Waze to apply", v -> restart(activity));
            apply.setVisibility(s.hudUntested != ClusterStatus.standaloneActive() ? View.VISIBLE : View.GONE);
            hud.setOnCheckedChangeListener((v, on) -> {
                preferences.edit().putBoolean(ClusterSettings.HUD_UNTESTED, on).commit();
                apply.setVisibility(on != ClusterStatus.standaloneActive() ? View.VISIBLE : View.GONE);
            });
            panel.addView(hud);
            panel.addView(label(c, "First turn off HUD output in OpenBYD or any other HUD app. Restart Waze to apply.", 14));
            panel.addView(apply, spaced(c));
        }
        // Waze draws the cluster with its shared map colours; it has no separate cluster style to choose live.
        panel.addView(label(c, "Cluster map colours follow Waze: its map colour setting, Apply for car display and the head unit theme. "
                + ClusterStatus.theme(), 14));
        slider(panel, c, "Pointer vertical offset (- up, + down)", ClusterSettings.POINTER_VERTICAL, -25, 25, s.pointerVertical);
        // Android 13 BYD builds (DiLink 5.1); also kept visible once the owner has chosen a profile.
        if (Build.VERSION.SDK_INT >= 33 || s.profile != DiLink51Layout.Profile.AUTOMATIC) {
            panel.addView(label(c, "DiLink 5.1 cluster layout", 18));
            options(panel, c, "Layout profile", new String[]{"Automatic", "DiLink 5.1", "DiLink 5"}, s.profile.ordinal(),
                    i -> preferences.edit().putString(ClusterSettings.PROFILE, DiLink51Layout.Profile.values()[i].name()).apply());
            options(panel, c, "Cluster theme", new String[]{"Scenario", "Map", "Simple", "Follow"}, s.mode.ordinal(),
                    i -> preferences.edit().putString(ClusterSettings.MODE, DiLink51Layout.Mode.values()[i].name()).apply());
            options(panel, c, "Map theme background", new String[]{"Theme default", "Light", "Dark"}, s.contrast.ordinal(),
                    i -> preferences.edit().putString(ClusterSettings.CONTRAST, DiLink51Layout.Contrast.values()[i].name()).apply());
            panel.addView(label(c, "Automatic uses the DiLink 5.1 layout only on verified firmware. Follow tracks the cluster's theme and needs Usage Access, "
                    + "granted once from a computer: adb shell appops set com.waze GET_USAGE_STATS allow", 14));
        }
        panel.addView(label(c, "Changes apply immediately and are saved automatically.", 14));
        panel.addView(button(c, "Support and diagnostics", v -> {
            try { activity.startActivity(new Intent().setClassName(activity, "local.waze.diagnostics.SupportActivity")); }
            catch (RuntimeException e) { Toast.makeText(activity, "Support is unavailable.", Toast.LENGTH_LONG).show(); }
        }), spaced(c));
        LimitedScroll scroll = new LimitedScroll(c);
        scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(c).setTitle("Instrument cluster").setView(scroll)
                .setNeutralButton("Full screen", null).setPositiveButton("Done", null).create();
        final boolean[] full = {s.panelFullscreen};
        dialog.setOnShowListener(d -> {
            // Switching the size keeps the panel open; the choice is remembered for next time.
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> {
                full[0] = !full[0];
                preferences.edit().putBoolean(ClusterSettings.PANEL_FULLSCREEN, full[0]).apply();
                layout(activity, dialog, scroll, full[0]);
            });
            layout(activity, dialog, scroll, full[0]);
        });
        dialog.show();
    }
}
