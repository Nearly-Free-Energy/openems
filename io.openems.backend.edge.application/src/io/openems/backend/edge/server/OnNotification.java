package io.openems.backend.edge.server;

import static io.openems.common.utils.JsonrpcUtils.simplifyJsonrpcMessage;
import static io.openems.common.utils.StringUtils.toShortString;

import org.java_websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.backend.edge.application.BackendEdgeServerApp;
import io.openems.backend.metrics.prometheus.PrometheusMetrics;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.jsonrpc.base.JsonrpcNotification;
import io.openems.common.jsonrpc.notification.EdgeRpcNotification;

public class OnNotification implements io.openems.common.websocket.OnNotification {

	private final Logger log = LoggerFactory.getLogger(OnNotification.class);

	private final String name;
	private final BackendEdgeServerApp app;

	public OnNotification(//
			String name, //
			BackendEdgeServerApp app) {
		this.name = name;
		this.app = app;
	}

	@Override
	public void accept(WebSocket ws, JsonrpcNotification notification) throws OpenemsNamedException {
		try (final var timer = PrometheusMetrics.WEBSOCKET_REQUEST
				.labelValues(this.name, notification.getFullyQualifiedMethod()).startTimer()) {
			WsData wsData = ws.getAttachment();
			var edgeId = wsData.getEdgeId();
			if (edgeId == null) {
				return;
			}
			final var payload = this.unwrapEdgeRpcNotification(edgeId, notification);
			if (payload == null) {
				return;
			}
			wsData.debugLog(this.log,
					() -> "NOTIFICATION " + edgeId + ": " + toShortString(simplifyJsonrpcMessage(payload), 200));
			this.app.sendNotificationToEdgeManager(edgeId, payload);
		}
	}

	/**
	 * Edges configured for a direct Edge.Manager connection wrap every
	 * notification in an {@link EdgeRpcNotification} themselves. Unwraps it, so it
	 * is not wrapped a second time on its way to Edge.Manager.
	 *
	 * @param edgeId       the authenticated Edge-ID of the connection
	 * @param notification the {@link JsonrpcNotification} received from the Edge
	 * @return the notification to forward; null if it was wrapped for another
	 *         Edge-ID
	 * @throws OpenemsNamedException on parse error
	 */
	private JsonrpcNotification unwrapEdgeRpcNotification(String edgeId, JsonrpcNotification notification)
			throws OpenemsNamedException {
		if (!EdgeRpcNotification.METHOD.equals(notification.getMethod())) {
			return notification;
		}
		final var edgeRpc = EdgeRpcNotification.from(notification);
		if (!edgeId.equals(edgeRpc.getEdgeId())) {
			this.log.warn("[{}] Dropped notification wrapped for other Edge-ID [{}]", edgeId, edgeRpc.getEdgeId());
			return null;
		}
		return edgeRpc.getPayload();
	}
}
