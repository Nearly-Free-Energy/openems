package io.openems.backend.edge.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.java_websocket.WebSocket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.openems.backend.common.edge.jsonrpc.ConnectedEdges;
import io.openems.backend.edge.client.WebsocketClient;
import io.openems.backend.edge.server.WebsocketServer;
import io.openems.backend.edge.server.WsData;
import io.openems.common.jsonrpc.base.JsonrpcMessage;

public class CyclicTaskTest {

	private final WebsocketClient client = mock(WebsocketClient.class);
	private final WebsocketServer server = mock(WebsocketServer.class);
	private final CyclicTask sut = new CyclicTask(this.client, () -> this.server);

	@BeforeEach
	public void connectedToEdgeManager() {
		when(this.client.sendMessage(any())).thenReturn(true);
	}

	private void connected(String... edgeIds) {
		final var connections = Arrays.stream(edgeIds).map(edgeId -> {
			final var wsData = mock(WsData.class);
			when(wsData.getEdgeId()).thenReturn(edgeId);
			final var ws = mock(WebSocket.class);
			when(ws.getAttachment()).thenReturn(wsData);
			return ws;
		}).toList();
		when(this.server.getConnections()).thenReturn(connections);
	}

	private Set<String> lastAnnouncedEdgeIds() {
		final var captor = ArgumentCaptor.forClass(JsonrpcMessage.class);
		verify(this.client, atLeastOnce()).sendMessage(captor.capture());
		final var notification = (ConnectedEdges.Notification) captor.getValue();
		return notification.getEdgeIds();
	}

	@Test
	public void testHandshakeIsAnnouncedImmediately() {
		this.connected("edge1");

		this.sut.edgeHandshakeAccepted("edge0");

		assertEquals(Set.of("edge0", "edge1"), this.lastAnnouncedEdgeIds());
	}

	@Test
	public void testHandshakeIsAnnouncedUntilItsConnectionIsListed() {
		this.connected();
		this.sut.edgeHandshakeAccepted("edge0");

		this.sut.run();

		assertEquals(Set.of("edge0"), this.lastAnnouncedEdgeIds());
	}

	@Test
	public void testClosedConnectionIsNoLongerAnnounced() {
		this.connected();
		this.sut.edgeHandshakeAccepted("edge0");
		this.connected("edge0");
		this.sut.run();
		assertEquals(Set.of("edge0"), this.lastAnnouncedEdgeIds());

		this.connected();
		this.sut.connectedEdgesChanged();
		this.sut.run();

		assertEquals(Set.of(), this.lastAnnouncedEdgeIds());
	}

	@Test
	public void testUnchangedConnectionsAreNotAnnouncedAgain() {
		this.connected("edge0");
		this.sut.connectedEdgesChanged();
		this.sut.run();
		this.sut.run();

		final var captor = ArgumentCaptor.forClass(JsonrpcMessage.class);
		verify(this.client, atLeastOnce()).sendMessage(captor.capture());
		assertEquals(List.of(Set.of("edge0")), captor.getAllValues().stream() //
				.map(m -> ((ConnectedEdges.Notification) m).getEdgeIds()) //
				.toList());
	}

	@Test
	public void testUnsentAnnouncementIsSentInNextRun() {
		when(this.client.sendMessage(any())).thenReturn(false, true);
		this.connected("edge0");
		this.sut.connectedEdgesChanged();

		this.sut.run();
		this.sut.run();
		this.sut.run();

		verify(this.client, times(2)).sendMessage(any());
		assertEquals(Set.of("edge0"), this.lastAnnouncedEdgeIds());
	}

	@Test
	public void testFailedHandshakeAnnouncementIsSentInNextRun() {
		when(this.client.sendMessage(any())) //
				.thenThrow(new IllegalStateException("Edge.Manager is unavailable")) //
				.thenReturn(true);
		this.connected("edge0");

		assertDoesNotThrow(() -> this.sut.edgeHandshakeAccepted("edge0"));
		this.sut.run();

		verify(this.client, times(2)).sendMessage(any());
		assertEquals(Set.of("edge0"), this.lastAnnouncedEdgeIds());
	}

}
