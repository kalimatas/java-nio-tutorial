import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.List;

class EchoWorker implements Runnable {
    private final List<ServerDataEvent> queue = new LinkedList<>();

    void processData(NioServer server, SocketChannel socket, byte[] data, int count) {
        byte[] dataCopy = new byte[count];
        System.arraycopy(data, 0, dataCopy, 0, count);
        synchronized (queue) {
            queue.add(new ServerDataEvent(server, socket, dataCopy));
            queue.notify();
        }
    }

    @Override
    public void run() {
        ServerDataEvent dataEvent;

        while (!Thread.interrupted()) {
            synchronized (queue) {
                while (queue.isEmpty()) {
                    try {
                        queue.wait();
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }
                dataEvent = queue.remove(0);
            }

            // Return to sender
            dataEvent.server.send(dataEvent.socketChannel, dataEvent.data);
        }
    }
}
