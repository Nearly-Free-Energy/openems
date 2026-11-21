package io.openems.backend.edge.application;

import static io.openems.common.utils.ThreadPoolUtils.shutdownAndAwaitTermination;
import static java.util.stream.Collectors.toCollection;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.backend.common.edge.jsonrpc.ConnectedEdges;
import io.openems.backend.edge.client.WebsocketClient;
import io.openems.backend.edge.server.WebsocketServer;
import io.openems.backend.edge.server.WsData;

public class CyclicTask implements Runnable {

	/**
	 * How long an Edge with an accepted handshake counts as connected before its
	 * connection is listed by the {@link WebsocketServer}.
	 */
	private static final long HANDSHAKE_GRACE_NANOS = TimeUnit.SECONDS.toNanos(10);

	private final Logger log = LoggerFactory.getLogger(CyclicTask.class);
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

	private final WebsocketClient client;
	private final Supplier<WebsocketServer> server;
	private final AtomicBoolean connectedEdgesChangedSinceLastRun = new AtomicBoolean(false);
	/** Edge-ID to time of its accepted handshake; guarded by this. */
	private final Map<String, Long> acceptedHandshakes = new HashMap<>();

	/**
	 * Builds and starts the {@link CyclicTask} via a given
	 * {@link ScheduledExecutorService}.
	 *
	 * @param client the {@link WebsocketClient}
	 * @param server the {@link WebsocketServer}
	 * @return a new {@link CyclicTask}
	 */
	public static CyclicTask from(WebsocketClient client, Supplier<WebsocketServer> server) {
		var task = new CyclicTask(client, server);
		task.executor.scheduleAtFixedRate(task, 5, 5, TimeUnit.SECONDS);
		return task;
	}

	protected void deactivate() {
		shutdownAndAwaitTermination(this.executor, 0);
	}

	public CyclicTask(WebsocketClient client, Supplier<WebsocketServer> server) {
		this.client = client;
		this.server = server;
	}

	@Override
	public void run() {
		try {
			this.printDebugLog();
			this.sendConnectedEdgesNotification();
		} catch (Exception e) {
			this.log.error(e.getMessage());
			this.log.debug("Stacktrace:", e);
		}
	}

	/**
	 * Announce Connected-Edges changed.
	 */
	public void connectedEdgesChanged() {
		this.connectedEdgesChangedSinceLastRun.set(true);
	}

	/**
	 * Announces an Edge to Edge.Manager as soon as its handshake is accepted, i.e.
	 * before the Edge can send anything.
	 *
	 * <p>
	 * Edge.Manager drops data of Edges it does not know yet, and an Edge sends the
	 * values of all its Channels only right after connecting and then every few
	 * minutes. Waiting for the next run would lose that first complete set.
	 *
	 * <p>
	 * Runs on the handshake thread, but never holds up or rejects the Edge:
	 * sending only queues the message, and if the announcement fails the next run
	 * sends it.
	 *
	 * @param edgeId the Edge-ID
	 */
	public synchronized void edgeHandshakeAccepted(String edgeId) {
		this.acceptedHandshakes.put(edgeId, System.nanoTime());
		try {
			this.sendConnectedEdgesNotification(Map.of());
		} catch (RuntimeException e) {
			this.connectedEdgesChangedSinceLastRun.set(true);
			this.log.error("Unable to announce Edge [" + edgeId + "]: " + e.getMessage());
			this.log.debug("Stacktrace:", e);
		}
	}

	private void printDebugLog() {
		this.log.info(new StringBuilder("[") //
				.append(this.client.getName()) //
				.append("] [monitor] ") //
				.append(this.client.debugLog()) //
				.toString());

		var server = this.server.get();
		if (server != null) {
			this.log.info(server.debugLog());
		}
	}

	private void sendConnectedEdgesNotification() {
		var server = this.server.get();
		if (server == null || !this.connectedEdgesChangedSinceLastRun.getAndSet(false)) {
			return;
		}

		// connected Edges to Server changed since last run of CyclicTask
		this.sendConnectedEdgesNotification(server.debugMetrics());
	}

	private synchronized void sendConnectedEdgesNotification(Map<String, Number> metrics) {
		var server = this.server.get();
		if (server == null) {
			return;
		}

		// TODO Performance test. How long does this take? Better record/send only diff?
		var edgeIds = server.getConnections().stream() //
				.map(ws -> (WsData) ws.getAttachment()) //
				.map(wsData -> wsData.getEdgeId()) //
				.filter(Objects::nonNull) //
				.collect(toCollection(HashSet::new));

		// Keep announcing accepted handshakes until their connection is listed
		final var now = System.nanoTime();
		this.acceptedHandshakes.entrySet().removeIf(e -> edgeIds.contains(e.getKey()) //
				|| now - e.getValue() > HANDSHAKE_GRACE_NANOS);
		edgeIds.addAll(this.acceptedHandshakes.keySet());
		if (!this.acceptedHandshakes.isEmpty()) {
			this.connectedEdgesChangedSinceLastRun.set(true);
		}

		if (!this.client.sendMessage(new ConnectedEdges.Notification(edgeIds, metrics))) {
			// Not connected to Edge.Manager; send again in the next run
			this.connectedEdgesChangedSinceLastRun.set(true);
		}
	}

}
