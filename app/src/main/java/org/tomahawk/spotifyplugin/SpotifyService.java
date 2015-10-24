package org.tomahawk.spotifyplugin;

import com.spotify.sdk.android.player.Config;
import com.spotify.sdk.android.player.ConnectionStateCallback;
import com.spotify.sdk.android.player.PlaybackBitrate;
import com.spotify.sdk.android.player.Player;
import com.spotify.sdk.android.player.PlayerNotificationCallback;
import com.spotify.sdk.android.player.PlayerState;
import com.spotify.sdk.android.player.PlayerStateCallback;
import com.spotify.sdk.android.player.Spotify;

import org.tomahawk.aidl.IPluginService;
import org.tomahawk.aidl.IPluginServiceCallback;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.RejectedExecutionException;

public class SpotifyService extends Service {

    // Used for debug logging
    private static final String TAG = SpotifyService.class.getSimpleName();

    public static final String CLIENT_ID = "";

    private WifiManager.WifiLock mWifiLock;

    private Player mPlayer;

    private String mPreparedUri;

    private boolean mIsPlaying;

    /**
     * This is a list of callbacks that have been registered with the service.  Note that this is
     * package scoped (instead of private) so that it can be accessed more efficiently from inner
     * classes.
     */
    final RemoteCallbackList<IPluginServiceCallback> mCallbacks = new RemoteCallbackList<>();

    /**
     * The IRemoteInterface is defined through IDL
     */
    private final IPluginService.Stub mBinder = new IPluginService.Stub() {

        @Override
        public void registerCallback(IPluginServiceCallback cb) {
            if (cb != null) {
                mCallbacks.register(cb);
            }
        }

        @Override
        public void unregisterCallback(IPluginServiceCallback cb) {
            if (cb != null) {
                mCallbacks.unregister(cb);
            }
        }

        @Override
        public void prepare(String uri, String accessToken, String accessTokenSecret,
                long accessTokenExpires) throws RemoteException {
            if (mPlayer == null) {
                Config playerConfig = new Config(SpotifyService.this, accessToken, CLIENT_ID);
                Player.Builder builder = new Player.Builder(playerConfig);
                mPlayer = Spotify.getPlayer(builder, this,
                        new Player.InitializationObserver() {
                            @Override
                            public void onInitialized(Player player) {
                                player.addConnectionStateCallback(mConnectionStateCallback);
                                player.addPlayerNotificationCallback(mPlayerNotificationCallback);
                                if (mIsPlaying) {
                                    player.play(mPreparedUri);
                                }
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                Log.e(TAG,
                                        "Could not initialize player: " + throwable.getMessage());
                            }
                        });
            } else {
                mPlayer.login(accessToken);
            }

            mPreparedUri = uri;

            broadcastToAll(new BroadcastRunnable() {
                @Override
                public void broadcast(IPluginServiceCallback callback) throws RemoteException {
                    callback.onPrepared();
                }
            });
        }

        @Override
        public void play() throws RemoteException {
            mIsPlaying = true;
            if (mPlayer != null) {
                try {
                    mPlayer.getPlayerState(new PlayerStateCallback() {
                        @Override
                        public void onPlayerState(PlayerState playerState) {
                            if (!playerState.trackUri.equals(mPreparedUri)) {
                                mPlayer.play(mPreparedUri);
                            } else if (!playerState.playing) {
                                mPlayer.resume();
                            }
                        }
                    });
                } catch (RejectedExecutionException e) {
                    Log.e(TAG, "play - " + e.getLocalizedMessage());
                }
            }
        }

        @Override
        public void pause() throws RemoteException {
            mIsPlaying = false;
            if (mPlayer != null) {
                try {
                    mPlayer.pause();
                } catch (RejectedExecutionException e) {
                    Log.e(TAG, "pause - " + e.getLocalizedMessage());
                }
            }
        }

        @Override
        public void seek(final int ms) throws RemoteException {
            if (mPlayer != null) {
                try {
                    mPlayer.seekToPosition(ms);

                    broadcastToAll(new BroadcastRunnable() {
                        @Override
                        public void broadcast(IPluginServiceCallback callback)
                                throws RemoteException {
                            callback.onPlayerPositionChanged(ms, System.currentTimeMillis());
                        }
                    });
                } catch (RejectedExecutionException e) {
                    Log.e(TAG, "seek - " + e.getLocalizedMessage());
                }
            }
        }

        @Override
        public void setBitRate(int mode) throws RemoteException {
            if (mPlayer != null) {
                PlaybackBitrate bitrate = null;
                switch (mode) {
                    case 0:
                        bitrate = PlaybackBitrate.BITRATE_LOW;
                        break;
                    case 1:
                        bitrate = PlaybackBitrate.BITRATE_NORMAL;
                        break;
                    case 2:
                        bitrate = PlaybackBitrate.BITRATE_HIGH;
                        break;
                }
                if (bitrate != null) {
                    try {
                        mPlayer.setPlaybackBitrate(bitrate);
                    } catch (RejectedExecutionException e) {
                        Log.e(TAG, "setBitrate - " + e.getLocalizedMessage());
                    }
                } else {
                    Log.d(TAG, "Invalid bitratemode given");
                }
            }
        }
    };

    private final ConnectionStateCallback mConnectionStateCallback = new ConnectionStateCallback() {

        @Override
        public void onLoggedIn() {
            Log.d(TAG, "User logged in");
        }

        @Override
        public void onLoggedOut() {
            Log.d(TAG, "User logged out");
        }

        @Override
        public void onLoginFailed(Throwable error) {
            Log.e(TAG, "Login failed: " + error.getLocalizedMessage());
        }

        @Override
        public void onTemporaryError() {
            Log.e(TAG, "Temporary error occurred");
        }

        @Override
        public void onConnectionMessage(String message) {
            Log.d(TAG, "Received connection message: " + message);
        }
    };

    private final PlayerNotificationCallback mPlayerNotificationCallback
            = new PlayerNotificationCallback() {

        @Override
        public void onPlaybackEvent(final EventType eventType, PlayerState playerState) {
            Log.d(TAG, "Playback event received: " + eventType.name());
            if (playerState.trackUri.equals(mPreparedUri)) {
                broadcastToAll(new BroadcastRunnable() {
                    @Override
                    public void broadcast(IPluginServiceCallback callback) throws RemoteException {
                        switch (eventType) {
                            case TRACK_END:
                                callback.onPlayerEndOfTrack();
                                break;
                            case PAUSE:
                                callback.onPause();
                                break;
                            case PLAY:
                                callback.onPlay();
                                break;
                        }
                    }
                });
            }
        }

        @Override
        public void onPlaybackError(final ErrorType errorType, final String errorDetails) {
            Log.e(TAG, "Playback error received: " + errorType.name());
            broadcastToAll(new BroadcastRunnable() {
                @Override
                public void broadcast(IPluginServiceCallback callback) throws RemoteException {
                    callback.onError(errorType.name() + ": " + errorDetails);
                }
            });
        }
    };

    interface BroadcastRunnable {

        void broadcast(IPluginServiceCallback callback) throws RemoteException;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mWifiLock = ((WifiManager) getSystemService(Context.WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL, "mylock");
        mWifiLock.acquire();
        Log.d(TAG, "SpotifyService has been created");
    }

    @Override
    public void onDestroy() {
        if (mPlayer != null) {
            mPlayer.pause();
            mPlayer = null;
        }
        Spotify.destroyPlayer(this);
        mWifiLock.release();
        mCallbacks.kill();
        Log.d(TAG, "SpotifyService has been destroyed");

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Client has been bound to SpotifyService");
        if (IPluginService.class.getName().equals(intent.getAction())) {
            return mBinder;
        }
        return null;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "Client has been unbound from SpotifyService");
        stopSelf();
        return false;
    }

    private void broadcastToAll(BroadcastRunnable runnable) {
        // Broadcast to all clients
        final int N = mCallbacks.beginBroadcast();
        for (int i = 0; i < N; i++) {
            try {
                runnable.broadcast(mCallbacks.getBroadcastItem(i));
            } catch (RemoteException e) {
                // The RemoteCallbackList will take care of removing
                // the dead object for us.
            }
        }
        mCallbacks.finishBroadcast();
    }
}
