package io.openems.backend.edge.manager;

import static io.openems.common.websocket.WebsocketUtils.getAsString;
import static io.openems.common.websocket.WebsocketUtils.parseRemoteIdentifier;
import static org.java_websocket.framing.CloseFrame.REFUSE;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.Handshakedata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.backend.common.edge.jsonrpc.UpdateMetadataCache;
import io.openems.common.exceptions.OpenemsError;
import io.openems.common.websocket.CommonHttpHeader;

public class OnOpen implements io.openems.common.websocket.OnOpen {

	private final Logger log = LoggerFactory.getLogger(OnOpen.class);
	private final Function<String, Optional<String>> getEdgeIdForApikey;
	private final Supplier<UpdateMetadataCache.Notification> generateUpdateMetadataCacheNotification;
	private final BiConsumer<Logger, String> logInfo;

	public OnOpen(//
			Function<String, Optional<String>> getEdgeIdForApikey, //
			Supplier<UpdateMetadataCache.Notification> generateUpdateMetadataCacheNotification, //
			BiConsumer<Logger, String> logInfo) {
		this.getEdgeIdForApikey = getEdgeIdForApikey;
		this.generateUpdateMetadataCacheNotification = generateUpdateMetadataCacheNotification;
		this.logInfo = logInfo;
	}

	@Override
	public OpenemsError apply(WebSocket ws, Handshakedata handshakedata) {
		// get id from handshake
		final var id = getAsString(handshakedata, "id");
		final var apikey = getAsString(handshakedata, CommonHttpHeader.APIKEY.asString());

		var error = this._apply(ws, id, apikey);
		if (error != null) {
			// close websocket
			ws.closeConnection(REFUSE, "Connection to backend failed. " //
					+ "Remote [" + parseRemoteIdentifier(ws, handshakedata) + "] " //
					+ "Error: " + error.name());
		}
		return error;
	}

	private OpenemsError _apply(WebSocket ws, String id, String apikey) {
		// get websocket attachment
		final WsData wsData = ws.getAttachment();

		if (id == null || apikey == null //
				|| this.getEdgeIdForApikey.apply(apikey).filter(id::equals).isEmpty()) {
			return OpenemsError.COMMON_AUTHENTICATION_FAILED;
		}

		this.logInfo.accept(this.log, "Backend.Edge.Client [" + id + "] connected");

		wsData.setId(id);

		// Register this directly-connected Edge so its data notifications are accepted
		// and persisted. In the multi-edge topology Edges are announced via a
		// ConnectedEdges.Notification (which creates the EdgeCache); a directly
		// connected Edge announces only itself via the 'id' header, so register it
		// here. Without this the EdgeCache is null and handleDataNotification silently
		// drops every TimestampedData/AggregatedData notification.
		wsData.updateEdgeStatus(id, true);

		// Send a UpdateMetadataCache.Notification
		wsData.send(this.generateUpdateMetadataCacheNotification.get());

		return null; // No error
	}
}
