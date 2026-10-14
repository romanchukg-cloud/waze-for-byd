package local.waze.diagnostics;

import android.app.job.*;

/** Network-constrained, persisted one-shot job. No always-running service or foreground notification. */
public final class DiagnosticsJob extends JobService {
    private volatile boolean stopped;
    public boolean onStartJob(JobParameters params) {
        stopped=false;
        Diagnostics d=Diagnostics.get(this);
        if(d==null)return false;
        d.work(() -> {if(!stopped)jobFinished(params,false);});
        return true;
    }
    public boolean onStopJob(JobParameters params) {stopped=true;return true;}
}
