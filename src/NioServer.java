import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

class NioServer implements Runnable {
    private static final Logger LOG = Logger.getLogger("server");

    private final InetAddress host;
    private final int port;

    // The selector to monitor
    private Selector selector;

    // Reading buffer
    private ByteBuffer readBuffer = ByteBuffer.allocate(8192);

    private EchoWorker worker;

    private final List<ChangeRequest> changeRequests = new LinkedList<>();

    // Maps a SocketChannel to a list of ByteBuffer instances
    private final Map<SocketChannel, List<ByteBuffer>> pendingData = new HashMap<>();

    NioServer(InetAddress host, int port, EchoWorker worker) throws IOException {
        this.host = host;
        this.port = port;
        this.selector = this.initSelector();
        this.worker = worker;
    }

    private Selector initSelector() throws IOException {
        // Accept connections on this channel
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);

        // Bind the server socket to the address and port
        InetSocketAddress address = new InetSocketAddress(this.host, this.port);
        serverChannel.bind(address);

        // Register the server channel: interest in accepting connections
        Selector selector = SelectorProvider.provider().openSelector();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        return selector;
    }

    @Override
    public void run() {
        LOG.info("Starting server");

        while (!Thread.interrupted()) {
            try {
                synchronized (this.changeRequests) {
                    for (ChangeRequest change : this.changeRequests) {
                        switch (change.type) {
                            case ChangeRequest.CHANGEOPS:
                                SelectionKey key = change.socket.keyFor(this.selector);
                                key.interestOps(change.ops);
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
                    if (key.isAcceptable()) {
                        LOG.info("Got accept event");
                        this.accept(key);
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

    private void accept(SelectionKey key) throws IOException {
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();

        // Accept the connection and make it non-blocking
        SocketChannel socketChannel = serverSocketChannel.accept();
        socketChannel.configureBlocking(false);

        // Register the SocketChannel with our Selector: interest in read
        socketChannel.register(this.selector, SelectionKey.OP_READ);
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

        // Hand the data off to our worker thread
        this.worker.processData(this, socketChannel, readBuffer.array(), numRead);
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

    void send(SocketChannel socket, byte[] data) {
        synchronized (this.changeRequests) {
            this.changeRequests.add(new ChangeRequest(socket, ChangeRequest.CHANGEOPS, SelectionKey.OP_WRITE));

            // Queue the data to write
            synchronized (this.pendingData) {
                List<ByteBuffer> queue = this.pendingData.computeIfAbsent(socket, k -> new ArrayList<>());
                queue.add(ByteBuffer.wrap(data));
            }
        }

        // Wake up our selecting thread so it can apply the required changes
        this.selector.wakeup();
    }
}
