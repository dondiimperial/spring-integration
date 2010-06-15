/*
 * Copyright 2002-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.integration.ip.tcp;


import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

import org.springframework.integration.core.Message;
import org.springframework.integration.ip.util.SocketIoUtils;

/**
 * Tcp Receiving Channel adapter that uses a {@link java.nio.channels.SocketChannel}.
 * Sockets are multiplexed across the pooled threads. More than one thread will
 * be required with large numbers of connections and incoming traffic. The
 * number of threads is controlled by the poolSize property.
 * 
 * @author Gary Russell
 * @since 2.0
 *
 */
public class TcpNioReceivingChannelAdapter extends
		AbstractTcpReceivingChannelAdapter {

	protected ServerSocketChannel serverChannel;
	protected boolean usingDirectBuffers;
	protected Class<NioSocketReader> customSocketReaderClass;
	
	/**
	 * Constructs a TcpNioReceivingChannelAdapter to listen on the port.
	 * @param port The port.
	 */
	public TcpNioReceivingChannelAdapter(int port) {
		super(port);
	}

	/**
	 * Opens a non-blocking {@link ServerSocketChannel}, registers it with a 
	 * {@link Selector} and calls {@link #doSelect(ServerSocketChannel, Selector)}.
	 * 
	 * @see org.springframework.integration.ip.tcp.AbstractTcpReceivingChannelAdapter#server()
	 */
	@Override
	protected void server() {
		try {
			this.serverChannel = ServerSocketChannel.open();
			this.listening = true;
			this.serverChannel.configureBlocking(false);
			if (this.localAddress == null) {
				this.serverChannel.socket().bind(new InetSocketAddress(this.port),
					Math.abs(this.poolSize));
			} else {
				InetAddress whichNic = InetAddress.getByName(this.localAddress);
				this.serverChannel.socket().bind(new InetSocketAddress(whichNic, this.port),
						Math.abs(this.poolSize));
			}
			final Selector selector = Selector.open();
			this.serverChannel.register(selector, SelectionKey.OP_ACCEPT);
			doSelect(this.serverChannel, selector);

		} catch (IOException e) {
			try {
				serverChannel.close();
			} catch (IOException e1) { }
			this.listening = false;
			this.serverChannel = null;
			if (this.active) {
				logger.error("Error on ServerSocketChannel", e);
			}
		}
	}

	/**
	 * Listens for incoming connections and for notifications that a connected
	 * socket is ready for reading.
	 * Accepts incoming connections, registers the new socket with the 
	 * selector for reading.
	 * When a socket is ready for reading, unregisters the read interest and
	 * schedules a call to doRead which reads all available data. When the read
	 * is complete, the socket is again registered for read interest. 
	 * @param server
	 * @param selector
	 * @throws IOException
	 * @throws ClosedChannelException
	 * @throws SocketException
	 */
	private void doSelect(ServerSocketChannel server, final Selector selector)
			throws IOException, ClosedChannelException, SocketException {
		while (active) {
			int selectionCount = selector.select();
			if (logger.isDebugEnabled())
				logger.debug("Port " + port + " SelectionCount: " + selectionCount);
			if (selectionCount > 0) {
				Set<SelectionKey> keys = selector.selectedKeys();
				Iterator<SelectionKey> iterator = keys.iterator();
				SocketChannel channel = null;
				while (iterator.hasNext()) {
					final SelectionKey key = iterator.next();
					iterator.remove();
					if (key.isAcceptable()) {
						channel = server.accept();
						channel.configureBlocking(false);
						channel.register(selector, SelectionKey.OP_READ);
						Socket socket = channel.socket();
						setSocketOptions(socket);
					}
					else if (key.isReadable()) {
						key.interestOps(key.interestOps() - key.readyOps());
						if (key.attachment() == null) {
							NioSocketReader reader = createSocketReader(key);
							if (reader == null) {
								continue;
							}
							key.attach(reader);
						}
						this.threadPoolTaskScheduler.execute(new Runnable() {
							public void run() {
								doRead(key);
								if (key.channel().isOpen()) {
									key.interestOps(SelectionKey.OP_READ);
									selector.wakeup();
								}
							}});
					}
					else {
						logger.error("Unexpected key: " + key);
					}
				}
			}			
		}
	}

	/**
	 * Creates an NioSocketReader, either directly,or 
	 * from the supplied class if {@link MessageFormats#FORMAT_CUSTOM}
	 * is used. 
	 * @param key The selection key.
	 * @return The NioSocketReader.
	 */
	private NioSocketReader createSocketReader(final SelectionKey key) {
		SocketChannel channel = (SocketChannel) key.channel();
		NioSocketReader reader = SocketIoUtils.createNioReader(messageFormat,
				this.customSocketReaderClass, channel, this.receiveBufferSize,
				this.receiveBufferSize,	this.usingDirectBuffers);
		return reader;
	}

	/**
	 * Obtains the {@link NetSocketReader} associated with the channel 
	 * and calls its {@link NetSocketReader#assembledData}
	 * method; if a message is fully assembled,  calls {@link #sendMessage(Message)} with the
	 * mapped message.
	 * 
	 * @param channel
	 */
	private void doRead(SelectionKey key) {
		NioSocketReader reader = (NioSocketReader) key.attachment();
		try {
			int messageStatus = reader.assembleData();
			if (messageStatus < 0) {
				return;
			}
			if (messageStatus == SocketReader.MESSAGE_COMPLETE) {
				if (close) {
					logger.debug("Closing channel because close=true");
					try {
						key.channel().close();
					} catch (IOException ioe) {
						logger.error("Error on close", ioe);
					}
				}
				Message<Object> message;
					message = mapper.toMessage(reader);
					if (message != null) {
						sendMessage(message);
					}
			}
		} catch (Exception e) {
			logger.error("Failure on read or message send", e);
		}
	}

	@Override
	protected void doStop() {
		super.doStop();
		try {
			this.serverChannel.close();
		}
		catch (Exception e) {
			// ignore
		}
	}

	/**
	 * @param usingDirectBuffers Set true if you wish to use direct buffers
	 * for NIO operations.
	 */
	public void setUsingDirectBuffers(boolean usingDirectBuffers) {
		this.usingDirectBuffers = usingDirectBuffers;
	}

	/**
	 * @param customSocketReaderClassName the customSocketReaderClassName to set
	 * @throws ClassNotFoundException 
	 */
	@SuppressWarnings("unchecked")
	public void setCustomSocketReaderClassName(String customSocketReaderClassName)
			throws ClassNotFoundException {
		this.customSocketReaderClass = (Class<NioSocketReader>) Class
				.forName(customSocketReaderClassName);
		if (!(NioSocketReader.class.isAssignableFrom(this.customSocketReaderClass))) {
			throw new IllegalArgumentException("Custom socket reader must be of type NioSocketReader");
		}
	}

}
 