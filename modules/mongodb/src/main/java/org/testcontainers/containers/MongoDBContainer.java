package org.testcontainers.containers;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.core5.net.URIBuilder;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.RandomUtils;
import org.rnorth.ducttape.TimeoutException;
import org.rnorth.ducttape.ratelimits.RateLimiter;
import org.rnorth.ducttape.ratelimits.RateLimiterBuilder;
import org.rnorth.ducttape.unreliables.Unreliables;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkState;
import static org.apache.commons.lang3.ArrayUtils.isEmpty;
import static org.apache.commons.lang3.ArrayUtils.isNotEmpty;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Testcontainers implementation for MongoDB.
 * <p>
 * Supported images: {@code mongo}, {@code mongodb/mongodb-community-server}, {@code mongodb/mongodb-enterprise-server}
 * <p>
 * Exposed ports: 27017
 *
 * @deprecated use {@link org.testcontainers.mongodb.MongoDBContainer} instead.
 */
@Slf4j
@Deprecated
public class MongoDBContainer extends GenericContainer<MongoDBContainer> {

    private static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("mongo");

    private static final DockerImageName COMMUNITY_SERVER_IMAGE = DockerImageName.parse(
        "mongodb/mongodb-community-server"
    );

    private static final DockerImageName ENTERPRISE_SERVER_IMAGE = DockerImageName.parse(
        "mongodb/mongodb-enterprise-server"
    );

    private static final String DEFAULT_TAG = "4.0.28";

    protected static final String INITDB_USERNAME = "MONGO_INITDB_ROOT_USERNAME";

    protected static final String INITDB_PASSWORD = "MONGO_INITDB_ROOT_PASSWORD";

    protected static final String INITDB_DATABASE = "MONGO_INITDB_DATABASE";

    protected static final String DEFAULT_KEY_FILE_PATH = "/run/secrets/key-file.key";

    private static final int DEFAULT_KEY_SIZE = 756;

    public static final String DEFAULT_DATABASE = "test";

    public static final String DEFAULT_AUTHENTICATION_DATABASE = "admin";

    private static final String STARTER_SCRIPT = "/testcontainers_start.sh";

    private static final int INIT_REPLICA_SET_TIMEOUT = 20;

    private static final RateLimiter INIT_REPLICA_SET_LIMITER = RateLimiterBuilder
        .newBuilder()
        .withRate(500, TimeUnit.MILLISECONDS)
        .withConstantThroughput()
        .build();

    private static final String CHECK_REPLICA_SET_READY =
        "var status = db.adminCommand({ replSetGetStatus: 1 });" +
        "printjson(status);" +
        "if (status.myState != 1) quit(900);" +
        "var hello = db.runCommand({ hello: 1 });" +
        "printjson(hello);" +
        "if (!hello.isWritablePrimary) quit(900);";

    private static final int DEFAULT_PORT = 27017;

    private boolean shardingEnabled = false;

    private byte[] keyFileContent = new byte[0];

    private String keyFilePath = DEFAULT_KEY_FILE_PATH;

    private String replicaSet = "docker-rs";

    private String shellExecutable;

    @Getter
    private String authenticationDatabase = DEFAULT_AUTHENTICATION_DATABASE;

    /**
     * @deprecated use {@link #MongoDBContainer(DockerImageName)} instead
     */
    @Deprecated
    public MongoDBContainer() {
        this(DEFAULT_IMAGE_NAME.withTag(DEFAULT_TAG));
    }

    public MongoDBContainer(@NonNull final String dockerImageName) {
        this(DockerImageName.parse(dockerImageName));
    }

    public MongoDBContainer(@NonNull final DockerImageName dockerImageName) {
        super(dockerImageName);
        dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME, COMMUNITY_SERVER_IMAGE, ENTERPRISE_SERVER_IMAGE);
        waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 1));
        withExposedPorts(DEFAULT_PORT);
        withDatabase(DEFAULT_DATABASE);
    }

    /**
     * Changes the replica-set name.
     *
     * <p>Replica-set is enabled by default since various MongoDB features
     * depends on it. The default replica-set name is "docker-rs". This method
     * only allows to change it.</p>
     *
     * @param name the replica-set name for the cluster
     * @return this
     */
    public MongoDBContainer withReplicaSet(@NonNull final String name) {
        this.replicaSet = name;
        return this;
    }

    private static void retryUntil(Callable<Boolean> runnable) {
        Unreliables.retryUntilTrue(
            INIT_REPLICA_SET_TIMEOUT,
            TimeUnit.SECONDS,
            () -> INIT_REPLICA_SET_LIMITER.getWhenReady(runnable)
        );
    }

    private boolean isReplicaSetReady() throws IOException, InterruptedException {
        return isRunning() && execInMongo(CHECK_REPLICA_SET_READY).getExitCode() == 0;
    }

    private void initReplicaSet(boolean reused) throws IOException, InterruptedException {
        if (reused && isReplicaSetReady()) {
            log.debug("Replica set already initiated");
        } else {
            try {
                retryUntil(() -> execInMongo("rs.initiate();").getExitCode() == 0);
                retryUntil(() -> isReplicaSetReady());
                log.debug("Replica set initiated");
            } catch (TimeoutException e) {
                throw new ContainerLaunchException("Error initiating replica set", e);
            }
        }
    }

    /**
     * Sets the key file of the cluster.
     *
     * <p>Stores a given key in the provided path and sets the '--keyFile'
     * option accordingly.</p>
     *
     * <p>The key file is a unique Base64-encoded file that enables nodes in a
     * replica set to authenticate and verify each other’s identity. It is
     * required when authentication is enabled. See {@link #withCredentials}
     * for more information</p>
     *
     * <p>Use this method if you need a specific key file, otherwise, consider
     * using {@link #withRandomKeyFileContent()}.</p>
     *
     * @param keyFileContent the key file content
     * @param keyFilePath path where the key file will be stored. Default is
     * {@code DEFAULT_KEY_FILE_PATH}
     * @return this
     */
    public MongoDBContainer withKeyFileContent(final byte[] keyFileContent, @NonNull final String keyFilePath) {
        this.keyFileContent = keyFileContent;
        this.keyFilePath = keyFilePath;
        return self();
    }

    /**
     * @see #withKeyFileContent(byte[], String)}
     */
    public MongoDBContainer withKeyFileContent(final byte[] key) {
        return withKeyFileContent(key, DEFAULT_KEY_FILE_PATH);
    }

    /**
     * Generates and sets a random key file of the cluster.
     *
     * <p>Generates a random key with the specified size and stores it in
     * the path indicated. See {@link #withKeyFileContent(byte[], String)} for
     * more information.
     *
     * @param size number of bytes to generate. Default is
     * {@code DEFAULT_KEY_SIZE}
     * @param keyFilePath path where the key file will be stored. Default is
     * {@code DEFAULT_KEY_FILE_PATH}
     * @return this
     */
    public MongoDBContainer withRandomKeyFileContent(final int size, @NonNull final String keyFilePath) {
        return withKeyFileContent(RandomUtils.nextBytes(size), keyFilePath);
    }

    /**
     * @see #withRandomKeyFileContent(int, String)
     */
    public MongoDBContainer withRandomKeyFileContent(final int size) {
        return withRandomKeyFileContent(size, DEFAULT_KEY_FILE_PATH);
    }

    /**
     * @see #withRandomKeyFileContent(int, String)
     */
    public MongoDBContainer withRandomKeyFileContent(@NonNull final String keyFilePath) {
        return withRandomKeyFileContent(DEFAULT_KEY_SIZE, keyFilePath);
    }

    /**
     * @see #withRandomKeyFileContent(int, String)
     */
    public MongoDBContainer withRandomKeyFileContent() {
        return withRandomKeyFileContent(DEFAULT_KEY_SIZE, DEFAULT_KEY_FILE_PATH);
    }

    /**
     * Enables sharding on the cluster
     *
     * @return this
     */
    public MongoDBContainer withSharding() {
        this.shardingEnabled = true;
        return this;
    }

    private boolean isSharding() {
        return shardingEnabled;
    }

    /**
     * Enables password-based authentication and sets the root credentials.
     *
     * <p>Sets the environment variables {@code MONGO_INITDB_ROOT_USERNAME} and
     * {@code MONGO_INITDB_ROOT_PASSWORD}, which create a user with the root
     * role in the {@code admin} database. For more information see the
     * "Environment Variables" section of
     * <a href="https://hub.docker.com/_/mongo">https://hub.docker.com/_/mongo"
     * </a></p>
     *
     * <p><strong>Note:</strong> When using authentication and replica-set
     * you must also configure a key file using
     * {@link #withKeyFileContent(byte[], String)} or {@link #withRandomKeyFileContent()}.
     * </p>
     *
     * @param username the root username to be created.
     * @param password the root user's password.
     * @return this.
     */
    public MongoDBContainer withCredentials(@NonNull final String username, @NonNull final String password) {
        return withUsername(username).withPassword(password);
    }

    private boolean hasCredentials() {
        return getUsername() != null && getPassword() != null;
    }

    private MongoDBContainer withUsername(@NonNull final String username) {
        return withEnv(INITDB_USERNAME, username);
    }

    private String getUsername() {
        return getEnvMap().get(INITDB_USERNAME);
    }

    private MongoDBContainer withPassword(@NonNull final String password) {
        return withEnv(INITDB_PASSWORD, password);
    }

    private String getPassword() {
        return getEnvMap().get(INITDB_PASSWORD);
    }

    public MongoDBContainer withAuthenticationDatabase(String authenticationDatabase) {
        this.authenticationDatabase = authenticationDatabase;
        return this;
    }

    /**
     * Sets a default database to be created on container initialization.
     *
     * <p>Uses the {@code MONGO_INITDB_DATABASE} environment variable to
     * initialize an empty database at startup. For more information see the
     * "Environment Variables" section of
     * <a href="https://hub.docker.com/_/mongo">https://hub.docker.com/_/mongo"</a>
     * </p>
     *
     * @param database the database name that will be created
     * @return this
     */
    public MongoDBContainer withDatabase(@NonNull final String database) {
        return withEnv(INITDB_DATABASE, database);
    }

    private String getDatabase() {
        return getEnvMap().get(INITDB_DATABASE);
    }

    protected String getShellExecutable() throws IOException, InterruptedException {
        if(isBlank(shellExecutable)) {
            if(execInContainer("mongosh", "--version").getExitCode() == 0) {
                shellExecutable = "mongosh";
            } else if (execInContainer("mongo", "--version").getExitCode() == 0) {
                shellExecutable = "mongo";
            } else {
                throw new UnsupportedOperationException(
                    "A MongoDB Shell was not found. " +
                    "Please use a supported image."
                );
            }
        }
        return shellExecutable;
    }

    public ExecResult execInMongo(@NonNull final String script) throws IOException, InterruptedException {
        checkState(isRunning(), "execInMongo can only be used while the container is running");
        val command = new ArrayList<String>();
        command.add(getShellExecutable());
        command.add("--quiet");
        if(hasCredentials()) {
            command.add(String.format("--username=%s", getUsername()));
            command.add(String.format("--password=%s", getPassword()));
        }
        command.add(String.format("--eval=%s", script));
        return execInContainer(command.stream().toArray(String[]::new));
    }

    protected URIBuilder getConnectionStringBuilder() {
        val builder = new URIBuilder()
            .setScheme("mongodb");
        if(isRunning()) {
            builder
                .setHost(getHost())
                .setPort(getMappedPort(DEFAULT_PORT));
        }
        if(isNotBlank(getDatabase())) {
            builder.setPath(getDatabase());
        }
        if(isNotBlank(replicaSet) && !shardingEnabled) {
            builder
                .addParameter("replicaSet", replicaSet)
                .addParameter("directConnection", "true");
        }
        if(isNotBlank(getUsername()) && isNotBlank(getPassword())) {
            builder
                .setUserInfo(getUsername(), getPassword())
                .addParameter("authSource", getAuthenticationDatabase());
        }
        return builder;
    }

    /**
     * Gets a connection string url.
     *
     * @return a connection url pointing to a mongodb instance
     */
    public String getConnectionString() {
        checkState(isRunning(), "Connection string can only be built with the container running");
        return getConnectionStringBuilder().toString();
    }

    /**
     * Gets a replica set url for the default {@value #DEFAULT_DATABASE} database.
     *
     * @return a replica set url.
     */
    public String getReplicaSetUrl() {
        return getReplicaSetUrl(getDatabase());
    }

    /**
     * Gets a replica set url for a provided {@code database}.
     *
     * @param database a database name.
     * @return a replica set url.
     */
    public String getReplicaSetUrl(@NonNull final String database) {
        checkState(isRunning(), "Replica set URL can only be built with the container running");
        return getConnectionStringBuilder().setPath(database).toString();
    }

    @Override
    protected void configure() {
        val parameters = new ArrayList<String>();
        if (log.isWarnEnabled()) {
            val hasUsername = isNotBlank(getUsername());
            val hasPassword = isNotBlank(getPassword());
            if(hasUsername != hasPassword) {
                log.warn(
                    "Authentication requires both a username and a password. " +
                    "Only {} was defined.", hasUsername ? "username" : "password");
            }
            if(
                hasUsername &&
                hasPassword &&
                isNotBlank(replicaSet) &&
                isEmpty(keyFileContent)
            ) {
                log.warn(
                    "Replica set with authentication requires a key file. " +
                    "Use the 'withKeyFileContent()' or " +
                    "'withRandomKeyFileContent() to provide one."
                );
            }
        }
        if(isNotBlank(replicaSet)) {
            parameters.add(String.format("--replSet=%s", replicaSet));
        }
        if(isNotEmpty(keyFileContent)) {
            withCopyToContainer(Transferable.of(keyFileContent, 0400, 999, 999), keyFilePath);
            parameters.add(String.format("--keyFile=%s", keyFilePath));
        }
        if(isSharding()) {
            withCopyFileToContainer(MountableFile.forClasspathResource("/sharding.sh", 0777), STARTER_SCRIPT);
            setWaitStrategy(Wait.forLogMessage("(?i).*mongos ready.*", 1));
            getContainerDef().setEntrypoint("sh");
            val parameterString = String.join(" ", parameters);
            parameters.clear();
            parameters.add("-c");
            parameters.add(String.format("while [ ! -f %1$s ]; do sleep 0.1; done; %1$s %2$s", STARTER_SCRIPT, parameterString));
        }
        withCommand(parameters.stream().toArray(String[]::new));
    }


    @SneakyThrows
    @Override
    protected void containerIsStarted(InspectContainerResponse containerInfo, boolean reused) {
        if (isNotBlank(replicaSet) && !isSharding()) {
            initReplicaSet(reused);
        }
    }
}
