import java.io.IOException;

public class SelectorDemoClient {
    public static void main(String[] args) {
        try {
            var client = new NioClient(null, 9090);
            var t = new Thread(client);
            t.setDaemon(true);
            t.start();

            var handler = new RspHandler();
            client.send("Hello World".getBytes(), handler);
            handler.waitForResponse();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
