package io.openems.backend.edge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.java_websocket.WebSocket;
import org.junit.jupiter.api.Test;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.jsonrpc.base.GenericJsonrpcNotification;
import io.openems.common.jsonrpc.base.JsonrpcNotification;
import io.openems.common.jsonrpc.notification.EdgeRpcNotification;
import io.openems.common.utils.JsonUtils;

public class OnNotificationTest {

	private record Forwarded(String edgeId, JsonrpcNotification notification) {
	}

	private final List<Forwarded> forwarded = new ArrayList<>();
	private final OnNotification sut = new OnNotification("test",
			(edgeId, notification) -> this.forwarded.add(new Forwarded(edgeId, notification)));

	private static WebSocket connectionOf(String edgeId) {
		final var ws = mock(WebSocket.class);
		final var wsData = new WsData(ws);
		if (edgeId != null) {
			wsData.setEdgeId(edgeId);
		}
		when(ws.getAttachment()).thenReturn(wsData);
		return ws;
	}

	private static JsonrpcNotification timestampedData() {
		return new GenericJsonrpcNotification("timestampedData", JsonUtils.buildJsonObject() //
				.add("1700000000000", JsonUtils.buildJsonObject() //
						.addProperty("_sum/State", 0) //
						.build()) //
				.build());
	}

	@Test
	public void testPlainNotificationIsForwarded() throws OpenemsNamedException {
		final var notification = timestampedData();
		this.sut.accept(connectionOf("edge0"), notification);

		assertEquals(List.of(new Forwarded("edge0", notification)), this.forwarded);
	}

	@Test
	public void testWrappedNotificationIsForwardedOnce() throws OpenemsNamedException {
		final var notification = timestampedData();
		this.sut.accept(connectionOf("edge0"), new EdgeRpcNotification("edge0", notification));

		assertEquals(1, this.forwarded.size());
		final var result = this.forwarded.get(0);
		assertEquals("edge0", result.edgeId());
		assertEquals(notification.toJsonObject(), result.notification().toJsonObject());
	}

	@Test
	public void testNotificationWrappedForOtherEdgeIsDropped() throws OpenemsNamedException {
		this.sut.accept(connectionOf("edge0"), new EdgeRpcNotification("edge1", timestampedData()));

		assertTrue(this.forwarded.isEmpty());
	}

	@Test
	public void testUnauthenticatedConnectionIsIgnored() throws OpenemsNamedException {
		this.sut.accept(connectionOf(null), timestampedData());

		assertTrue(this.forwarded.isEmpty());
	}

}
