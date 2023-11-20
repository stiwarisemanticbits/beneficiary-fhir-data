package gov.cms.bfd.server.war;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.LoggingInterceptor;
import com.google.common.collect.ImmutableSet;
import gov.cms.bfd.model.rif.LoadedBatch;
import gov.cms.bfd.model.rif.LoadedFile;
import gov.cms.bfd.model.rif.RifFileEvent;
import gov.cms.bfd.model.rif.RifFilesEvent;
import gov.cms.bfd.model.rif.RifRecordBase;
import gov.cms.bfd.model.rif.SkippedRifRecord;
import gov.cms.bfd.model.rif.entities.Beneficiary;
import gov.cms.bfd.model.rif.entities.BeneficiaryHistory;
import gov.cms.bfd.model.rif.entities.BeneficiaryMonthly;
import gov.cms.bfd.model.rif.entities.CarrierClaim;
import gov.cms.bfd.model.rif.entities.CarrierClaimLine;
import gov.cms.bfd.model.rif.entities.DMEClaim;
import gov.cms.bfd.model.rif.entities.DMEClaimLine;
import gov.cms.bfd.model.rif.entities.HHAClaim;
import gov.cms.bfd.model.rif.entities.HHAClaimLine;
import gov.cms.bfd.model.rif.entities.HospiceClaim;
import gov.cms.bfd.model.rif.entities.HospiceClaimLine;
import gov.cms.bfd.model.rif.entities.InpatientClaim;
import gov.cms.bfd.model.rif.entities.InpatientClaimLine;
import gov.cms.bfd.model.rif.entities.OutpatientClaim;
import gov.cms.bfd.model.rif.entities.OutpatientClaimLine;
import gov.cms.bfd.model.rif.entities.PartDEvent;
import gov.cms.bfd.model.rif.entities.SNFClaim;
import gov.cms.bfd.model.rif.entities.SNFClaimLine;
import gov.cms.bfd.model.rif.samples.StaticRifResource;
import gov.cms.bfd.model.rif.samples.StaticRifResourceGroup;
import gov.cms.bfd.pipeline.PipelineTestUtils;
import gov.cms.bfd.pipeline.ccw.rif.extract.RifFileRecords;
import gov.cms.bfd.pipeline.ccw.rif.extract.RifFilesProcessor;
import gov.cms.bfd.pipeline.ccw.rif.load.CcwRifLoadTestUtils;
import gov.cms.bfd.pipeline.ccw.rif.load.LoadAppOptions;
import gov.cms.bfd.pipeline.ccw.rif.load.RifLoader;
import gov.cms.bfd.server.war.commons.ClaimType;
import gov.cms.bfd.server.war.commons.CommonTransformerUtils;
import gov.cms.bfd.server.war.stu3.providers.Stu3EobSamhsaMatcherTest;
import gov.cms.bfd.sharedutils.database.DatabaseUtils;
import gov.cms.bfd.sharedutils.exceptions.BadCodeMonkeyException;
import io.restassured.response.Response;
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.management.MBeanServer;
import javax.net.ssl.SSLContext;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.Table;
import javax.sql.DataSource;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Contains test utilities. */
public final class ServerTestUtils {
  private static final Logger LOGGER = LoggerFactory.getLogger(ServerTestUtils.class);

  /** The singleton {@link ServerTestUtils} instance to use everywhere. */
  private static ServerTestUtils SINGLETON;

  /** The server's base url. */
  private final String serverBaseUrl;

  /** A set of ignored paths for json comparison testing. */
  public static final Set<String> JSON_COMPARE_IGNORED_PATHS =
      ImmutableSet.of(
          "\"/id\"",
          "\"/date\"",
          "\"/created\"",
          "\"/link/[0-9]/url\"",
          "\"/implementation/url\"",
          "\"/entry/[0-9]/fullUrl\"",
          "\"/meta\"",
          "\"/meta/lastUpdated\"",
          "\"/entry/[0-9]/resource/meta/lastUpdated\"",
          "\"/entry/[0-9]/resource/meta\"",
          "\"/entry/[0-9]/resource/created\"",
          "\"/procedure/[0-9]/date\"",
          "\"/entry/[0-9]/resource/procedure/[0-9]/date\"",
          "\"/software/version\"");

  /**
   * Constructs a new {@link ServerTestUtils} instance. Marked <code>private</code>; use {@link
   * #get()}, instead.
   */
  private ServerTestUtils() {
    this.serverBaseUrl = initServerBaseUrl();
  }

  /**
   * Get server test utils.
   *
   * <p>We use a singleton and cache all the fields because creating some of the fields stored in
   * the PipelineApplicationState is EXPENSIVE (it maintains a DB connection pool), so we don't want
   * to have to re-create it for every test.
   *
   * @return the singleton {@link ServerTestUtils} instance to use everywhere
   */
  public static synchronized ServerTestUtils get() {
    if (SINGLETON == null) {
      SINGLETON = new ServerTestUtils();
    }

    return SINGLETON;
  }

  /**
   * Init server base url string.
   *
   * @return the value to use for {@link #getServerBaseUrl()}
   */
  private static String initServerBaseUrl() {
    Properties testServerPorts = initTestServerPortsProperties();
    int httpsPort = Integer.parseInt(testServerPorts.getProperty("server.port.https"));
    String serverBaseUrl = String.format("https://localhost:%d", httpsPort);
    return serverBaseUrl;
  }

  /**
   * Returns the server port {@link Properties} that are written to a file during the <code>
   * reserve-server-ports</code> maven plugin step. This allows us to use the same server port
   * during the entire test runtime.
   *
   * @return the server port properties
   */
  private static Properties initTestServerPortsProperties() {
    /*
     * The working directory for tests will either be the module directory or their parent
     * directory. With that knowledge, we're searching for the target/server-work directory.
     */
    Path serverRunDir = Paths.get("target", "server-work");
    if (!Files.isDirectory(serverRunDir))
      serverRunDir = Paths.get("bfd-server-war", "target", "server-work");
    if (!Files.isDirectory(serverRunDir))
      throw new IllegalStateException(
          "Unable to find server-work directory from current working directory: "
              + Paths.get(".").toAbsolutePath());

    Properties serverPortsProps = new Properties();
    try {
      serverPortsProps.load(
          new FileReader(serverRunDir.resolve("server-ports.properties").toFile()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return serverPortsProps;
  }

  /**
   * Creates a new FHIR {@link IGenericClient} for use, configured to use the {@link
   * ClientSslIdentity#TRUSTED} login.
   *
   * @return a FHIR client
   */
  public IGenericClient createFhirClient() {
    return createFhirClient(Optional.of(ClientSslIdentity.TRUSTED));
  }

  /**
   * Create fhir client with the specified ssl identity.
   *
   * @param clientSslIdentity the {@link ClientSslIdentity} to use as a login for the FHIR server
   * @return a new FHIR {@link IGenericClient} for use
   */
  public IGenericClient createFhirClient(Optional<ClientSslIdentity> clientSslIdentity) {
    return createFhirClient("v1", clientSslIdentity);
  }

  /**
   * Creates a new FHIR {@link IGenericClient} for use, configured to use the {@link
   * ClientSslIdentity#TRUSTED} login for FIHR v2 server.
   *
   * @return a FHIR client
   */
  public IGenericClient createFhirClientV2() {
    return createFhirClientV2(Optional.of(ClientSslIdentity.TRUSTED));
  }

  /**
   * Create V2 fhir client with the specified ssl identity.
   *
   * @param clientSslIdentity the {@link ClientSslIdentity} to use as a login for the FV2 HIR server
   * @return a new FHIR {@link IGenericClient} for use
   */
  public IGenericClient createFhirClientV2(Optional<ClientSslIdentity> clientSslIdentity) {
    return createFhirClient("v2", clientSslIdentity, FhirContext.forR4());
  }

  /**
   * Creates a FHIR client.
   *
   * @param versionId the v1 or v2 identifier to use as a part of the URL for the FHIR server
   * @param clientSslIdentity the {@link ClientSslIdentity} to use as a login for the FHIR server
   * @return a new FHIR {@link IGenericClient} for use
   */
  private IGenericClient createFhirClient(
      String versionId, Optional<ClientSslIdentity> clientSslIdentity) {
    // Default behavor before was to spawn a DSTU3 context, so retain that
    return createFhirClient(versionId, clientSslIdentity, FhirContext.forDstu3());
  }

  /**
   * Creates a FHIR client.
   *
   * @param versionId the v1 or v2 identifier to use as a part of the URL for the FHIR server
   * @param clientSslIdentity the {@link ClientSslIdentity} to use as a login for the FHIR server
   * @param ctx the fhir context
   * @return a new FHIR {@link IGenericClient} for use
   */
  private IGenericClient createFhirClient(
      String versionId, Optional<ClientSslIdentity> clientSslIdentity, FhirContext ctx) {
    // Figure out where the test server is running.
    String fhirBaseUrl = String.format("%s/%s/fhir", getServerBaseUrl(), versionId);

    /*
     * We need to override the FHIR client's SSLContext. Unfortunately, that
     * requires overriding the entire HttpClient that it uses. Otherwise,
     * the settings used here mirror those that the default FHIR HttpClient
     * would use.
     */
    SSLContext sslContext = createSslContext(clientSslIdentity);

    /*
     * The default timeout is 10s, which was failing for batches of 100. A
     * 300s timeout was failing for batches of 100 once Part B claims were
     * mostly mapped, so batches were cut to 10, which ran at 12s or so,
     * each.
     */
    ctx.getRestfulClientFactory().setSocketTimeout((int) TimeUnit.MINUTES.toMillis(5));
    PoolingHttpClientConnectionManager connectionManager =
        new PoolingHttpClientConnectionManager(
            RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http", PlainConnectionSocketFactory.getSocketFactory())
                .register("https", new SSLConnectionSocketFactory(sslContext))
                .build(),
            null,
            null,
            null,
            5000,
            TimeUnit.MILLISECONDS);
    @SuppressWarnings("deprecation")
    RequestConfig defaultRequestConfig =
        RequestConfig.custom()
            .setSocketTimeout(ctx.getRestfulClientFactory().getSocketTimeout())
            .setConnectTimeout(ctx.getRestfulClientFactory().getConnectTimeout())
            .setConnectionRequestTimeout(
                ctx.getRestfulClientFactory().getConnectionRequestTimeout())
            .setStaleConnectionCheckEnabled(true)
            .build();
    HttpClient httpClient =
        HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(defaultRequestConfig)
            .disableCookieManagement()
            .build();
    ctx.getRestfulClientFactory().setHttpClient(httpClient);

    IGenericClient client = ctx.newRestfulGenericClient(fhirBaseUrl);

    /*
     * The FHIR client logging (for tests) can be managed via the
     * `src/test/resources/logback-test.xml` file.
     */
    LoggingInterceptor loggingInterceptor = new LoggingInterceptor();
    loggingInterceptor.setLogRequestSummary(LOGGER.isDebugEnabled());
    loggingInterceptor.setLogResponseSummary(LOGGER.isDebugEnabled());
    loggingInterceptor.setLogRequestHeaders(LOGGER.isTraceEnabled());
    loggingInterceptor.setLogResponseHeaders(LOGGER.isTraceEnabled());
    loggingInterceptor.setLogRequestBody(LOGGER.isTraceEnabled());
    loggingInterceptor.setLogResponseBody(LOGGER.isTraceEnabled());
    client.registerInterceptor(loggingInterceptor);

    return client;
  }

  /**
   * Creates the ssl context.
   *
   * @param clientSslIdentity the {@link ClientSslIdentity} to use as a login for the server
   * @return a new {@link SSLContext} for HTTP clients connecting to the server to use
   */
  public SSLContext createSslContext(Optional<ClientSslIdentity> clientSslIdentity) {
    SSLContext sslContext;
    try {
      SSLContextBuilder sslContextBuilder = SSLContexts.custom();

      // If a client key is desired, configure the key store with it.
      if (clientSslIdentity.isPresent())
        sslContextBuilder.loadKeyMaterial(
            clientSslIdentity.get().getKeyStore(),
            clientSslIdentity.get().getStorePassword(),
            clientSslIdentity.get().getKeyPass());

      // Configure the trust store.
      sslContextBuilder.loadTrustMaterial(
          getClientTrustStorePath().toFile(), "changeit".toCharArray());

      sslContext = sslContextBuilder.build();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (KeyManagementException
        | UnrecoverableKeyException
        | NoSuchAlgorithmException
        | KeyStoreException
        | CertificateException e) {
      throw new IllegalStateException(e);
    }
    return sslContext;
  }

  /**
   * Gets the {@link #serverBaseUrl}.
   *
   * @return the base URL for the server (not for the FHIR servlet, but just the server itself)
   */
  public String getServerBaseUrl() {
    return serverBaseUrl;
  }

  /**
   * Gets the local {@link Path} that the project can be found in.
   *
   * @return the local {@link Path}
   */
  public static Path getWarProjectDirectory() {
    try {
      /*
       * The working directory for tests will either be the module directory or their parent
       * directory. With that knowledge, we're searching for the project directory.
       */
      Path projectDir = Paths.get(".");
      if (!Files.isDirectory(projectDir) && projectDir.toRealPath().endsWith("bfd-server-war"))
        throw new IllegalStateException();
      return projectDir;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Gets the local {@link Path} that development/test key and trust stores can be found in.
   *
   * @return the ssl stores directory
   */
  static Path getSslStoresDirectory() {
    /*
     * The working directory for tests will either be the module directory
     * or their parent directory. With that knowledge, we're searching for
     * the ssl-stores directory.
     */
    Path sslStoresDir = Paths.get("..", "dev", "ssl-stores");
    if (!Files.isDirectory(sslStoresDir)) sslStoresDir = Paths.get("dev", "ssl-stores");
    if (!Files.isDirectory(sslStoresDir)) throw new IllegalStateException();
    return sslStoresDir;
  }

  /**
   * Gets the client trust store path.
   *
   * @return the local {@link Path} to the trust store that FHIR clients should use
   */
  private static Path getClientTrustStorePath() {
    Path trustStorePath = getSslStoresDirectory().resolve("client-truststore.pfx");
    return trustStorePath;
  }

  /**
   * Parses a list of sample sources.
   *
   * @param sampleResources the sample RIF resources to parse
   * @return the {@link List} of RIF records that were parsed (e.g. {@link Beneficiary}s, etc.)
   */
  public static List<Object> parseData(List<StaticRifResource> sampleResources) {
    RifFilesEvent rifFilesEvent =
        new RifFilesEvent(
            Instant.now(),
            false,
            sampleResources.stream().map(r -> r.toRifFile()).collect(Collectors.toList()));
    RifFilesProcessor processor = new RifFilesProcessor();
    List<Object> recordsParsed = new ArrayList<>();
    for (RifFileEvent rifFileEvent : rifFilesEvent.getFileEvents()) {
      RifFileRecords rifFileRecords = processor.produceRecords(rifFileEvent);
      rifFileRecords
          .getRecords()
          .map(r -> r.getRecord())
          .toIterable()
          .forEach(r -> recordsParsed.add(r));
    }

    return recordsParsed;
  }

  /**
   * Loads the sample A test data.
   *
   * @return the loaded records
   */
  public List<Object> loadSampleAData() {
    return loadSampleAData(false);
  }

  /**
   * Loads the sample A data to use in tests.
   *
   * @param addSamhsa if samhsa data should be added to the carrier test data
   * @return the loaded records
   */
  public List<Object> loadSampleAData(boolean addSamhsa) {

    List<Object> loadedRecords =
        ServerTestUtils.get()
            .loadData(Arrays.asList(StaticRifResourceGroup.SAMPLE_A.getResources()));
    if (addSamhsa) {
      addSamhsaToLoadedRecords(loadedRecords);
    }

    return loadedRecords;
  }

  /**
   * Load the SAMPLE_A resources and then tweak carrier claim types to have a SAMHSA diagnosis code.
   *
   * @param loadedRecords the loaded records
   */
  private void addSamhsaToLoadedRecords(List<Object> loadedRecords) {
    // Load the SAMPLE_A resources normally.
    EntityManager entityManager = null;

    try {
      EntityManagerFactory entityManagerFactory =
          PipelineTestUtils.get().getPipelineApplicationState().getEntityManagerFactory();
      entityManager = entityManagerFactory.createEntityManager();

      // Tweak the SAMPLE_A claims such that they are SAMHSA-related.
      adjustCarrierClaimForSamhsaDiagnosis(loadedRecords, entityManager);
      adjustHhaRecordForSamhsaDiagnosis(loadedRecords, entityManager);
      adjustDmeRecordForSamhsaDiagnosis(loadedRecords, entityManager);
      adjustInpatientRecordForSamhsaDiagnosis(loadedRecords, entityManager);
      adjustHospiceRecordForSamhsaDiagnosis(loadedRecords, entityManager);
      adjustOutpatientRecordForSamhsaDiagnosis(loadedRecords, entityManager);
      adjustSnfRecordForSamhsaDiagnosis(loadedRecords, entityManager);

    } finally {
      if (entityManager != null && entityManager.getTransaction().isActive())
        entityManager.getTransaction().rollback();
      if (entityManager != null) entityManager.close();
    }
  }

  /**
   * Adjusts the carrier claim to support samhsa.
   *
   * @param loadedRecords the loaded records
   * @param entityManager the entity manager
   */
  private void adjustCarrierClaimForSamhsaDiagnosis(
      List<Object> loadedRecords, EntityManager entityManager) {

    CarrierClaim carrierRifRecord =
        loadedRecords.stream()
            .filter(r -> r instanceof CarrierClaim)
            .map(CarrierClaim.class::cast)
            .findFirst()
            .get();

    entityManager.getTransaction().begin();
    carrierRifRecord = entityManager.find(CarrierClaim.class, carrierRifRecord.getClaimId());
    carrierRifRecord.setDiagnosis2Code(
        Optional.of(Stu3EobSamhsaMatcherTest.SAMPLE_SAMHSA_ICD_9_DIAGNOSIS_CODE));
    carrierRifRecord.setDiagnosis2CodeVersion(Optional.of('9'));
    entityManager.merge(carrierRifRecord);
    entityManager.getTransaction().commit();
  }

  /**
   * Adjusts the first inpatient record to support samhsa.
   *
   * @param loadedRecords the loaded records
   * @param entityManager the entity manager
   */
  private void adjustInpatientRecordForSamhsaDiagnosis(
      List<Object> loadedRecords, EntityManager entityManager) {

    InpatientClaim inpatientRifRecord =
        loadedRecords.stream()
            .filter(r -> r instanceof InpatientClaim)
            .map(InpatientClaim.class::cast)
            .findFirst()
            .get();

    entityManager.getTransaction().begin();
    inpatientRifRecord = entityManager.find(InpatientClaim.class, inpatientRifRecord.getClaimId());
    inpatientRifRecord.setDiagnosis2Code(
        Optional.of(Stu3EobSamhsaMatcherTest.SAMPLE_SAMHSA_ICD_9_DIAGNOSIS_CODE));
    inpatientRifRecord.setDiagnosis2CodeVersion(Optional.of('9'));
    entityManager.merge(inpatientRifRecord);
    entityManager.getTransaction().commit();
  }

  /**
   * Adjusts the first outpatient record to support samhsa.
   *
   * @param loadedRecords the loaded records
   * @param entityManager the entity manager
   */
  private void adjustOutpatientRecordForSamhsaDiagnosis(
      List<Object> loadedRecords, EntityManager entityManager) {

    OutpatientClaim outpatientRifRecord =
        loadedRecords.stream()
            .filter(r -> r instanceof OutpatientClaim)
            .map(OutpatientClaim.class::cast)
            .findFirst()
            .get();

    entityManager.getTransaction().begin();
    outpatientRifRecord =
        entityManager.find(OutpatientClaim.class, outpatientRifRecord.getClaimId());
    outpatientRifRecord.setDiagnosis2Code(
        Optional.of(Stu3EobSamhsaMatcherTest.SAMPLE_SAMHSA_ICD_9_DIAGNOSIS_CODE));
    outpatientRifRecord.setDiagnosis2CodeVersion(Optional.of('9'));
    entityManager.merge(outpatientRifRecord);
    entityManager.getTransaction().commit();
  }

  /**
   * Adjusts the first HHA record to support samhsa.
   *
   * @param loadedRecords the loaded records
   * @param entityManager the entity manager
   */
  private void adjustHhaRecordForSamhsaDiagnosis(
      List<Object> loadedRecords, EntityManager entityManager) {

    HHAClaim hhaRifRecord =
        loadedRecords.stream()
            .filter(r -> r instanceof HHAClaim)
            .map(HHAClaim.class::cast)
            .findFirst()
            .get();

    entityManager.getTransaction().begin();
    hhaRifRecord = entityManager.find(HHAClaim.class, hhaRifRecord.getClaimId());
    hhaRifRecord.setDiagnosis2Code(
        Optional.of(Stu3EobSamhsaMatcherTest.SAMPLE_SAMHSA_ICD_9_DIAGNOSIS_CODE));
    hhaRifRecord.setDiagnosis2CodeVersion(Optional.of('9'));
    entityManager.merge(hhaRifRecord);
    entityManager.getTransaction().commit();
  }

  /**
   * Adjusts the first SNF record to support samhsa.
   *
   * @param loadedRecords the loaded records
   * @param entityManager the entity manager
   */
  private void adjustSnfRecordForSamhsaDiagnosis(
      List<Object> loadedRecords, EntityManager entityManager) {

    SNFClaim snfRifRecord =
        loadedRecords.stream()
            .filter(r -> r instanceof SNFClaim)
            .map(SNFClaim.class::cast)
            .findFirst()
            .get();

    entityManager.getTransaction().begin();
    snfRifRecord = entityManager.find(SNFClaim.class, snfRifRecord.getClaimId());
    snfRifRecord.setDiagnosis2Code(
        Optional.of(Stu3EobSamhsaMatcherTest.SAMPLE_SAMHSA_ICD_9_DIAGNOSIS_CODE));
    snfRifRecord.setDiagnosis2CodeVersion(Optional.of('9'));
    entityManager.merge(snfRifRecord);
    entityManager.getTransaction().commit();
  }

  /**
   * Adjusts the first Hospice record to support samhsa.
   *
   * @param loadedRecords the loaded records
   * @param entityManager the entity manager
   */
  private void adjustHospiceRecordForSamhsaDiagnosis(
      List<Object> loadedRecords, EntityManager entityManager) {

    HospiceClaim hospiceRifRecord =
        loadedRecords.stream()
            .filter(r -> r instanceof HospiceClaim)
            .map(HospiceClaim.class::cast)
            .findFirst()
            .get();

    entityManager.getTransaction().begin();
    hospiceRifRecord = entityManager.find(HospiceClaim.class, hospiceRifRecord.getClaimId());
    hospiceRifRecord.setDiagnosis2Code(
        Optional.of(Stu3EobSamhsaMatcherTest.SAMPLE_SAMHSA_ICD_9_DIAGNOSIS_CODE));
    hospiceRifRecord.setDiagnosis2CodeVersion(Optional.of('9'));
    entityManager.merge(hospiceRifRecord);
    entityManager.getTransaction().commit();
  }

  /**
   * Adjusts the first DME record to support samhsa.
   *
   * @param loadedRecords the loaded records
   * @param entityManager the entity manager
   */
  private void adjustDmeRecordForSamhsaDiagnosis(
      List<Object> loadedRecords, EntityManager entityManager) {

    DMEClaim dmeRifRecord =
        loadedRecords.stream()
            .filter(r -> r instanceof DMEClaim)
            .map(DMEClaim.class::cast)
            .findFirst()
            .get();

    entityManager.getTransaction().begin();
    dmeRifRecord = entityManager.find(DMEClaim.class, dmeRifRecord.getClaimId());
    dmeRifRecord.setDiagnosis2Code(
        Optional.of(Stu3EobSamhsaMatcherTest.SAMPLE_SAMHSA_ICD_9_DIAGNOSIS_CODE));
    dmeRifRecord.setDiagnosis2CodeVersion(Optional.of('9'));
    entityManager.merge(dmeRifRecord);
    entityManager.getTransaction().commit();
  }

  /**
   * Gets the pagination link by name from the response. Throws an exception if the given link name
   * does not exist in the response.
   *
   * <p>Should be one of: next, previous, self, last, first
   *
   * @param response the response
   * @param paginationLinkName the pagination link name
   * @return the pagination link
   */
  public String getPaginationLink(Response response, String paginationLinkName) {

    List<Map<String, ?>> links = response.jsonPath().getList("link");
    return links.stream()
        .filter(m -> m.get("relation").equals(paginationLinkName))
        .findFirst()
        .orElseThrow()
        .get("url")
        .toString();
  }

  /**
   * Loads a list of sample resources.
   *
   * @param sampleResources the sample RIF resources to load
   * @return the {@link List} of RIF records that were loaded (e.g. {@link Beneficiary}s, etc.)
   */
  public List<Object> loadData(List<StaticRifResource> sampleResources) {
    LoadAppOptions loadOptions = CcwRifLoadTestUtils.getLoadOptions();
    RifFilesEvent rifFilesEvent =
        new RifFilesEvent(
            Instant.now(),
            false,
            sampleResources.stream().map(r -> r.toRifFile()).collect(Collectors.toList()));

    // Create the processors that will handle each stage of the pipeline.
    RifFilesProcessor processor = new RifFilesProcessor();

    // Link up the pipeline and run it.
    RifLoader loader =
        new RifLoader(loadOptions, PipelineTestUtils.get().getPipelineApplicationState());
    LOGGER.info("Loading RIF records...");
    List<Object> recordsLoaded = new ArrayList<>();
    for (RifFileEvent rifFileEvent : rifFilesEvent.getFileEvents()) {
      RifFileRecords rifFileRecords = processor.produceRecords(rifFileEvent);
      loader
          .processAsync(rifFileRecords, new AtomicBoolean())
          .doOnError(error -> LOGGER.warn("Record(s) failed to load.", error))
          .doOnNext(result -> recordsLoaded.add(result.getRifRecordEvent().getRecord()))
          .blockLast();
    }
    LOGGER.info("Loaded RIF records: '{}'.", recordsLoaded.size());
    return recordsLoaded;
  }

  /**
   * Gets the first beneficiary in the provided data.
   *
   * @param loadedRecords the loaded records
   * @return the first beneficiary
   */
  public Beneficiary getFirstBeneficiary(List<Object> loadedRecords) {
    return loadedRecords.stream()
        .filter(r -> r instanceof Beneficiary)
        .map(Beneficiary.class::cast)
        .findFirst()
        .get();
  }

  /**
   * Gets the patient id.
   *
   * @param loadedRecords the loaded records
   * @return the patient id from the first beneficiary
   */
  public String getPatientId(List<Object> loadedRecords) {
    return CommonTransformerUtils.buildPatientId(getFirstBeneficiary(loadedRecords)).getIdPart();
  }

  /**
   * A wrapper for the entity manager logic and action. The executor is called within a transaction
   *
   * @param executor to call with an entity manager.
   */
  public void doTransaction(Consumer<EntityManager> executor) {
    EntityManager em = null;
    EntityTransaction tx = null;
    try {
      em =
          PipelineTestUtils.get()
              .getPipelineApplicationState()
              .getEntityManagerFactory()
              .createEntityManager();
      tx = em.getTransaction();
      tx.begin();
      executor.accept(em);
      tx.commit();
    } finally {
      if (tx != null && tx.isActive()) {
        tx.rollback();
        LOGGER.info("Rolling back a transaction");
      }
      if (em != null && em.isOpen()) em.close();
    }
  }

  /**
   * Starts a background thread that periodically generates a heap dump at <code>./heap-dumps/
   * </code>. Useful when trying to debug memory pressure issues in our test code.
   *
   * @param period how often to generate a heap dump
   */
  @SuppressWarnings("restriction")
  public static void startHeapDumpCollector(Duration period) {
    Runnable collector =
        () -> {
          MBeanServer server = ManagementFactory.getPlatformMBeanServer();
          try {
            Path heapDumpsDir = Paths.get(".", "heap-dumps");
            Files.createDirectories(heapDumpsDir);
            String heapDumpFileName =
                String.format(
                        "bfd-server-tests-%s.hprof",
                        DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                    .replaceAll(":", "-");
            Path heapDump = heapDumpsDir.resolve(heapDumpFileName);
            LOGGER.info("Generating heap dump at: {}", heapDump.toAbsolutePath().toString());

            com.sun.management.HotSpotDiagnosticMXBean mxBean =
                java.lang.management.ManagementFactory.newPlatformMXBeanProxy(
                    server,
                    "com.sun.management:type=HotSpotDiagnostic",
                    com.sun.management.HotSpotDiagnosticMXBean.class);
            mxBean.dumpHeap(heapDump.toString(), true);
            LOGGER.info("Generated heap dump at: {}", heapDump.toAbsolutePath().toString());
          } catch (IOException e) {
            LOGGER.warn("Unable to generate heap dump.", e);
            throw new UncheckedIOException(e);
          }
        };
    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    executor.scheduleAtFixedRate(
        collector, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * Runs a <code>TRUNCATE</code> for all tables in the supplied database. Obviously, only use this
   * on test/local databases.
   *
   * @param dataSource the data source
   */
  public void truncateNonRdaTablesInDataSource(DataSource dataSource) {
    List<Class<?>> entityTypes =
        Arrays.asList(
            PartDEvent.class,
            SNFClaimLine.class,
            SNFClaim.class,
            OutpatientClaimLine.class,
            OutpatientClaim.class,
            InpatientClaimLine.class,
            InpatientClaim.class,
            HospiceClaimLine.class,
            HospiceClaim.class,
            HHAClaimLine.class,
            HHAClaim.class,
            DMEClaimLine.class,
            DMEClaim.class,
            CarrierClaimLine.class,
            CarrierClaim.class,
            BeneficiaryHistory.class,
            BeneficiaryMonthly.class,
            Beneficiary.class,
            LoadedBatch.class,
            LoadedFile.class,
            SkippedRifRecord.class);

    try (Connection connection = dataSource.getConnection(); ) {
      // Disable auto-commit and remember the default schema name.
      connection.setAutoCommit(false);
      Optional<String> defaultSchemaName = Optional.ofNullable(connection.getSchema());
      if (defaultSchemaName.isEmpty()) {
        throw new BadCodeMonkeyException("Unable to determine default schema name.");
      }

      // Loop over every @Entity type.
      for (Class<?> entityType : entityTypes) {
        Optional<Table> entityTableAnnotation =
            Optional.ofNullable(entityType.getAnnotation(Table.class));

        // First, make sure we found an @Table annotation.
        if (entityTableAnnotation.isEmpty()) {
          throw new BadCodeMonkeyException(
              "Unable to determine table metadata for entity: " + entityType.getCanonicalName());
        }

        // Then, make sure we have a table name specified.
        if (entityTableAnnotation.get().name() == null
            || entityTableAnnotation.get().name().isEmpty()) {
          throw new BadCodeMonkeyException(
              "Unable to determine table name for entity: " + entityType.getCanonicalName());
        }
        String tableNameSpecifier = normalizeTableName(entityTableAnnotation.get().name());

        // Then, switch to the appropriate schema.
        if (entityTableAnnotation.get().schema() != null
            && !entityTableAnnotation.get().schema().isEmpty()) {
          String schemaNameSpecifier =
              normalizeSchemaName(connection, entityTableAnnotation.get().schema());
          connection.setSchema(schemaNameSpecifier);
        } else {
          connection.setSchema(defaultSchemaName.get());
        }

        /*
         * Finally, run the TRUNCATE. On Postgres the cascade option is required due to the
         * presence of FK constraints.
         */
        String truncateTableSql = String.format("TRUNCATE TABLE %s", tableNameSpecifier);
        if (DatabaseUtils.isPostgresConnection(connection)) {
          truncateTableSql = truncateTableSql + " CASCADE";
        }
        connection.createStatement().execute(truncateTableSql);

        connection.setSchema(defaultSchemaName.get());
      }

      connection.commit();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Normalizes the schema names to work with both HSQL and postgres by preserving case and removing
   * quotes.
   *
   * @param connection the connection
   * @param schemaNameSpecifier name of a schema from a hibernate annotation
   * @return value compatible with call to {@link Connection#setSchema(String)}
   * @throws SQLException the sql exception
   */
  private String normalizeSchemaName(Connection connection, String schemaNameSpecifier)
      throws SQLException {
    String sql = schemaNameSpecifier.replaceAll("`", "");
    if (DatabaseUtils.isHsqlConnection((connection))) {
      sql = sql.toUpperCase();
    }
    return sql;
  }

  /**
   * Normalizes the table names to work with both HSQL and Hibernate by upper-casing table names
   * without quotes and preserving case for other table names.
   *
   * @param tableNameSpecifier name of a table from a hibernate annotation
   * @return value compatible with call to {@link java.sql.Statement#execute(String)}
   */
  private String normalizeTableName(String tableNameSpecifier) {
    if (tableNameSpecifier.startsWith("`")) {
      tableNameSpecifier = tableNameSpecifier.replaceAll("`", "");
    } else {
      tableNameSpecifier = tableNameSpecifier.toUpperCase();
    }
    return tableNameSpecifier;
  }

  /**
   * Validates if the test's db url is valid for testing; i.e. is this a local database we can
   * properly purge between tests. This is primarily to avoid calling truncate on a database you
   * shouldn't and ruining your entire career.
   *
   * @param dbUrl the db url to validate
   * @return if the db url is a local database that can be safely truncated and is not empty
   */
  public static boolean isValidServerDatabase(String dbUrl) {
    boolean isTestContainer = dbUrl.endsWith("tc");
    boolean isLocalDb = dbUrl.contains("localhost") || dbUrl.contains("127.0.0.1");
    boolean isHsqlDb = dbUrl.contains("hsql");
    return !dbUrl.isBlank() && (isTestContainer || isLocalDb || isHsqlDb);
  }

  /**
   * Generates a path to use to get or store endpoint json files.
   *
   * @param directory the path to where the file should be written
   * @param endpoint the string to identify which endpoint's response the file contents contain
   * @return a path to use as a filename
   */
  public static Path generatePathForEndpointJsonFile(Path directory, String endpoint) {
    return Paths.get(directory.toString(), endpoint + ".json");
  }

  /**
   * Reads a file at the specified location.
   *
   * @param path the path to the file
   * @return the contents of the file as a string.
   */
  public static String readFile(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Can't read file at " + path, e);
    }
  }

  /**
   * Gets the claim id for the specified record type in the loaded records.
   *
   * @param loadedRecords the loaded records
   * @param claimType the claim type
   * @return the claim id for
   */
  public static String getClaimIdFor(List<Object> loadedRecords, ClaimType claimType) {
    return switch (claimType) {
      case CARRIER:
        CarrierClaim carrier = getClaim(loadedRecords, CarrierClaim.class);
        yield String.valueOf(carrier.getClaimId());
      case DME:
        DMEClaim dme = getClaim(loadedRecords, DMEClaim.class);
        yield String.valueOf(dme.getClaimId());
      case HHA:
        HHAClaim hha = getClaim(loadedRecords, HHAClaim.class);
        yield String.valueOf(hha.getClaimId());
      case HOSPICE:
        HospiceClaim hospiceClaim = getClaim(loadedRecords, HospiceClaim.class);
        yield String.valueOf(hospiceClaim.getClaimId());
      case INPATIENT:
        InpatientClaim inpatientClaim = getClaim(loadedRecords, InpatientClaim.class);
        yield String.valueOf(inpatientClaim.getClaimId());
      case OUTPATIENT:
        OutpatientClaim outpatientClaim = getClaim(loadedRecords, OutpatientClaim.class);
        yield String.valueOf(outpatientClaim.getClaimId());
      case PDE:
        PartDEvent pde = getClaim(loadedRecords, PartDEvent.class);
        yield String.valueOf(pde.getEventId());
      case SNF:
        SNFClaim snfClaim = getClaim(loadedRecords, SNFClaim.class);
        yield String.valueOf(snfClaim.getClaimId());
    };
  }

  /**
   * Instantiates a new Get claim.
   *
   * @param loadedRecords the loaded records
   * @param clazz the rif record type
   * @param <T> the rif record type (must match clazz)
   * @return the claim of the given type from the sample data
   */
  public static <T extends RifRecordBase> T getClaim(List<Object> loadedRecords, Class<T> clazz) {

    return loadedRecords.stream()
        .filter(clazz::isInstance)
        .map(clazz::cast)
        .findFirst()
        .orElseThrow();
  }

  /**
   * Writes a file to the specified path.
   *
   * @param contents the string to be written to a file
   * @param filePath the path+name to save the file as
   */
  public static void writeFile(String contents, Path filePath) {
    try {
      Files.writeString(filePath, contents, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not write file at " + filePath, e);
    }
  }
}
