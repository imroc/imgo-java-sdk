package com.goim.bootstrap;

/**
 * Created by cpwl
 */
public interface ClientEventListener {
    void onConnectionStateChanged(ConnectionState currentState);
    void onError(Exception e);
    void onAuth(boolean success);
    void onMessage(long version,String message);
}
