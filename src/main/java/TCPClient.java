import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;

public class TCPClient {

    public TCPClient() {

    }
    public byte[] sendAndReceive(String host, int port, byte[] message) {

        try (Socket socket = new Socket(host, port)) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            out.write(message);

            byte[] responseBytes = new byte[68];
            in.read(responseBytes, 0, 68);
            return responseBytes;

        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}
