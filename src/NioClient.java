import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

class NioClient implements Runnable {
    private static final Logger LOG = Logger.getLogger("client");

    private final InetAddress host;
    private final int port;

    // The selector to monitor
    private Selector selector;

    // Reading buffer
    private ByteBuffer readBuffer = ByteBuffer.allocate(8192);

    private final List<ChangeRequest> changeRequests = new LinkedList<>();

    // Maps a SocketChannel to a list of ByteBuffer instances
    private final Map<SocketChannel, List<ByteBuffer>> pendingData = new HashMap<>();

    private final Map<SocketChannel, RspHandler> rspHandlers = Collections.synchronizedMap(new HashMap<>());

    NioClient(InetAddress host, int port) throws IOException {
        this.host = host;
        this.port = port;
        this.selector = this.initSelector();
    }

    private Selector initSelector() throws IOException {
        return SelectorProvider.provider().openSelector();
    }

    private SocketChannel initiateConnection() throws IOException {
        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(false);

        socketChannel.connect(new InetSocketAddress(this.host, this.port));

        // Queue a channel registration
        synchronized (this.changeRequests) {
            this.changeRequests.add(new ChangeRequest(socketChannel, ChangeRequest.REGISTER, SelectionKey.OP_CONNECT));
        }

        return socketChannel;
    }

    @Override
    public void run() {
        LOG.info("Starting client");

        while (!Thread.interrupted()) {
            try {
                synchronized (this.changeRequests) {
                    for (ChangeRequest change : this.changeRequests) {
                        switch (change.type) {
                            case ChangeRequest.CHANGEOPS:
                                SelectionKey key = change.socket.keyFor(this.selector);
                                key.interestOps(change.ops);
                                break;
                            case ChangeRequest.REGISTER:
                                change.socket.register(this.selector, change.ops);
                                break;
                        }
                    }
                    this.changeRequests.clear();
                }

                // Wait for an event
                this.selector.select();

                LOG.info("Got selector event");

                Iterator selectedKeys = this.selector.selectedKeys().iterator();
                while (selectedKeys.hasNext()) {
                    SelectionKey key = (SelectionKey) selectedKeys.next();
                    selectedKeys.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    // Deal with event
                    if (key.isConnectable()) {
                        this.finishConnection(key);
                    } else if (key.isReadable()) {
                        LOG.info("Got read event");
                        this.read(key);
                    } else if (key.isWritable()) {
                        this.write(key);
                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void finishConnection(SelectionKey key) {
        SocketChannel socketChannel = (SocketChannel) key.channel();

        // Finish the connection
        try {
            socketChannel.finishConnect();
        } catch (IOException e) {
            // Cancel the channel's registration
            key.cancel();
            return;
        }

        // Register an interest in writing on this channel
        key.interestOps(SelectionKey.OP_WRITE);
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();

        this.readBuffer.clear();

        // Read off the channel
        int numRead;
        try {
            numRead = socketChannel.read(this.readBuffer);
        } catch (IOException e) {
            LOG.warning("Cannot read; force close");
            // Close the connection, cancel the selection key and close the channel.
            key.cancel();
            socketChannel.close();
            return;
        }

        if (numRead == -1) {
            LOG.warning("Cannot read; clean close");
            // Clean shutdown
            socketChannel.close();
            key.cancel();
            return;
        }

        this.handleResponse(socketChannel, this.readBuffer.array(), numRead);
    }

    private void handleResponse(SocketChannel socketChannel, byte[] data, int numRead) throws IOException {
        byte[] rspData = new byte[numRead];
        System.arraycopy(data, 0, rspData, 0, numRead);

        // Look up the handler
        RspHandler handler = this.rspHandlers.get(socketChannel);

        if (handler.handleResponse(rspData)) {
            socketChannel.close();
            socketChannel.keyFor(this.selector).cancel();
        }
    }

    private void write(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();

        synchronized (this.pendingData) {
            List<ByteBuffer> queue = this.pendingData.get(socketChannel);

            while (!queue.isEmpty()) {
                var buf = queue.get(0);
                socketChannel.write(buf);
                if (buf.remaining() > 0) {
                    // .. the socket's buffers fills up
                    break;
                }
                queue.remove(0);
            }

            if (queue.isEmpty()) {
                // Not interested in writing anymore, because all data is written
                key.interestOps(SelectionKey.OP_READ);
            }
        }
    }

    void send(byte[] data, RspHandler handler) throws IOException {
        SocketChannel socket = this.initiateConnection();

        this.rspHandlers.put(socket, handler);

        // Queue the data to write
        synchronized (this.pendingData) {
            List<ByteBuffer> queue = this.pendingData.computeIfAbsent(socket, k -> new ArrayList<>());
            queue.add(ByteBuffer.wrap(data));
        }

        // Wake up our selecting thread so it can apply the required changes
        this.selector.wakeup();
    }
}
