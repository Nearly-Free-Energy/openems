package io.openems.edge.ess.srne.batteryinverter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import io.openems.common.OpenemsConstants;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.utils.JsonUtils;

/**
 * Persists the night charge stop's one-night override (ADR-017) in a small JSON
 * file in the OpenEMS data directory, outside the component configuration, so
 * saving it never replaces the driver component. The file holds the stop SoC
 * and the instant it was written; it is replaced atomically (temporary file,
 * flushed, then renamed) and deleted on a cancel or once its night is over.
 */
final class NightChargeOverrideStore {

	/**
	 * A persisted override.
	 *
	 * @param soc       the stop SoC in percent
	 * @param writtenAt the instant it was written
	 */
	record Stored(int soc, Instant writtenAt) {
	}

	/**
	 * The result of a load.
	 *
	 * @param stored  the persisted override, or null if none could be read
	 * @param problem why a present file could not be used, or null
	 */
	record Loaded(Stored stored, String problem) {
	}

	private static final String DIRECTORY = "srne";
	private static final String SOC = "soc";
	private static final String WRITTEN_AT = "writtenAt";

	private final Path file;

	NightChargeOverrideStore(Path file) {
		this.file = file;
	}

	/**
	 * The store of a component in the OpenEMS data directory.
	 *
	 * @param componentId the component ID
	 * @return the store
	 */
	static NightChargeOverrideStore inDataDir(String componentId) {
		return inDirectory(Paths.get(OpenemsConstants.getOpenemsDataDir(), DIRECTORY), componentId);
	}

	/**
	 * The store of a component in a directory.
	 *
	 * @param directory   the directory
	 * @param componentId the component ID
	 * @return the store
	 */
	static NightChargeOverrideStore inDirectory(Path directory, String componentId) {
		return new NightChargeOverrideStore(directory.resolve(componentId + "-night-charge-stop-override.json"));
	}

	Path file() {
		return this.file;
	}

	/**
	 * Reads the persisted override. Never throws: a missing file is no override, an
	 * unreadable or corrupt one is reported as a problem.
	 *
	 * @return the result
	 */
	Loaded load() {
		if (!Files.exists(this.file)) {
			return new Loaded(null, null);
		}
		try {
			var json = JsonUtils.parseToJsonObject(Files.readString(this.file, StandardCharsets.UTF_8));
			var soc = JsonUtils.getAsOptionalInt(json, SOC);
			var writtenAt = JsonUtils.getAsOptionalString(json, WRITTEN_AT);
			if (soc.isEmpty() || writtenAt.isEmpty()) {
				return new Loaded(null, this.file + " lacks " + SOC + " or " + WRITTEN_AT);
			}
			return new Loaded(new Stored(soc.get(), Instant.parse(writtenAt.get())), null);
		} catch (IOException | OpenemsNamedException | RuntimeException e) {
			return new Loaded(null, this.file + " is unreadable: " + e.getMessage());
		}
	}

	/**
	 * Replaces the persisted override atomically, or deletes it.
	 *
	 * @param stored the override, or null to delete it
	 * @throws IOException on a write failure; the previous file is left intact
	 */
	void save(Stored stored) throws IOException {
		if (stored == null) {
			Files.deleteIfExists(this.file);
			return;
		}
		var json = JsonUtils.buildJsonObject() //
				.addProperty(SOC, stored.soc()) //
				.addProperty(WRITTEN_AT, stored.writtenAt().toString()) //
				.build();
		Files.createDirectories(this.file.getParent());
		var temp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
		// Flushed to the device before the rename, so a power loss cannot leave an
		// empty file under the final name.
		try (var channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING)) {
			var buffer = ByteBuffer.wrap(json.toString().getBytes(StandardCharsets.UTF_8));
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
			channel.force(true);
		}
		try {
			Files.move(temp, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
