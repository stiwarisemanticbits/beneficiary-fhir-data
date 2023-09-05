package gov.cms.bfd.server.war;

import gov.cms.bfd.server.launcher.AppConfiguration;
import gov.cms.bfd.server.launcher.DataServerLauncherApp;
import gov.cms.bfd.sharedutils.config.ConfigLoader;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionTimeoutException;

/**
 * Sets up and starts/stops the server for the end-to-end tests (or e2e tests masquerading as
 * integration tests).
 */
public class ServerExecutor {
  private static final Logger LOGGER = LoggerFactory.getLogger(ServerExecutor.class);

  /**
   * Name of directory under target that contains our web application. The directory must have been
   * already created for us by maven. It must not contain any jar or class files as those would be
   * loaded by a different class loader and make debugging difficult as well as making it difficult
   * to re-use the {@link ConfigLoader} containing the app configuration.
   */
  public static final String TEST_WEBAPP_DIRECTORY = "test-webapp-directory";

  /** Holds information about the running server. */
  @GuardedBy("class synchronized")
  private static DataServerLauncherApp.ServerInfo serverInfo;

  /** Keeps track of the server port we're running the server on. */
  @GuardedBy("class synchronized")
  private static String testServerPort;

  /**
   * Starts the BFD server for tests. If already running, does nothing.
   *
   * @param dbUrl the db url
   * @param dbUsername the db username
   * @param dbPassword the db password
   * @return {@code true} if the server is running, if {@code false} the server failed to start up
   * @throws IOException if there is an issue setting up the server relating to accessing files
   */
  public static synchronized boolean startServer(String dbUrl, String dbUsername, String dbPassword)
      throws IOException {
    if (serverInfo != null) {
      // Nothing to do since its already running.
      return true;
    }

    LOGGER.info("Starting IT server with DB: {}", dbUrl);

    // Set up the paths we require for the server war dependencies
    String targetPath = "target";
    String workDirectory = targetPath + "/server-work";
    String warArtifactLocation = targetPath + "/" + TEST_WEBAPP_DIRECTORY;
    String serverPortsFile = workDirectory + "/server-ports.properties";
    // These two files are copied into server-work during build time by maven for convenience
    // from: bfd-server/dev/ssl-stores
    String keyStore = workDirectory + "/server-keystore.pfx";
    String trustStore = workDirectory + "/server-truststore.pfx";

    // Validate the paths and properties needed to run the server war exist
    if (!validateRequiredServerSetup(
        targetPath, workDirectory, warArtifactLocation, serverPortsFile, keyStore, trustStore)) {
      return false;
    }

    String portFileContents = Files.readString(Path.of(serverPortsFile)).trim();
    String serverPort = portFileContents.substring(portFileContents.indexOf('=') + 1);
    LOGGER.info("Configured server to run on HTTPS port {}.", serverPort);

    final var appSettings = new HashMap<String, String>();
    addServerSettings(appSettings, dbUrl, dbUsername, dbPassword);
    final var configLoader = ConfigLoader.builder().addSingle(appSettings::get).build();
    appSettings.put("BFD_PORT", serverPort);
    appSettings.put("BFD_KEYSTORE", keyStore);
    appSettings.put("BFD_TRUSTSTORE", trustStore);
    appSettings.put("BFD_WAR", warArtifactLocation);
    AppConfiguration appConfig = AppConfiguration.loadConfig(configLoader);
    serverInfo = DataServerLauncherApp.createServer(appConfig);

    // Tells jetty to scan the classes in our normal classpath instead of
    // scanning jars and class files within the webapp directory.  This is
    // necessary to allow spring to find our configuration class.
    serverInfo
        .getWebapp()
        .setAttribute(
            "org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern", ".*/classes/.*");

    // Installs our configuration as an attribute in the ServletContext so that
    // ServerInitializer can reuse it.  This prevents it trying to configure the
    // application using environment variables and system properties.
    serverInfo
        .getWebapp()
        .setAttribute(SpringConfiguration.CONFIG_LOADER_CONTEXT_NAME, configLoader);

    // OK - fire it up.  This is asynchronous so it returns immediately.
    try {
      serverInfo.getServer().start();
    } catch (Exception ex) {
      throw new RuntimeException("Caught exception when starting server.", ex);
    }

    // Wait for the server to begin listening on its port.
    try {
      Awaitility.await()
          .atMost(2, TimeUnit.MINUTES)
          .until(
              () -> {
                try (Socket ignored = new Socket("localhost", Integer.parseInt(serverPort))) {
                  return true;
                } catch (Exception e) {
                  return false;
                }
              });
    } catch (ConditionTimeoutException e) {
      throw new RuntimeException("Error: Server failed to start within 120 seconds.", e);
    }
    testServerPort = serverPort;
    return true;
  }

  /**
   * Add the necessary BFD settings to the {@link Map} used by our {@link ConfigLoader}.
   *
   * @param appSettings map to receive the settings
   * @param dbUrl the db url
   * @param dbUsername the db username
   * @param dbPassword the db password
   */
  private static void addServerSettings(
      Map<String, String> appSettings, String dbUrl, String dbUsername, String dbPassword) {
    // FUTURE: Inherit these from system properties? Which of these are valuable to pass?
    String pacEnabled = "true";
    String pacOldMbiHashEnabled = "true";
    String pacClaimSourceTypes = "fiss,mcs";
    String includeFakeDrugCode = "true";
    String includeFakeOrgName = "true";

    appSettings.put("bfdServer.pac.enabled", pacEnabled);
    appSettings.put("bfdServer.pac.oldMbiHash.enabled", pacOldMbiHashEnabled);
    appSettings.put("bfdServer.pac.claimSourceTypes", pacClaimSourceTypes);
    appSettings.put("bfdServer.db.url", dbUrl);
    appSettings.put("bfdServer.db.username", dbUsername);
    appSettings.put("bfdServer.db.password", dbPassword);
    appSettings.put("bfdServer.include.fake.drug.code", includeFakeDrugCode);
    appSettings.put("bfdServer.include.fake.org.name", includeFakeOrgName);
  }

  /**
   * Gets the port our server is listening to (if any).
   *
   * @return the port as a string
   */
  @Nullable
  public static synchronized String getServerPort() {
    return testServerPort;
  }

  /**
   * Checks if the server is running.
   *
   * @return true if the server is running
   */
  public static synchronized boolean isRunning() {
    return serverInfo != null && serverInfo.getServer().isRunning();
  }

  /** Stops the server process. */
  public static synchronized void stopServer() {
    if (isRunning()) {
      try {
        serverInfo.getServer().stop();
        serverInfo.getServer().join();
      } catch (Exception ex) {
        LOGGER.error("Caught exception while stopping server: message={}", ex.getMessage(), ex);
        throw new RuntimeException(ex);
      }
      serverInfo = null;
      testServerPort = null;
      LOGGER.info("Stopped server.");
    } else {
      LOGGER.warn("Tried to destroy server process but was not running.");
    }
  }

  /**
   * Validate required server setup variables and paths exist.
   *
   * @param targetPath the target directory location
   * @param serverWorkPath the server-work directory location
   * @param warArtifactLocation the war artifact location
   * @param serverPortsFileLocation the server ports file location
   * @param keyStoreLocation the key store location
   * @param trustStoreLocation the trust store location
   * @return false if required paths or properties dont exist
   */
  private static boolean validateRequiredServerSetup(
      String targetPath,
      String serverWorkPath,
      String warArtifactLocation,
      String serverPortsFileLocation,
      String keyStoreLocation,
      String trustStoreLocation) {

    // Check required paths exist
    boolean targetIsDir = Files.isDirectory(Paths.get(targetPath));
    boolean serverWorkExists = Files.exists(Paths.get(serverWorkPath));
    boolean serverPortFileExists = Files.exists(Paths.get(serverPortsFileLocation));
    boolean keyStoreExists = Files.exists(Paths.get(keyStoreLocation));
    boolean trustStoreExists = Files.exists(Paths.get(trustStoreLocation));
    if (!targetIsDir
        || !serverWorkExists
        || !serverPortFileExists
        || !keyStoreExists
        || !trustStoreExists) {
      LOGGER.error("Could not setup server; could not find required path.");
      LOGGER.error("   found target: {}", targetIsDir);
      LOGGER.error("   found target/server-work: {}", serverWorkExists);
      LOGGER.error("   found server port file: {}", serverPortFileExists);
      LOGGER.error("   found keystore: {}", keyStoreExists);
      LOGGER.error("   found trust store: {}", trustStoreExists);
      return false;
    }

    if (!Files.exists(Paths.get(warArtifactLocation))) {
      LOGGER.error("Test setup could not find artifact war at: {}", warArtifactLocation);
      return false;
    }

    return true;
  }
}
