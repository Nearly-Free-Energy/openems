package io.openems.edge.ess.srne.batteryinverter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class NightChargeOverrideStoreTest {

	@Test
	public void testRoundTripReplaceAndDelete(@TempDir Path dir) throws Exception {
		var store = NightChargeOverrideStore.inDirectory(dir.resolve("srne"), "batteryInverter0");
		var missing = store.load();
		assertNull(missing.stored());
		assertNull(missing.problem());

		var stored = new NightChargeOverrideStore.Stored(40, Instant.parse("2026-11-12T11:00:00Z"));
		store.save(stored);
		assertEquals(stored, store.load().stored());
		var replaced = new NightChargeOverrideStore.Stored(70, Instant.parse("2026-11-13T11:00:00Z"));
		store.save(replaced);
		assertEquals(replaced, store.load().stored());
		// No temporary file is left behind.
		try (var files = Files.list(store.file().getParent())) {
			assertEquals(1, files.count());
		}

		store.save(null);
		assertFalse(Files.exists(store.file()));
		assertNull(store.load().stored());
	}

	@Test
	public void testUnreadableFileIsAProblemNotAnException(@TempDir Path dir) throws Exception {
		var store = NightChargeOverrideStore.inDirectory(dir, "batteryInverter0");
		for (var content : new String[] { "", "{not json", "[]", "{\"soc\":40}", "{\"writtenAt\":\"x\"}",
				"{\"soc\":40,\"writtenAt\":\"yesterday\"}",
				"{\"soc\":\"forty\",\"writtenAt\":\"2026-11-12T11:00:00Z\"}" }) {
			Files.writeString(store.file(), content);
			var loaded = store.load();
			assertNull(loaded.stored(), content);
			assertNotNull(loaded.problem(), content);
		}
		// A directory in place of the file cannot be read either.
		Files.delete(store.file());
		Files.createDirectory(store.file());
		assertNotNull(store.load().problem());
	}
}
