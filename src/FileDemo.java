import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

public class FileDemo {
    public static void main(String[] args) {
        try (var file = new RandomAccessFile("/Users/kalimatas/p/java/nio/src/FileDemo.java", "r")) {
            var channel = file.getChannel();
            var buf = ByteBuffer.allocate(80);

            int bytesRead = channel.read(buf);
            while (bytesRead != -1) {
                System.out.println("read " + bytesRead);
                buf.flip();

                while (buf.hasRemaining()) {
                    System.out.print((char) buf.get());
                }

                buf.clear();
                bytesRead = channel.read(buf);
            }
        } catch (FileNotFoundException e) {
            System.err.println("file not found " + e);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
