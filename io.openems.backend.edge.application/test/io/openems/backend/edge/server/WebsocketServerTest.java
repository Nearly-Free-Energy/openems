package io.openems.backend.edge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.HandshakeImpl1Client;
import org.junit.jupiter.api.Test;

import io.openems.backend.edge.application.CyclicTask;
import io.openems.backend.edge.client.WebsocketClient;

public class WebsocketServerTest {

	private final List<String> acceptedHandshakes = new ArrayList<>();

	private WebsocketServer createServer() {
		return createServer(this.acceptedHandshakes::add);
	}

	private static WebsocketServer createServer(Consumer<String> handshakeAccepted) {
		return new WebsocketServer("test", 0, 1, //
				(edgeId, request) -> CompletableFuture.failedFuture(new UnsupportedOperationException()), //
				(edgeId, notification) -> {
				}, //
				apikey -> "valid-key".equals(apikey) ? "edge0" : null, //
				handshakeAccepted, //
				() -> {
				});
	}

	private static HandshakeImpl1Client handshake(String apikey, String id) {
		final var handshake = new HandshakeImpl1Client();
		if (apikey != null) {
			handshake.put("Apikey", apikey);
		}
		if (id != null) {
			handshake.put("id", id);
		}
		return handshake;
	}

	@Test
	public void testApikeyWithoutIdIsAccepted() throws InvalidDataException {
		final var wsData = this.createServer().onHandshake(mock(WebSocket.class), null, handshake("valid-key", null));
		assertEquals("edge0", wsData.getEdgeId());
		assertEquals(List.of("edge0"), this.acceptedHandshakes);
	}

	@Test
	public void testApikeyWithMatchingIdIsAccepted() throws InvalidDataException {
		final var wsData = this.createServer().onHandshake(mock(WebSocket.class), null,
				handshake("valid-key", "edge0"));
		assertEquals("edge0", wsData.getEdgeId());
		assertEquals(List.of("edge0"), this.acceptedHandshakes);
	}

	@Test
	public void testApikeyWithOtherIdIsRejected() {
		final var e = assertThrows(InvalidDataException.class,
				() -> this.createServer().onHandshake(mock(WebSocket.class), null, handshake("valid-key", "edge1")));
		assertEquals(CloseFrame.POLICY_VALIDATION, e.getCloseCode());
		assertTrue(this.acceptedHandshakes.isEmpty());
	}

	@Test
	public void testInvalidApikeyIsRejected() {
		assertThrows(InvalidDataException.class,
				() -> this.createServer().onHandshake(mock(WebSocket.class), null, handshake("invalid-key", "edge0")));
		assertThrows(InvalidDataException.class,
				() -> this.createServer().onHandshake(mock(WebSocket.class), null, handshake(null, "edge0")));
		assertTrue(this.acceptedHandshakes.isEmpty());
	}

	@Test
	public void testHandshakeIsAcceptedWhenTheAnnouncementFails() throws InvalidDataException {
		final var client = mock(WebsocketClient.class);
		when(client.sendMessage(any())).thenThrow(new IllegalStateException("Edge.Manager is unavailable"));
		final var otherServer = mock(WebsocketServer.class);
		final var cyclicTask = new CyclicTask(client, () -> otherServer);

		final var wsData = createServer(cyclicTask::edgeHandshakeAccepted).onHandshake(mock(WebSocket.class), null,
				handshake("valid-key", "edge0"));

		assertEquals("edge0", wsData.getEdgeId());
		verify(client).sendMessage(any());
	}

}
