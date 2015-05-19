package org.tomahawk.aidl;

oneway interface IPluginServiceCallback {

    void onPause();

    void onPlay();

    void onPrepared();

    void onPlayerEndOfTrack();

    void onPlayerPositionChanged(int position, long timeStamp);
    
    void onError(String message);
}
