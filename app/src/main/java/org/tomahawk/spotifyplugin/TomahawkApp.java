package org.tomahawk.spotifyplugin;

import org.acra.ACRA;
import org.acra.ReportingInteractionMode;
import org.acra.annotation.ReportsCrashes;
import org.acra.sender.HttpSender;

import android.app.Application;
import android.os.StrictMode;
import android.util.Log;

/**
 * This class represents the Application core.
 */
@ReportsCrashes(
        httpMethod = HttpSender.Method.PUT,
        reportType = HttpSender.Type.JSON,
        formUri = "http://crash-stats.tomahawk-player.org:5984/acra-tomahawkandroid/_design/acra-storage/_update/report",
        formUriBasicAuthLogin = "reporter",
        formUriBasicAuthPassword = "unknackbar",
        excludeMatchingSharedPreferencesKeys = {".*_config$"},
        mode = ReportingInteractionMode.SILENT,
        logcatArguments = {"-t", "2000", "-v", "time"})
public class TomahawkApp extends Application {

    private static final String TAG = TomahawkApp.class.getSimpleName();

    @Override
    public void onCreate() {
        ACRA.init(this);

        StrictMode.setThreadPolicy(
                new StrictMode.ThreadPolicy.Builder().detectCustomSlowCalls().detectDiskReads()
                        .detectDiskWrites().detectNetwork().penaltyLog().build());
        try {
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .setClassInstanceLimit(Class.forName(SpotifyService.class.getName()), 1)
                    .penaltyLog().build());
        } catch (ClassNotFoundException e) {
            Log.e(TAG, e.toString());
        }

        super.onCreate();
    }

}
