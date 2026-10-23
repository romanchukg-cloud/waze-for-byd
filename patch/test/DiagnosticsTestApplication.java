// SPDX-License-Identifier: GPL-3.0-only
package local.waze.diagnostics;
public final class DiagnosticsTestApplication extends android.app.Application {
    public void onCreate() {
        super.onCreate();Diagnostics.install(this);
        Diagnostics d=Diagnostics.get(this);
        d.execute(() -> {
            DiagnosticsTests.Fake fake=new DiagnosticsTests.Fake();fake.offline=true;
            d.engine=new ReportEngine(d.store,fake);
        },() -> {});
    }
}
