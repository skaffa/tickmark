package dev.tickmark.client.dev;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Minimal RCON client for the local test server (dev only; port 25575, password from server.properties). */
final class Rcon implements AutoCloseable {
	private final Socket socket;
	private final DataInputStream in;
	private final OutputStream out;
	private int nextId = 1;

	Rcon(String host, int port, String password) throws IOException {
		socket = new Socket(host, port);
		socket.setSoTimeout(10_000);
		in = new DataInputStream(socket.getInputStream());
		out = socket.getOutputStream();
		send(3, password);
		if (read().id == -1) {
			throw new IOException("RCON login refused");
		}
	}

	/** Runs a command and returns the server's reply. */
	String command(String cmd) throws IOException {
		send(2, cmd);
		return read().body;
	}

	private record Reply(int id, String body) {
	}

	private void send(int type, String body) throws IOException {
		byte[] b = body.getBytes(StandardCharsets.UTF_8);
		ByteBuffer buf = ByteBuffer.allocate(14 + b.length).order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(10 + b.length).putInt(nextId++).putInt(type).put(b).put((byte) 0).put((byte) 0);
		out.write(buf.array());
		out.flush();
	}

	private Reply read() throws IOException {
		byte[] lenBytes = new byte[4];
		in.readFully(lenBytes);
		int len = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		byte[] data = new byte[len];
		in.readFully(data);
		ByteBuffer d = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		int id = d.getInt();
		d.getInt();
		return new Reply(id, new String(data, 8, Math.max(0, len - 10), StandardCharsets.UTF_8));
	}

	@Override
	public void close() throws IOException {
		socket.close();
	}
}
