package org.tomahawk.spotifyplugin;

import com.spotify.sdk.android.player.Config;
import com.spotify.sdk.android.player.ConnectionStateCallback;
import com.spotify.sdk.android.player.PlaybackBitrate;
import com.spotify.sdk.android.player.Player;
import com.spotify.sdk.android.player.PlayerNotificationCallback;
import com.spotify.sdk.android.player.PlayerState;
import com.spotify.sdk.android.player.PlayerStateCallback;
import com.spotify.sdk.android.player.Spotify;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.concurrent.RejectedExecutionException;

public class SpotifyService extends Service {

    // Used for debug logging
    private static final String TAG = SpotifyService.class.getSimpleName();

    /**
     * Command to the service to register a client, receiving callbacks from the service. The
     * Message's replyTo field must be a Messenger of the client where callbacks should be sent.
     */
    static final int MSG_REGISTER_CLIENT = 1;

    /**
     * Command to the service to unregister a client, ot stop receiving callbacks from the service.
     * The Message's replyTo field must be a Messenger of the client as previously given with
     * MSG_REGISTER_CLIENT.
     */
    static final int MSG_UNREGISTER_CLIENT = 2;

    /**
     * Commands to the service
     */
    private static final int MSG_PREPARE = 100;

    private static final String MSG_PREPARE_ARG_URI = "uri";

    private static final String MSG_PREPARE_ARG_ACCESSTOKEN = "accessToken";

    private static final String MSG_PREPARE_ARG_ACCESSTOKENEXPIRES = "accessTokenExpires";

    private static final int MSG_PLAY = 101;

    private static final int MSG_PAUSE = 102;

    private static final int MSG_SEEK = 103;

    private static final String MSG_SEEK_ARG_MS = "ms";

    private static final int MSG_SETBITRATE = 104;

    private static final String MSG_SETBITRATE_ARG_MODE = "mode";

    /**
     * Commands to the client
     */
    private static final int MSG_ONPAUSE = 200;

    private static final int MSG_ONPLAY = 201;

    private static final int MSG_ONPREPARED = 202;

    protected static final String MSG_ONPREPARED_ARG_URI = "uri";

    private static final int MSG_ONPLAYERENDOFTRACK = 203;

    private static final int MSG_ONPLAYERPOSITIONCHANGED = 204;

    private static final String MSG_ONPLAYERPOSITIONCHANGED_ARG_POSITION = "position";

    private static final String MSG_ONPLAYERPOSITIONCHANGED_ARG_TIMESTAMP = "timestamp";

    private static final int MSG_ONERROR = 205;

    private static final String MSG_ONERROR_ARG_MESSAGE = "message";

    private WifiManager.WifiLock mWifiLock;

    private Player mPlayer;

    private boolean mIsResuming;

    private boolean mIsPausing;

    private String mPreparingUri;

    private String mPreparedUri;

    /**
     * Keeps track of all current registered clients.
     */
    ArrayList<Messenger> mClients = new ArrayList<>();

    /**
     * Target we publish for clients to send messages to IncomingHandler.
     */
    final Messenger mMessenger = new Messenger(new IncomingHandler(this));

    /**
     * Handler of incoming messages from clients.
     */
    private static class IncomingHandler extends WeakReferenceHandler<SpotifyService> {

        public IncomingHandler(SpotifyService referencedObject) {
            super(referencedObject);
        }

        @Override
        public void handleMessage(Message msg) {
            final SpotifyService s = getReferencedObject();
            switch (msg.what) {
                case MSG_REGISTER_CLIENT:
                    s.mClients.add(msg.replyTo);
                    break;
                case MSG_UNREGISTER_CLIENT:
                    s.mClients.remove(msg.replyTo);
                    break;
                case MSG_PREPARE:
                    String uri = msg.getData().getString(MSG_PREPARE_ARG_URI);
                    String accessToken =
                            msg.getData().getString(MSG_PREPARE_ARG_ACCESSTOKEN);

                    if (s.mPlayer != null) {
                        s.mPlayer.removeConnectionStateCallback(s.mConnectionStateCallback);
                        s.mPlayer.removePlayerNotificationCallback(s.mPlayerNotificationCallback);
                        s.mPlayer.pause();
                        s.mPlayer = null;
                    }
                    Log.d(TAG, "(Re-)initializing Player object...");
                    Config playerConfig = new Config(s, accessToken, BuildConfig.CLIENT_ID);
                    Player.Builder builder = new Player.Builder(playerConfig);
                    s.mPlayer = Spotify.getPlayer(builder, this,
                            new Player.InitializationObserver() {
                                @Override
                                public void onInitialized(Player player) {
                                    player.addConnectionStateCallback(
                                            s.mConnectionStateCallback);
                                    player.addPlayerNotificationCallback(
                                            s.mPlayerNotificationCallback);
                                }

                                @Override
                                public void onError(Throwable throwable) {
                                    Log.e(TAG, "Could not initialize player: "
                                            + throwable.getMessage());
                                }
                            });
                    s.reportPosition(0);
                    Log.d(TAG, "Preparing uri: " + uri);
                    s.mPreparingUri = uri;
                    s.mPlayer.play(s.mPreparingUri);
                    break;
                case MSG_PLAY:
                    Log.d(TAG, "play called");
                    if (s.mPlayer != null) {
                        try {
                            s.mPlayer.getPlayerState(new PlayerStateCallback() {
                                @Override
                                public void onPlayerState(PlayerState playerState) {
                                    if (!s.mIsResuming && !playerState.playing) {
                                        Log.d(TAG, "play - resuming playback");
                                        s.mIsResuming = true;
                                        s.mPlayer.resume();
                                    }
                                }
                            });
                        } catch (RejectedExecutionException e) {
                            Log.e(TAG, "play - " + e.getLocalizedMessage());
                        }
                    }
                    break;
                case MSG_PAUSE:
                    Log.d(TAG, "pause called");
                    if (s.mPlayer != null) {
                        try {
                            s.mPlayer.getPlayerState(new PlayerStateCallback() {
                                @Override
                                public void onPlayerState(PlayerState playerState) {
                                    if (!s.mIsPausing && playerState.playing) {
                                        Log.d(TAG, "pause - pausing playback");
                                        s.mIsPausing = true;
                                        s.mPlayer.pause();
                                    }
                                }
                            });
                        } catch (RejectedExecutionException e) {
                            Log.e(TAG, "pause - " + e.getLocalizedMessage());
                        }
                    }
                    break;
                case MSG_SEEK:
                    int ms = msg.getData().getInt(MSG_SEEK_ARG_MS);

                    Log.d(TAG, "seek()");
                    if (s.mPlayer != null) {
                        try {
                            ms = Math.max(ms, 1);
                            Log.d(TAG, "seek - seeking to " + ms + "ms");
                            s.mPlayer.seekToPosition(ms);
                            s.reportCurrentPosition(s.mPlayer);
                        } catch (RejectedExecutionException e) {
                            Log.e(TAG, "seek - " + e.getLocalizedMessage());
                        }
                    }
                    break;
                case MSG_SETBITRATE:
                    int mode = msg.getData().getInt(MSG_SETBITRATE_ARG_MODE);

                    if (s.mPlayer != null) {
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
                                s.mPlayer.setPlaybackBitrate(bitrate);
                            } catch (RejectedExecutionException e) {
                                Log.e(TAG, "setBitrate - " + e.getLocalizedMessage());
                            }
                        } else {
                            Log.d(TAG, "Invalid bitratemode given");
                        }
                    }
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

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
            if (playerState.trackUri != null) {
                if (playerState.trackUri.equals(mPreparingUri)) {
                    Bundle args;
                    switch (eventType) {
                        case TRACK_START:
                            Log.d(TAG, "Successfully prepared uri: " + mPreparingUri);
                            mPreparedUri = mPreparingUri;
                            mPreparingUri = null;
                            mPlayer.pause();
                            args = new Bundle();
                            args.putString(MSG_ONPREPARED_ARG_URI, mPreparedUri);
                            broadcastToAll(MSG_ONPREPARED, args);
                            reportCurrentPosition(mPlayer);
                            break;
                    }
                }
                if (mPreparingUri == null && playerState.trackUri.equals(mPreparedUri)) {
                    Bundle args;
                    switch (eventType) {
                        case TRACK_END:
                            broadcastToAll(MSG_ONPLAYERENDOFTRACK);
                            break;
                        case PAUSE:
                            mIsPausing = false;
                            broadcastToAll(MSG_ONPAUSE);
                            break;
                        case PLAY:
                            mIsResuming = false;
                            broadcastToAll(MSG_ONPLAY);
                            break;
                        case LOST_PERMISSION:
                            args = new Bundle();
                            args.putString(MSG_ONERROR_ARG_MESSAGE,
                                    "Spotify is currently being used on a different device.");
                            broadcastToAll(MSG_ONERROR, args);
                    }
                }
            }
        }

        @Override
        public void onPlaybackError(final ErrorType errorType, final String errorDetails) {
            Log.e(TAG, "Playback error received: " + errorType.name());
            Bundle args = new Bundle();
            args.putString(MSG_ONERROR_ARG_MESSAGE,
                    errorType.name() + ": " + errorDetails);
            broadcastToAll(MSG_ONERROR, args);
        }
    };

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
            mPlayer.removeConnectionStateCallback(mConnectionStateCallback);
            mPlayer.removePlayerNotificationCallback(mPlayerNotificationCallback);
            mPlayer = null;
        }
        Spotify.destroyPlayer(this);
        mWifiLock.release();
        Log.d(TAG, "SpotifyService has been destroyed");

        super.onDestroy();
    }

    /**
     * When binding to the service, we return an interface to our messenger for sending messages to
     * the service.
     */
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Client has been bound to SpotifyService");
        return mMessenger.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "Client has been unbound from SpotifyService");
        return super.onUnbind(intent);
    }

    private void reportCurrentPosition(Player player) {
        if (player != null) {
            player.getPlayerState(new PlayerStateCallback() {
                @Override
                public void onPlayerState(PlayerState playerState) {
                    reportPosition(playerState.positionInMs);
                }
            });
        } else {
            Log.e(TAG, "Wasn't able to reportCurrentPosition, because given Player is was null!");
        }
    }

    private void reportPosition(int positionInMs) {
        Bundle args = new Bundle();
        args.putInt(MSG_ONPLAYERPOSITIONCHANGED_ARG_POSITION, positionInMs);
        args.putLong(MSG_ONPLAYERPOSITIONCHANGED_ARG_TIMESTAMP, System.currentTimeMillis());
        broadcastToAll(MSG_ONPLAYERPOSITIONCHANGED, args);
    }

    private void broadcastToAll(int what) {
        broadcastToAll(what, null);
    }

    private void broadcastToAll(int what, Bundle bundle) {
        for (int i = mClients.size() - 1; i >= 0; i--) {
            try {
                Message message = Message.obtain(null, what);
                message.setData(bundle);
                mClients.get(i).send(message);
            } catch (RemoteException e) {
                // The client is dead.  Remove it from the list;
                // we are going through the list from back to front
                // so this is safe to do inside the loop.
                mClients.remove(i);
            }
        }
    }
}
