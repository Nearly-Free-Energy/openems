package io.openems.edge.controller.api.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.Proxy.Type;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.Test;

import com.google.gson.JsonObject;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.jsonrpc.base.GenericJsonrpcNotification;
import io.openems.common.jsonrpc.base.JsonrpcNotification;
import io.openems.common.jsonrpc.notification.EdgeRpcNotification;
import io.openems.common.oem.DummyOpenemsEdgeOem;
import io.openems.common.test.TimeLeapClock;
import io.openems.common.websocket.DummyWebsocketServer;
import io.openems.edge.common.sum.DummySum;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.common.test.DummyCycle;

/**
 * With an Edge-ID configured, every notification the Edge sends must reach the
 * Backend wrapped in an {@link EdgeRpcNotification}, whichever code path sent
 * it. The Backend Edge.Manager drops anything else at top level.
 */
public class EdgeRpcWrappingTest {

	private static final String EDGE_ID = "edge0";

	@Test
	public void testEveryNotificationIsWrappedWhenEdgeIdIsSet() throws Exception {
		final List<JsonrpcNotification> received = new CopyOnWriteArrayList<>();
		try (final var server = DummyWebsocketServer.create() //
				.onNotification((ws, notification) -> received.add(notification)) //
				.build()) {
			server.start();

			int port;
			do {
				Thread.sleep(100);
				port = server.getPort();
			} while (port == 0);

			final var clock = new TimeLeapClock(Instant.ofEpochSecond(1577836800L), ZoneOffset.UTC);
			final var sut = new ControllerApiBackendImpl();
			new ComponentTest(sut) //
					.addReference("componentManager", new DummyComponentManager(clock)) //
					.addReference("cycle", new DummyCycle(1000)) //
					.addReference("resendHistoricDataWorkerFactory", new DummyResendHistoricDataWorkerFactory()) //
					.addReference("requestHandlerFactory", new DummyBackendOnRequestFactory()) //
					.addReference("oem", new DummyOpenemsEdgeOem()) //
					.addComponent(new DummySum()) //
					.activate(MyConfig.create() //
							.setId("ctrl0") //
							.setUri("ws://localhost:" + port) //
							.setApikey("12345") //
							.setEdgeId(EDGE_ID) //
							.setProxyType(Type.DIRECT) //
							.setProxyAddress("") //
							.setPersistencePriority(PersistencePriority.HIGH) //
							.setAggregationPriority(PersistencePriority.VERY_LOW) //
							.setResendPriority(PersistencePriority.MEDIUM) //
							.build());

			// Wait for the connection
			for (var i = 0; i < 100 && !sut.websocket.isConnected(); i++) {
				Thread.sleep(100);
			}
			assertTrue("Edge did not connect", sut.websocket.isConnected());

			// A sender that does not wrap by itself, like the Schedule and SystemLog
			// handlers
			assertTrue(sut.websocket.sendMessage(new GenericJsonrpcNotification("test", new JsonObject())));

			for (var i = 0; i < 100 && received.stream().noneMatch(EdgeRpcWrappingTest::isTestPayload); i++) {
				Thread.sleep(100);
			}

			assertFalse(received.isEmpty());
			assertTrue(received.stream().anyMatch(EdgeRpcWrappingTest::isTestPayload));
			for (var notification : received) {
				assertEquals("Unwrapped notification: " + notification, EdgeRpcNotification.METHOD,
						notification.getMethod());
				final var edgeRpc = EdgeRpcNotification.from(notification);
				assertEquals(EDGE_ID, edgeRpc.getEdgeId());
				// Never wrapped twice
				assertFalse(EdgeRpcNotification.METHOD.equals(edgeRpc.getPayload().getMethod()));
			}

			sut.deactivate();
			server.stop();
		}
	}

	private static boolean isTestPayload(JsonrpcNotification notification) {
		try {
			return EdgeRpcNotification.METHOD.equals(notification.getMethod())
					&& "test".equals(EdgeRpcNotification.from(notification).getPayload().getMethod());
		} catch (Exception e) {
			return false;
		}
	}
}
