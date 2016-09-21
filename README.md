# imgo-java-sdk
java sdk for imgo

# Usage

``` java
String pushAddress = "push.imgo.com:8080";
String offlineMsgAddress = "msg.imgo.com:8099";
long userid = 88888888888L;
String token = "abcdefg123456789"
//use sdk to connect imgo
try{
    PushClient client = new PushClient(pushAddress, offlineMsgAddress,userid,token);
    client.setClientEventListener(new ClientEventListener() {
        @Override
        public void onConnectionStateChanged(ConnectionState currentState) {
            System.out.println("current state is "+ currentState.name())
        }

        @Override
        public void onError(Exception e) {
            System.out.println("发生错误："+e.getMessage());
        }

        @Override
        public void onAuth(boolean success) {
            System.out.println("认证结果："+success);
        }

        @Override
        public void onMessage(long version, String message) {
            System.out.Println("消息协议版本:"+version+",消息内容:"+message)
        }
    });
    client.start();
}catch (Exception e){
    System.out.println("错误："+e.getMessage());
}

```

