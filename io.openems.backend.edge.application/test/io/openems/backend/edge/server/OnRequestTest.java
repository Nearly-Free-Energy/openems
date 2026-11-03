package io.openems.backend.edge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.java_websocket.WebSocket;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcRequest;
import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.common.jsonrpc.request.EdgeRpcRequest;

public class OnRequestTest {

	@Test
	public void testRequestIsRelayedForTheConnectedEdgeOnly() {
		final var relayedFor = new ArrayList<String>();
		final var sut = new OnRequest("test", (edgeId, request) -> {
			relayedFor.add(edgeId);
			return CompletableFuture.completedFuture(new GenericJsonrpcResponseSuccess(request.getId()));
		});
		final var ws = mock(WebSocket.class);
		final var wsData = new WsData(ws);
		wsData.setEdgeId("edge0");
		when(ws.getAttachment()).thenReturn(wsData);

		// An Edge cannot choose the Edge-ID its requests are relayed for
		sut.apply(ws, new EdgeRpcRequest("edge1", new GenericJsonrpcRequest("getEdgeConfig", new JsonObject())));

		assertEquals(List.of("edge0"), relayedFor);
	}

}
