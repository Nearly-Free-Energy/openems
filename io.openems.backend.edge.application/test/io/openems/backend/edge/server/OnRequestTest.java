package io.openems.backend.edge.server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.java_websocket.WebSocket;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import io.openems.backend.edge.application.BackendEdgeServerApp;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.jsonrpc.base.GenericJsonrpcRequest;
import io.openems.common.jsonrpc.request.EdgeRpcRequest;

public class OnRequestTest {

	@Test
	public void testRequestIsRelayedForTheConnectedEdgeOnly() throws OpenemsNamedException {
		final var app = mock(BackendEdgeServerApp.class);
		when(app.sendRequestToEdgeManager(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
		final var sut = new OnRequest("test", app);
		final var ws = mock(WebSocket.class);
		final var wsData = new WsData(ws);
		wsData.setEdgeId("edge0");
		when(ws.getAttachment()).thenReturn(wsData);

		// An Edge cannot choose the Edge-ID its requests are relayed for
		sut.apply(ws, new EdgeRpcRequest("edge1", new GenericJsonrpcRequest("getEdgeConfig", new JsonObject())));

		verify(app).sendRequestToEdgeManager(eq("edge0"), any());
	}

}
