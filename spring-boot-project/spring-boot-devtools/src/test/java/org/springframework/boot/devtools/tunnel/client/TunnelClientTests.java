/*
 * Copyright 2012-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.devtools.tunnel.client;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link TunnelClient}.
 *
 * @author Phillip Webb
 */
class TunnelClientTests {

	private final MockTunnelConnection tunnelConnection = new MockTunnelConnection();

	@Test
	void listenPortMustNotBeNegative() {
		assertThatIllegalArgumentException().isThrownBy(() -> new TunnelClient(-5, this.tunnelConnection))
				.withMessageContaining("ListenPort must be greater than or equal to 0");
	}

	@Test
	void tunnelConnectionMustNotBeNull() {
		assertThatIllegalArgumentException().isThrownBy(() -> new TunnelClient(1, null))
				.withMessageContaining("TunnelConnection must not be null");
	}

	@Test
	void typicalTraffic() throws Exception {
		TunnelClient client = new TunnelClient(0, this.tunnelConnection);
		int port = client.start();
		SocketChannel channel = SocketChannel.open(new InetSocketAddress(port));
		channel.write(ByteBuffer.wrap("hello".getBytes()));
		ByteBuffer buffer = ByteBuffer.allocate(5);
		channel.read(buffer);
		channel.close();
		this.tunnelConnection.verifyWritten("hello");
		assertThat(new String(buffer.array())).isEqualTo("olleh");
	}

	@Test
	void socketChannelClosedTriggersTunnelClose() throws Exception {
		TunnelClient client = new TunnelClient(0, this.tunnelConnection);
		int port = client.start();
		SocketChannel channel = SocketChannel.open(new InetSocketAddress(port));
		Awaitility.await().atMost(Duration.ofSeconds(30)).until(this.tunnelConnection::getOpenedTimes,
				(open) -> open == 1);
		channel.close();
		client.getServerThread().stopAcceptingConnections();
		client.getServerThread().join(2000);
		assertThat(this.tunnelConnection.getOpenedTimes()).isOne();
		assertThat(this.tunnelConnection.isOpen()).isFalse();
	}

	@Test
	void stopTriggersTunnelClose() throws Exception {
		TunnelClient client = new TunnelClient(0, this.tunnelConnection);
		int port = client.start();
		SocketChannel channel = SocketChannel.open(new InetSocketAddress(port));
		Awaitility.await().atMost(Duration.ofSeconds(30)).until(this.tunnelConnection::getOpenedTimes,
				(times) -> times == 1);
		assertThat(this.tunnelConnection.isOpen()).isTrue();
		client.stop();
		assertThat(this.tunnelConnection.isOpen()).isFalse();
		assertThat(readWithPossibleFailure(channel)).satisfiesAnyOf((result) -> assertThat(result).isEqualTo(-1),
				(result) -> assertThat(result).isInstanceOf(SocketException.class));
	}

	private Object readWithPossibleFailure(SocketChannel channel) {
		try {
			return channel.read(ByteBuffer.allocate(1));
		}
		catch (Exception ex) {
			return ex;
		}
	}

	@Test
	void addListener() throws Exception {
		TunnelClient client = new TunnelClient(0, this.tunnelConnection);
		MockTunnelClientListener listener = new MockTunnelClientListener();
		client.addListener(listener);
		int port = client.start();
		SocketChannel channel = SocketChannel.open(new InetSocketAddress(port));
		Awaitility.await().atMost(Duration.ofSeconds(30)).until(listener.onOpen::get, (open) -> open == 1);
		assertThat(listener.onClose).hasValue(0);
		client.getServerThread().stopAcceptingConnections();
		channel.close();
		Awaitility.await().atMost(Duration.ofSeconds(30)).until(listener.onClose::get, (close) -> close == 1);
		client.getServerThread().join(2000);
	}

	static class MockTunnelClientListener implements TunnelClientListener {

		private final AtomicInteger onOpen = new AtomicInteger();

		private final AtomicInteger onClose = new AtomicInteger();

		@Override
		public void onOpen(SocketChannel socket) {
			this.onOpen.incrementAndGet();
		}

		@Override
		public void onClose(SocketChannel socket) {
			this.onClose.incrementAndGet();
		}

	}

	static class MockTunnelConnection implements TunnelConnection {

		private final ByteArrayOutputStream written = new ByteArrayOutputStream();

		private boolean open;

		private int openedTimes;

		@Override
		public WritableByteChannel open(WritableByteChannel incomingChannel, Closeable closeable) {
			this.openedTimes++;
			this.open = true;
			return new TunnelChannel(incomingChannel, closeable);
		}

		void verifyWritten(String expected) {
			verifyWritten(expected.getBytes());
		}

		void verifyWritten(byte[] expected) {
			synchronized (this.written) {
				assertThat(this.written.toByteArray()).isEqualTo(expected);
				this.written.reset();
			}
		}

		boolean isOpen() {
			return this.open;
		}

		int getOpenedTimes() {
			return this.openedTimes;
		}

		private class TunnelChannel implements WritableByteChannel {

			private final WritableByteChannel incomingChannel;

			private final Closeable closeable;

			TunnelChannel(WritableByteChannel incomingChannel, Closeable closeable) {
				this.incomingChannel = incomingChannel;
				this.closeable = closeable;
			}

			@Override
			public boolean isOpen() {
				return MockTunnelConnection.this.open;
			}

			@Override
			public void close() throws IOException {
				MockTunnelConnection.this.open = false;
				this.closeable.close();
			}

			@Override
			public int write(ByteBuffer src) throws IOException {
				int remaining = src.remaining();
				ByteArrayOutputStream stream = new ByteArrayOutputStream();
				Channels.newChannel(stream).write(src);
				byte[] bytes = stream.toByteArray();
				synchronized (MockTunnelConnection.this.written) {
					MockTunnelConnection.this.written.write(bytes);
				}
				byte[] reversed = new byte[bytes.length];
				for (int i = 0; i < reversed.length; i++) {
					reversed[i] = bytes[bytes.length - 1 - i];
				}
				this.incomingChannel.write(ByteBuffer.wrap(reversed));
				return remaining;
			}

		}

	}

}
