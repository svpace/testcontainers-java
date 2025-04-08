package org.testcontainers.containers;

import com.github.dockerjava.api.command.InspectContainerResponse;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.RandomUtils;
import org.rnorth.ducttape.ratelimits.RateLimiter;
import org.rnorth.ducttape.ratelimits.RateLimiterBuilder;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.apache.commons.lang3.ArrayUtils.isEmpty;
import static org.apache.commons.lang3.ArrayUtils.isNotEmpty;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.rnorth.ducttape.unreliables.Unreliables.retryUntilTrue;

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

    private static final String DEFAULT_TAG = "4.0.10";

    private static final String INITDB_USERNAME = "MONGO_INITDB_ROOT_USERNAME";

    private static final String INITDB_PASSWORD = "MONGO_INITDB_ROOT_PASSWORD";

    private static final String INITDB_DATABASE = "MONGO_INITDB_DATABASE";

    private static final String DEFAULT_KEY_FILE_PATH = "/run/secrets/key-file.key";

    private static final int DEFAULT_KEY_SIZE = 756;

    public static final String DEFAULT_DATABASE = "test";

    public static final String DEFAULT_AUTHENTICATION_DATABASE = "admin";

    private static final String STARTER_SCRIPT = "/testcontainers_start.sh";

    private static final int INIT_REPLICA_SET_ATTEMPTS = 60;
    private static final RateLimiter INIT_REPLICASET_LIMITER = RateLimiterBuilder.newBuilder()
        .withRate(500, TimeUnit.MILLISECONDS)
        .withConstantThroughput()
        .build();

    private static final String INIT_REPLICA_SET_WAIT_COMMAND = String.format(
        String.join(
            "\n",
            "var attempt = 0;",
            "while (db.runCommand( { isMaster: 1 } ).ismaster==false) {",
            "   if (attempt > %d) {",
            "       quit(1);",
            "   } else {",
            "       print('An attempt to await for a single node replica set initialization: ' + attempt);",
            "       sleep(100);",
            "       attempt++;",
            "   }",
            "}"
        ),
        INIT_REPLICA_SET_ATTEMPTS
    );

    private static final String EVAL_COMMAND = String.format(
        "mongosh %1$s || mongo %1$s",
        String.join(" ",
            "${MONGO_INITDB_ROOT_USERNAME:+--username \"$MONGO_INITDB_ROOT_USERNAME\"}" +
            "${MONGO_INITDB_ROOT_PASSWORD:+--password \"$MONGO_INITDB_ROOT_PASSWORD\"}" +
            "--eval \"%1$s\""
        )
    );

    private static final int DEFAULT_PORT = 27017;

    private boolean shardingEnabled;

    private byte[] keyFileContent = new byte[0];

    private String keyFilePath = DEFAULT_KEY_FILE_PATH;

    private String replicaSet = "";

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

    public MongoDBContainer(final DockerImageName dockerImageName) {
        super(dockerImageName);
        dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME, COMMUNITY_SERVER_IMAGE, ENTERPRISE_SERVER_IMAGE);

        withExposedPorts(DEFAULT_PORT);
        waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 1));
        withReplicaSet("docker-rs");
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
    public MongoDBContainer withCredentials(final String username, final String password) {
        return withEnv(INITDB_USERNAME, username).withEnv(INITDB_PASSWORD, password);
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
    public MongoDBContainer withDatabase(final String database) {
        return withEnv(INITDB_DATABASE, database);
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
    public MongoDBContainer withKeyFileContent(final byte[] keyFileContent, final String keyFilePath) {
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
    public MongoDBContainer withRandomKeyFileContent(final int size, final String keyFilePath) {
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
    public MongoDBContainer withRandomKeyFileContent(final String keyFilePath) {
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
    /**
     * Overrides the generated startup command for the container.
     *
     * <p><strong>Use with caution.</strong> Overriding the default command may
     * disable or break features like authentication, replica sets or shardind.
     * </p>
     *
     * @param commandParts the command and arguments to use as a replacement
     * @return this
     */
    public MongoDBContainer withCommandOverride(String... commandParts) {
        return super.withCommand(commandParts);
    }

    /**
     * @see #withCommand(String...)
     */
    public MongoDBContainer withCommandOverride(String command) {
        return super.withCommand(command);
    }

    /**
     * <strong>Unsupported operation.</strong> This method is disabled to
     * prevent unintended overrides of internal startup logic.
     *
     * <p>{@code MongoDBContainer} manages its own startup command based on
     * configuration such as replica set, authentication and sharding. Using
     * {@code withCommand(String...)} would bypass this logic, leading to
     * unexpected behavior or a non-functional container.</p>
     *
     * <p>If you really wish to customize the container's command, use
     * {@link #withCommandOverride(String[])} instead. This allows you
     * to override the full command explicitly.</p>
     *
     * @param commandParts ignored
     * @throws UnsupportedOperationException always thrown to indicate this
     * method must not be used.
     */
    @Override
    public void setCommand(@NonNull String... commandParts) {
        throw new UnsupportedOperationException(String.join("\n",
            "This method is disabled to prevent unintended overrides of",
            "internal startup logic. If you really wish to customize the",
            "container's command, use withCommandOverride() instead. Also,",
            "consider generatedCommandParts()to have access to the generated",
            "parameters and incorporate then into your custom command."));
    }

    @SneakyThrows
    @Override
    protected void containerIsStarted(InspectContainerResponse containerInfo, boolean reused) {
        if (isNotBlank(replicaSet) && !isSharding()) {
            initReplicaSet(reused);
        }
    }

    private <T> T requiresRunning(Supplier<T> supplier) {
        if (!isRunning()) {
            throw new IllegalStateException("MongoDBContainer should be started first");
        } else {
            return supplier.get();
        }
    }

    /**
     * Gets a connection string url.
     *
     * @return a connection url pointing to a mongodb instance
     */
    public String getConnectionString() {
        return requiresRunning(() -> {
            val builder = new StringBuilder("mongodb://");
            if(hasCredentials()) {
                val username = getEnvMap().get(INITDB_USERNAME);
                val password = getEnvMap().get(INITDB_PASSWORD);
                builder.append(String.format("%s:%s@", username, password));
            }
            builder.append(String.format("%s:%s", getHost(), getMappedPort(DEFAULT_PORT)));
            if(hasDatabase()) {
                val database = getEnvMap().get(INITDB_DATABASE);
                builder.append(String.format("/%s", database));
            }
            return builder.toString();
        });
    }

    /**
     * Gets a replica set url for the default {@value #DEFAULT_DATABASE} database.
     *
     * @return a replica set url.
     */
    public String getReplicaSetUrl() {
        return getReplicaSetUrl(DEFAULT_DATABASE);
    }

    /**
     * Gets a replica set url for a provided <code>databaseName</code>.
     *
     * @param databaseName a database name.
     * @return a replica set url.
     */
    public String getReplicaSetUrl(final String databaseName) {
        return requiresRunning(() -> getConnectionString() + "/" + databaseName);
    }

    private ExecResult execInMongo(final String command) throws IOException, InterruptedException {
        val script = String.format(EVAL_COMMAND, command);
        return execInContainer("sh", "-c", script);
    }

    private boolean isReplicaSet() {
        return isNotBlank(replicaSet);
    }

    private boolean isReplicaSetInitialized() throws IOException, InterruptedException {
        return isRunning()
            && execInMongo("if (db.adminCommand({ replSetGetStatus: 1 }).myState != 1) { quit(900); }")
            .getExitCode() == 0;
    }

    private boolean isSharding() {
        return shardingEnabled;
    }

    private boolean hasCommandOverride() {
        return isNotEmpty(getCommandParts());
    }

    private boolean hasDatabase() {
        return getEnvMap().containsKey(INITDB_DATABASE);
    }

    private boolean hasCredentials() {
        return getEnvMap().containsKey(INITDB_USERNAME)
            && getEnvMap().containsKey(INITDB_PASSWORD);
    }

    private boolean hasKeyFileContent() {
        return isNotEmpty(keyFileContent);
    }

    @Override
    protected void configure() {
        val parameters = new ArrayList<String>();
        if(isReplicaSet() && hasCredentials() && !hasKeyFileContent()) {
            log.warn(
                "Replica-set with authentication requires a key file to be" +
                "present. Use the 'withKeyFileContent()' or" +
                "'withRandomKeyFileContent() to provide one."
            );
        }
        if(isReplicaSet()) {
            parameters.add(String.format("--replSet=%s", replicaSet));
        }
        if(hasKeyFileContent()) {
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
        if(!hasCommandOverride()) {
            setCommandParts(parameters.stream().toArray(String[]::new));
        }
    }

    private void initReplicaSet(boolean reused){
        retryUntilTrue(
            INIT_REPLICA_SET_ATTEMPTS,
            () -> INIT_REPLICASET_LIMITER.getWhenReady(this::tryInitReplicaSet)
        );
    }

    private boolean tryInitReplicaSet() throws IOException, InterruptedException {
        if (isReplicaSetInitialized()) {
            log.debug("Replica set already initialized.");
            return true;
        } else {
            val result = execInMongo("rs.initiate();");
            if (result.getExitCode() == 0) {
                log.debug(String.format("Replica set initiated:\n%s", result.getStdout()));
            } else {
                val errorMessage = String.format("An error occurred while initializing the replica set:\n%s", result.getStdout());
                log.error(errorMessage);
            }
            return false;
        }
    }
}
