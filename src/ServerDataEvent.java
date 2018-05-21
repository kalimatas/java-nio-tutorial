import java.nio.channels.SocketChannel;

public class ServerDataEvent {
    NioServer server;
    SocketChannel socketChannel;
    byte[] data;

    public ServerDataEvent(NioServer server, SocketChannel socketChannel, byte[] data) {
        this.server = server;
        this.socketChannel = socketChannel;
        this.data = data;
    }
}
