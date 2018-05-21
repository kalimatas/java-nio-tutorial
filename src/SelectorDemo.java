import java.io.IOException;

public class SelectorDemo {
    public static void main(String[] args) {
        try {
            EchoWorker worker = new EchoWorker();
            new Thread(worker).start();
            new Thread(new NioServer(null, 9090, worker)).start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}



