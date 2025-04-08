package org.testcontainers.containers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.containers.MongoDBContainer.INITDB_DATABASE;
import static org.testcontainers.containers.MongoDBContainer.INITDB_PASSWORD;
import static org.testcontainers.containers.MongoDBContainer.INITDB_USERNAME;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConfigureTest extends AbstractMongoTest {

    @Test
    void shouldHaveCommandWhenReplicaSetUsed() {
        container
            .withReplicaSet("test-rs")
            .configure();
        assertThat(container.getCommandParts()).containsExactly(
            "--replSet=test-rs"
        );
    }

    @Test
    void shouldBeValidWhenCredentialsAndKeyfileAndDatabaseUsed() {
        container
            .withReplicaSet("test-rs")
            .withCredentials("test-user", "test-p@ss")
            .withRandomKeyFileContent("test-keyfile")
            .withDatabase("test-db")
            .configure();
        assertThat(container.getEnvMap())
            .containsEntry(INITDB_USERNAME, "test-user")
            .containsEntry(INITDB_PASSWORD, "test-p@ss")
            .containsEntry(INITDB_DATABASE, "test-db");
        assertThat(container.getCommandParts()).containsExactly(
            "--replSet=test-rs",
            "--keyFile=test-keyfile"
        );
        assertThat(container.getCopyToTransferableContainerPathMap())
            .containsValue("test-keyfile");
        assertThat(container.getConnectionStringBuilder().setHost("localhost").toString())
            .isEqualTo("mongodb://test-user:test-p%40ss@localhost/test-db?replicaSet=test-rs&directConnection=true&authSource=admin");
    }

    @Test
    void shouldHaveEnvWhenDatabaseUsed() {
        container.withDatabase("test-db");
        assertThat(container.getEnvMap())
            .containsEntry(INITDB_DATABASE, "test-db");
    }

    @Test
    void shouldWarnWhenUsernamePresentAndPasswordMissing() {
        container
            .withReplicaSet("test-rs")
            .withEnv(INITDB_USERNAME, "test-user")
            .configure();
        assertThat(getLogs()).satisfiesOnlyOnce(it -> assertThat(it)
            .contains("[WARN]", "Only username was defined")
        );
    }

    @Test
    void shouldWarnWhenUsernameMissingAndPasswordPresent() {
        container
            .withReplicaSet("test-rs")
            .withEnv(INITDB_PASSWORD, "test-password")
            .configure();
        assertThat(getLogs()).satisfiesOnlyOnce(it -> assertThat(it)
            .contains("[WARN]", "Only password was defined")
        );
    }

    @Test
    void shouldWarnWhenMissingKeyfile() {
        container
            .withReplicaSet("test-rs")
            .withCredentials("test-user", "test-pass")
            .configure();
        assertThat(getLogs()).satisfiesOnlyOnce(it -> assertThat(it)
            .contains("[WARN] Replica set with authentication requires a key file.")
        );
    }
}
