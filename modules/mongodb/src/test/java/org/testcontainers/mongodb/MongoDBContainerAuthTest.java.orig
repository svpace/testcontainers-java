package org.testcontainers.containers;

import com.mongodb.BasicDBObject;
import com.mongodb.ConnectionString;
import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import lombok.val;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

import java.util.Collections;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MongoDBContainerAuthTest {

    private static final String DEFAULT_USERNAME = "test";

    private static final String DEFAULT_PASSWORD = "test";

    @Test
    public void shouldTestAuthenticationAccessControl() {
        final String usernameFullAccess = "my-name";
        final String passwordFullAccess = "my-pass";
        try (
            val mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:4.4"))
                .withCredentials(usernameFullAccess, passwordFullAccess)
        ) {
            mongoDBContainer.start();
            val connectionStringFullAccess = new ConnectionString(mongoDBContainer.getReplicaSetUrl());
            try (val mongoSyncClientFullAccess = MongoClients.create(connectionStringFullAccess)) {
                final MongoDatabase adminDatabase = mongoSyncClientFullAccess.getDatabase(
                    MongoDBContainer.DEFAULT_AUTHENTICATION_DATABASE
                );
                final MongoDatabase testDatabaseFullAccess = mongoSyncClientFullAccess.getDatabase(
                    MongoDBContainer.DEFAULT_DATABASE
                );
                final String collectionName = "my-collection";
                final Document document1 = new Document("abc", 1);
                testDatabaseFullAccess.getCollection(collectionName).insertOne(document1);
                final String usernameRestrictedAccess = usernameFullAccess + "-restricted";
                final String passwordRestrictedAccess = passwordFullAccess + "-restricted";
                runCommand(
                    adminDatabase,
                    new BasicDBObject("createUser", usernameRestrictedAccess).append("pwd", passwordRestrictedAccess),
                    "read"
                );
                try (val mongoSyncRestrictedAccess = MongoClients.create(mongoDBContainer.getReplicaSetUrl())) {
                    val collection = mongoSyncRestrictedAccess
                        .getDatabase(MongoDBContainer.DEFAULT_DATABASE)
                        .getCollection(collectionName);
                    assertThat(collection.find().first()).isEqualTo(document1);
                    final Document document2 = new Document("abc", 2);
                    assertThatThrownBy(() -> collection.insertOne(document2)).isInstanceOf(MongoCommandException.class);
                    runCommand(adminDatabase, new BasicDBObject("updateUser", usernameRestrictedAccess), "readWrite");
                    collection.insertOne(document2);
                    assertThat(collection.countDocuments()).isEqualTo(2);
                    assertThat(connectionStringFullAccess.getUsername()).isEqualTo(usernameFullAccess);
                    assertThat(new String(requireNonNull(connectionStringFullAccess.getPassword())))
                        .isEqualTo(passwordFullAccess);
                }
            }
        }
    }

    private void runCommand(MongoDatabase adminDatabase, BasicDBObject command, String role) {
        adminDatabase.runCommand(
            command.append(
                "roles",
                Collections.singletonList(
                    new BasicDBObject("role", role).append("db", MongoDBContainer.DEFAULT_DATABASE)
                )
            )
        );
    }
}
