package org.broadinstitute.ddp.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueFactory;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.broadinstitute.ddp.constants.ApplicationProperty;
import org.broadinstitute.ddp.exception.DDPException;
import org.broadinstitute.ddp.secrets.SecretManager;

/**
 * Wrapper around {@link ConfigFactory#load() config load} that
 * allows for on-the-fly overrides of config values during testing.
 * DO NOT CALL {@link #overrideValue(String, String)} unless you
 * are in a test!
 */
@Slf4j
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ConfigManager {
    public static final File TYPESAFE_CONFIG_FILE;

    static {
        // For benefit of GAE. Does not like command line options with "=" characters and env variables with "."
        final var systemPropertyName = ApplicationProperty.APPLICATION_CONFIG.getPropertyName();
        final var gaeConfigPropertyName = systemPropertyName.replace('.', '_');
        final var configFileName = Optional.ofNullable(System.getenv(gaeConfigPropertyName))
                .orElse(System.getProperty(systemPropertyName));

        TYPESAFE_CONFIG_FILE = Optional.ofNullable(configFileName).map(File::new).orElse(null);
        if (TYPESAFE_CONFIG_FILE != null && !TYPESAFE_CONFIG_FILE.exists()) {
            throw new DDPException("The config file " + TYPESAFE_CONFIG_FILE.getAbsolutePath() + " doesn't exist");
        }
    }

    private final Config cfg;

    private static ConfigManager configManager;

    private final Map<String, String> overrides = new HashMap<>();

    public static ConfigManager init(final Config config) {
        configManager = new ConfigManager(config);
        return configManager;
    }

    public static synchronized ConfigManager getInstance() {
        if (configManager == null) {
            try {
                init(parseConfig());
            } catch (final Exception e) {
                throw new DDPException("Could not initialize config", e);
            }
        }
        return configManager;
    }

    /**
     * Re-reads the main config file directly, bypassing any typesafe config caching.  Useful
     * for responding to changes to the config file without a reboot.
     * Unless you are specifically looking to re-read directly from disk,
     * please use {@link #getInstance()}
     */
    public static Config parseConfig() {
        Config configCloud = ConfigFactory.empty();
        if (isSecretManagerConfigurationSpecified()) {
            log.info("Secret manager configuration found. Trying to load the configuration from the Secret Manager");
            configCloud = loadFromSecretManager();
        }

        Config configLocal = ConfigFactory.empty();
        if (isLocalConfigurationFileSpecified()) {
            log.info("The config file name was specified. Trying to load the configuration from the local file");
            configLocal = ConfigFactory.parseFile(TYPESAFE_CONFIG_FILE);
        }

        if (configCloud.isEmpty() && configLocal.isEmpty()) {
            log.info("no configuration was specified, an empty configuration will be used. "
                            + "Use properties '{}' to use a local file or '{}' and '{}' to use a cloud secret",
                    ApplicationProperty.APPLICATION_CONFIG.getPropertyName(),
                    ApplicationProperty.GOOGLE_SECRET_PROJECT.getPropertyName(),
                    ApplicationProperty.GOOGLE_SECRET_NAME.getPropertyName());
            return null;
        }

        return configLocal.withFallback(configCloud).resolve();
    }

    private static boolean isLocalConfigurationFileSpecified() {
        return TYPESAFE_CONFIG_FILE != null;
    }

    private static boolean isSecretManagerConfigurationSpecified() {
        return StringUtils.isNotBlank(PropertyManager.getProperty(ApplicationProperty.GOOGLE_SECRET_PROJECT, null))
                && StringUtils.isNotBlank(PropertyManager.getProperty(ApplicationProperty.GOOGLE_SECRET_NAME, null));
    }

    private static Config loadFromSecretManager() {
        final var projectName = PropertyManager.getProperty(ApplicationProperty.GOOGLE_SECRET_PROJECT);
        if (projectName.isEmpty()) {
            log.error(ApplicationProperty.GOOGLE_SECRET_PROJECT.getPropertyName() + " property is not set");
            return ConfigFactory.empty();
        }

        final var secretName = PropertyManager.getProperty(ApplicationProperty.GOOGLE_SECRET_NAME);
        if (secretName.isEmpty()) {
            log.error(ApplicationProperty.GOOGLE_SECRET_NAME.getPropertyName() + " property is not set");
            return ConfigFactory.empty();
        }

        final var configurationContents = SecretManager.get(
                projectName.get(),
                secretName.get(),
                PropertyManager.getProperty(ApplicationProperty.GOOGLE_SECRET_VERSION, SecretManager.LATEST));

        return ConfigFactory.parseString(configurationContents.orElseThrow(() -> {
            return new DDPException("The secret " + secretName + " doesn't exist");
        }));
    }

    /**
     * Reads the config file to a string
     */
    public static String readConfigFile() throws IOException {
        return FileUtils.readFileToString(TYPESAFE_CONFIG_FILE, StandardCharsets.UTF_8);
    }

    /**
     * Overwrites the config file.  ONLY USE IN TESTS.
     */
    public static void rewriteConfigFile(String newContents) throws IOException  {
        FileUtils.writeStringToFile(TYPESAFE_CONFIG_FILE, newContents, StandardCharsets.UTF_8);
    }

    /**
     * Overwrites the config file.  ONLY USE IN TESTS.
     */
    public static void rewriteConfigFile(Config newConfig) throws IOException  {
        FileUtils.writeStringToFile(TYPESAFE_CONFIG_FILE, newConfig.root().render(ConfigRenderOptions.concise()), StandardCharsets.UTF_8);
    }

    /**
     * Returns the current overrides
     */
    public Map<String, String> getOverrides() {
        return Collections.unmodifiableMap(overrides);
    }

    /**
     * Should only be called from tests to inject
     * and override for the given path.  Should
     * only be used from test code!
     */
    public void overrideValue(String path, String value) {
        overrides.put(path, value);
    }

    /**
     * Returns the {@link Config config} for pepper
     */
    public Config getConfig() {
        Config cfgWithOverride = cfg;

        for (Map.Entry<String, String> override : overrides.entrySet()) {
            ConfigValue overrideValue = ConfigValueFactory.fromAnyRef(override.getValue(), "Overriding via " + this.getClass().getName());
            cfgWithOverride = cfgWithOverride.withValue(override.getKey(), overrideValue);
            log.info("Overriding config value for key {}", override.getKey());
        }
        return cfgWithOverride;
    }
}
