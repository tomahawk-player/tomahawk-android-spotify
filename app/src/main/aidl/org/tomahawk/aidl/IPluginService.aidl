package org.tomahawk.aidl;

import org.tomahawk.aidl.IPluginServiceCallback;

interface IPluginService {

    void registerCallback(IPluginServiceCallback cb);

    void unregisterCallback(IPluginServiceCallback cb);

    void prepare(String uri, String accessToken, String accessTokenSecret, long accessTokenExpires);

    void play();

    void pause();

    void seek(int ms);

    void setBitRate(int mode);
}
