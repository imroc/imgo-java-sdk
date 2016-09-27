package com.imgo.bootstrap;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by roc
 */
public class PushClient {

    private static short DEFAULT_MESSAGE_SIZE = 1024;

    private final AtomicReference<ConnectionState> state = new AtomicReference<ConnectionState>(ConnectionState.STOPPED);
    protected InetAddress server;
    protected int port;
    private int defaultBufferSize;
    private int defaultHeartBeatTimeOut = 20000;
    private int defaultSocketTimeOut = 3 * 60 * 1000;
    protected long uid;
    protected long mid;
    protected String token;
    protected byte[] authMessage;
    protected byte[] heartbeatMessage;
    protected ClientEventListener clientEventListener;
    private final AtomicReference<DataOutputStream> out = new AtomicReference<DataOutputStream>();
    private final AtomicReference<DataInputStream> in = new AtomicReference<DataInputStream>();
    private final ConnectTask connectTask = new ConnectTask();
    private final HeartbeatTask heartbeatTask = new HeartbeatTask();
    private final PullOfflineMessageTask pullOfflineMessageTask = new PullOfflineMessageTask();

    /**
     * Construct an unstarted client which will attempt to connect to the given
     * server on the given port.
     *

     * @param uid
     *            the user id.
     * @param token
     *            the token,used for authentication.
     */
    public PushClient(String cometAddress, long uid, String token) throws UnknownHostException {
        this(cometAddress, uid, token, DEFAULT_MESSAGE_SIZE);
    }

    /**
     * Construct an unstarted client which will attempt to connect to the given
     * server on the given port.
     *
     * @param uid
     *            the user id.
     * @param token
     *            the token,used for authentication.
     * @param defaultBufferSize
     *            the default buffer size for reads. This should as small as
     *            possible value that doesn't get exceeded often - see class
     *            documentation.
     */
    public PushClient(String cometAddress, long uid, String token, int defaultBufferSize) throws UnknownHostException {
        String[] cometInfo = cometAddress.split(":");
        if(cometInfo.length!=2){
            throw new RuntimeException("illegal comet address");
        }

        this.server = InetAddress.getByName(cometInfo[0]);
        this.port = Integer.parseInt(cometInfo[1]);

        this.uid = uid;
        this.token = token;
        this.defaultBufferSize = defaultBufferSize;

        byte[] tokenBytes = token.getBytes();
        int tokenLength = tokenBytes.length+16;
        byte[] message;
        int offset;

        //init auth message
        message = new byte[4 + 2 + 2 + 4 + 4];
        // package length
        offset = BruteForceCoding.encodeIntBigEndian(message, tokenLength, 0, 4 * BruteForceCoding.BSIZE);
        // header lenght
        offset = BruteForceCoding.encodeIntBigEndian(message, 16, offset, 2 * BruteForceCoding.BSIZE);
        // ver
        offset = BruteForceCoding.encodeIntBigEndian(message, 1, offset, 2 * BruteForceCoding.BSIZE);
        // operation
        offset = BruteForceCoding.encodeIntBigEndian(message, 7, offset, 4 * BruteForceCoding.BSIZE);
        // jsonp callback
        BruteForceCoding.encodeIntBigEndian(message, 1, offset, 4 * BruteForceCoding.BSIZE);
        this.authMessage = BruteForceCoding.add(message, tokenBytes);


        //init heartbeat message
        message = new byte[4 + 2 + 2 + 4 + 4];
        // package length
        offset = BruteForceCoding.encodeIntBigEndian(message, tokenLength, 0, 4 * BruteForceCoding.BSIZE);
        // header lenght
        offset = BruteForceCoding.encodeIntBigEndian(message, 16, offset, 2 * BruteForceCoding.BSIZE);
        // ver
        offset = BruteForceCoding.encodeIntBigEndian(message, 1, offset, 2 * BruteForceCoding.BSIZE);
        // operation
        offset = BruteForceCoding.encodeIntBigEndian(message, 2, offset, 4 * BruteForceCoding.BSIZE);
        // jsonp callback
        BruteForceCoding.encodeIntBigEndian(message, 1, offset, 4 * BruteForceCoding.BSIZE);
        this.heartbeatMessage = BruteForceCoding.add(message, tokenBytes);

    }

    public void setClientEventListener(ClientEventListener listener){
        this.clientEventListener = listener;
    }

    public void setMid(long mid){
        this.mid = mid;
    }



    public void start(){
        new Thread(this.connectTask).start();
    }


    /**
     * Stop the client in a graceful manner. After this call the client may
     * spend some time in the process of stopping. A disconnected callback will
     * occur when the client actually stops.
     *
     * @return if the client was successfully set to stop.
     */
    public boolean stop() {
        if (state.compareAndSet(ConnectionState.RUNNING, ConnectionState.STOPPING)) {
            if(clientEventListener!=null){
                clientEventListener.onConnectionStateChanged(state.get());
            }
            try {
                in.get().close();
            } catch (IOException e) {
                if(clientEventListener!=null){
                    clientEventListener.onError(e);
                }
                return false;
            }
            return true;
        }
        return false;
    }


    private synchronized Boolean authWrite() throws IOException {
        write(authMessage);
        return true;
    }

    private synchronized Boolean heartBeatWrite() throws IOException {
        write(heartbeatMessage);
        return true;
    }


    private void write(byte[] msg) throws IOException {
        out.get().write(msg);
        out.get().flush();
    }

    private void heartBeat() {
        new Thread(this.heartbeatTask).start();
    }


    private class HeartbeatTask implements Runnable {

        @Override
        public void run() {

            while (true) {
                try {
                    Thread.sleep(defaultHeartBeatTimeOut);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                try {
                    heartBeatWrite();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class ConnectTask implements Runnable {
        /**
         * Attempt to connect to the server and receive messages. If the client is
         * already running, it will not be started again. This method is designed to
         * be called in its own thread and will not return until the client is
         * stopped.
         *
         * @throws RuntimeException
         *             if the client fails
         */
        public void run() {
            Socket socket = null;
            try {
                socket = new Socket(server, port);
                socket.setSoTimeout(defaultSocketTimeOut);

                out.set(new DataOutputStream(socket.getOutputStream()));
                in.set(new DataInputStream(socket.getInputStream()));

                if (!state.compareAndSet(ConnectionState.STOPPED, ConnectionState.RUNNING)) {
                    return;
                }

                if(clientEventListener!=null){
                    clientEventListener.onConnectionStateChanged(state.get());
                }

                authWrite();

                while (state.get() == ConnectionState.RUNNING) {

                    byte[] inBuffer = new byte[defaultBufferSize];
                    int readPoint = in.get().read(inBuffer);
                    if (readPoint != -1) {
                        long operation = BruteForceCoding.decodeIntBigEndian(inBuffer, 8, 4);
                        switch ((int)operation){
                            case 3:
                                //heartbeat...
                                break;
                            case 8:
                                if(clientEventListener!=null){
                                    clientEventListener.onAuth(true);
                                    //TODO 改造服务端，如果认真失败，应该给个返回数据告诉客户端是否认证成功。
                                }
                                heartBeat();
                                break;
                            case 5:
                                //Long packageLength = BruteForceCoding.decodeIntBigEndian(inBuffer, 0, 4);
                                //Long headLength = BruteForceCoding.decodeIntBigEndian(inBuffer, 4, 2);
                                Long version = BruteForceCoding.decodeIntBigEndian(inBuffer, 6, 2);
                                //Long sequenceId = BruteForceCoding.decodeIntBigEndian(inBuffer, 12, 4);
                                if(clientEventListener!=null){
                                    byte[] result = BruteForceCoding.tail(inBuffer, inBuffer.length - 16);
                                    clientEventListener.onMessage(version,new String(result).trim());
                                }
                                //messageReceived(packageLength, headLength, version,operation, sequenceId,new String(result).trim());
                                //messageReceived(new String(result).trim());
                                break;
                        }
                    }
                }
            } catch (Exception ioe) {
                if(clientEventListener!=null){
                    clientEventListener.onError(ioe);
                }
                try {
                    socket.close();
                    state.set(ConnectionState.STOPPED);
                    if(clientEventListener!=null){
                        clientEventListener.onConnectionStateChanged(state.get());
                    }
                } catch (Exception e) {
                    // do nothing - server failed
                    if(clientEventListener!=null){
                        clientEventListener.onError(e);
                    }
                }
                //start();
            }
        }
    }
}
