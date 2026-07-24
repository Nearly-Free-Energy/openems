package io.openems.backend.metadata.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.Test;

import io.openems.backend.common.test.DummyEventAdmin;
import io.openems.common.session.Language;

public class MetadataFileTest {

	@Test
	public void testGenerateUpdateMetadataCacheNotification() throws Exception {
		final var path = Files.createTempFile("metadata-file-test", ".json");
		Files.writeString(path, """
				{
				  "edges": {
				    "edge0": {
				      "apikey": "apikey0",
				      "comment": "Edge 0"
				    },
				    "edge1": {
				      "apikey": "apikey1",
				      "comment": "Edge 1"
				    },
				    "edgeWithoutApikey": {
				      "apikey": "",
				      "comment": "Edge without Apikey"
				    }
				  }
				}
				""");

		final var sut = createMetadataFile(path);
		final var notification = sut.generateUpdateMetadataCacheNotification();

		assertEquals(Map.of(//
				"apikey0", "edge0", //
				"apikey1", "edge1"), //
				notification.getApikeysToEdgeIds());
		assertFalse(notification.getApikeysToEdgeIds().containsKey(""));
	}

	@Test
	public void testConfigurableDefaultLanguageAndInstanceIsolation() throws Exception {
		final var path = createEmptyMetadataFile();
		final var englishMetadata = createMetadataFile(path);
		final var frenchMetadata = createMetadataFile(path);

		activate(englishMetadata, path, Language.EN);
		activate(frenchMetadata, path, Language.FR);

		assertEquals(Language.EN, englishMetadata.getUser("admin").orElseThrow().getLanguage());
		assertEquals(Language.FR, frenchMetadata.getUser("admin").orElseThrow().getLanguage());
	}

	@Test
	public void testUpdateUserLanguageUpdatesSharedUser() throws Exception {
		final var path = createEmptyMetadataFile();
		final var sut = createMetadataFile(path);
		activate(sut, path, Language.EN);

		final var user = sut.getUser("admin").orElseThrow();
		sut.updateUserLanguage(user, Language.NL);

		assertEquals(Language.NL, sut.getUser("admin").orElseThrow().getLanguage());
	}

	private static Path createEmptyMetadataFile() throws Exception {
		final var path = Files.createTempFile("metadata-file-language-test", ".json");
		Files.writeString(path, """
				{
				  "edges": {}
				}
				""");
		return path;
	}

	private static void activate(MetadataFile sut, Path path, Language defaultLanguage) throws Exception {
		final Config config = new Config() {

			@Override
			public Class<? extends Annotation> annotationType() {
				return Config.class;
			}

			@Override
			public String path() {
				return path.toString();
			}

			@Override
			public Language defaultLanguage() {
				return defaultLanguage;
			}

			@Override
			public String webconsole_configurationFactory_nameHint() {
				return "Metadata.File";
			}
		};
		final Method activateMethod = MetadataFile.class.getDeclaredMethod("activate", Config.class);
		activateMethod.setAccessible(true);
		activateMethod.invoke(sut, config);
	}

	private static MetadataFile createMetadataFile(Path path) throws Exception {
		final var sut = new MetadataFile();
		final Field pathField = MetadataFile.class.getDeclaredField("path");
		pathField.setAccessible(true);
		pathField.set(sut, path.toString());
		final Field eventAdminField = MetadataFile.class.getDeclaredField("eventAdmin");
		eventAdminField.setAccessible(true);
		eventAdminField.set(sut, new DummyEventAdmin(event -> {
		}));
		return sut;
	}
}
