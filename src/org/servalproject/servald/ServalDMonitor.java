/**
 * Copyright (C) 2011 The Serval Project
 *
 * This file is part of Serval Software (http://www.servalproject.org)
 *
 * Serval Software is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package org.servalproject.servald;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.StringTokenizer;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.util.Log;

public class ServalDMonitor implements Runnable {
	private LocalSocket socket = null;

	private LocalSocketAddress serverSocketAddress = new LocalSocketAddress(
			"org.servalproject.servald.monitor.socket",
			LocalSocketAddress.Namespace.ABSTRACT);

	// Use a filesystem binding point from inside our app dir at our end,
	// so that no one other than the server can send us messages.
	private LocalSocketAddress clientSocketAddress = new LocalSocketAddress(
			"/data/data/org.servalproject/var/serval-node/servald-java-client.socket",
			LocalSocketAddress.Namespace.FILESYSTEM);

	private OutputStream os = null;
	private DataInputStream is = null;
	private boolean stopMe = false;
	private long dontReconnectUntil = 0;
	private long socketConnectTime;
	private int reconnectBackoff = 100;
	private boolean logMessages = false;

	int dataBytes = 0;
	private Messages messages;

	public ServalDMonitor(Messages messages) {
		this.messages = messages;
	}

	public interface Messages {
		public void connected();

		public int message(String cmd, StringTokenizer arguments,
				DataInputStream in,
				int dataLength) throws IOException;
	}

	private void backOff() {
		dontReconnectUntil = SystemClock.elapsedRealtime()
				+ reconnectBackoff;
		reconnectBackoff *= 2;
		if (reconnectBackoff > 120000)
			reconnectBackoff = 120000;
	}

	private void createSocket() {
		if (socket != null)
			return;

		long wait = dontReconnectUntil - SystemClock.elapsedRealtime();
		if (wait > 0) {
			Log.v("ServalDMonitor", "Waiting for " + wait + "ms");
			try {
				Thread.sleep(wait);
			} catch (InterruptedException e) {
				Log.e("ServalDMonitor", e.getMessage(), e);
			}
		}

		Log.v("ServalDMonitor", "Creating socket");
		LocalSocket socket = new LocalSocket();
		try {
			socket.bind(clientSocketAddress);
			socket.connect(serverSocketAddress);

			int tries = 15;
			while (!socket.isConnected() && --tries > 0)
				try {
					Thread.sleep(250);
				} catch (InterruptedException e) {
					throw new InterruptedIOException();
				}

			if (!socket.isConnected())
				throw new IOException("Connection timed out");

			socket.setSoTimeout(60000);
			is = new DataInputStream(socket.getInputStream());
			os = socket.getOutputStream();
			socketConnectTime = SystemClock.elapsedRealtime();
			this.socket = socket;

			if (this.messages != null)
				messages.connected();
		} catch (IOException e) {
			backOff();

			Log.e("ServalDMonitor", e.getMessage(), e);
			try {
				socket.close();
			} catch (IOException e1) {
				Log.e("ServalDMonitor", e1.getMessage(), e1);
			}
		}
	}

	private void cleanupSocket() {
		close(is);
		is = null;
		close(os);
		os = null;
		try {
			if (socket != null)
				socket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		socket = null;
	}

	private void errorCleanup() {
		backOff();
		cleanupSocket();
	}

	private void close(Closeable c) {
		if (c == null)
			return;
		try {
			c.close();
		} catch (IOException e) {
			Log.e("ServalDMonitor", e.getMessage(), e);
		}
	}

	private Thread currentThread;

	@Override
	public void run() {
		Log.d("ServalDMonitor", "Starting");
		currentThread = Thread.currentThread();

		while (!stopMe) {

			try {
				// Make sure we have the sockets we need
				if (socket == null)
					createSocket();

				// See if there is anything to read
				// socket.setSoTimeout(60000); // sleep for a long time if
				// needed
				if (socketConnectTime > SystemClock.elapsedRealtime() + 5000)
					// Reset reconnection backoff, when we have been connected
					// for a while
					reconnectBackoff = 100;

				processInput();
			} catch (Exception e) {
				Log.e("ServalDMonitor", e.getMessage(), e);
			}
		}
		currentThread = null;
	}

	private void processInput() throws IOException {
		StringTokenizer tokens;
		String cmd;

		DataInputStream in = is;
		if (in == null)
			return;

		synchronized (in) {
			try {
				String line = in.readLine();
				if (line == null)
					throw new EOFException();
				if (line.equals(""))
					return;

				if (logMessages)
					Log.v("ServalDMonitor", "Read monitor message: " + line);

				tokens = new StringTokenizer(line, ":");
				cmd = tokens.nextToken();
				if (cmd.charAt(0) == '*') {

					// Message with data
					dataBytes = Integer.parseInt(cmd.substring(1));

					if (dataBytes < 0)
						throw new IOException(
								"Message has data block with negative length: "
										+ line);

					// Okay, we know about the data, get the real command
					cmd = tokens.nextToken();
				} else
					dataBytes = 0;

				int read = 0;

				if (cmd.equals("CLOSE")) {
					// servald doesn't want to talk to us
					// don't retry for a second
					cleanupSocket();
					dontReconnectUntil = SystemClock.elapsedRealtime() + 1000;
				} else if (this.messages != null)
					read = messages.message(cmd, tokens, in, dataBytes);

				while (read < dataBytes) {
					if (logMessages)
						Log.v("ServalDMonitor", "Skipping "
								+ (dataBytes - read) + " unread data bytes");
					read += in.skip(dataBytes - read);
				}

				if (read > dataBytes)
					throw new IOException("Read too many bytes");

			} catch (IOException e) {
				if ("Try again".equals(e.getMessage()))
					return;

				errorCleanup();
				throw e;
			}
		}
	}

	private void dump(String string, byte[] data, int offset, int lengthIn) {
		int length=lengthIn-offset;
		int i,j;
		StringBuilder sb = new StringBuilder();
		for(i=0;i<length;i+=16) {
			sb.setLength(0);
			sb.append(Integer.toHexString(i));
			sb.append(" :");
			for (j = 0; j < 16; j++) {
				int v = 0xFF & data[offset + i + j];
				sb.append(" ");
				if (i + j < length) {
					sb.append(Integer.toHexString(v));
				} else
					sb.append("   ");
			}
			sb.append(" ");
			for (j = 0; j < 16; j++) {
				int v = 0xFF & data[offset + i + j];
				if (i + j < length) {
					if (v >= 0x20 && v < 0x7d)
						sb.append((char) v);
					else
						sb.append('.');
				}
			}
			// Log.d("Dump:" + string, sb.toString());
		}

	}

	private void write(String str) throws IOException {
		byte buff[] = new byte[str.length()];
		for (int i = 0; i < str.length(); i++) {
			char chr = str.charAt(i);
			if (chr > 0xFF)
				throw new IOException("Unexpected character " + chr);
			buff[i] = (byte) chr;
		}
		os.write(buff);
	}

	public void sendMessage(String string) throws IOException {
		try {
			if (socket == null)
				createSocket();
			if (logMessages)
				Log.v("ServalDMonitor", "Sending " + string);
			synchronized (os) {
				socket.setSoTimeout(500);
				write(string + "\n");
				socket.setSoTimeout(60000);
			}
			os.flush();
		} catch (IOException e) {
			errorCleanup();
			throw e;
		}
	}

	public void sendMessageAndLog(String string) {
		try {
			this.sendMessage(string);
		} catch (IOException e) {
			Log.e("ServalDMonitor", e.getMessage(), e);
		}
	}
	public boolean ready() {
		if (socket != null)
			return true;
		else
			return false;
	}

	public void stop() {
		stopMe = true;
		if (currentThread != null)
			currentThread.interrupt();
		cleanupSocket();
	}

	public void sendMessageAndData(String string, byte[] block, int len)
			throws IOException {
		try {
			if (socket == null)
				createSocket();
			StringBuilder sb = new StringBuilder();
			sb
					.append("*")
					.append(len)
					.append(":")
					.append(string)
					.append("\n");
			if (logMessages)
				Log.v("ServalDMonitor", "Sending " + string + " +"
						+ len + " data");
			synchronized (os) {
				socket.setSoTimeout(500);
				write(sb.toString());
				os.write(block, 0, len);
				socket.setSoTimeout(60000);
			}
			os.flush();
		} catch (IOException e) {
			errorCleanup();
			throw e;
		}
	}
}
