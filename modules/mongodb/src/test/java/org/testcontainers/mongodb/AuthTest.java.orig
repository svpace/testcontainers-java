package org.testcontainers.containers;

import com.mongodb.BasicDBObject;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import lombok.val;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class AuthTest extends AbstractMongoTest {

    private static final String DEFAULT_USERNAME = "test";

    private static final String DEFAULT_PASSWORD = "test";

    @Test
    public void shouldTestAuthenticationAccessControl() {
        val usernameFullAccess = "my-name";
        val passwordFullAccess = "my-pass";
        container.withCredentials(usernameFullAccess, passwordFullAccess);
        container.start();

        val connectionStringFullAccess = new ConnectionString(container.getConnectionString());
        try (val mongoSyncClientFullAccess = MongoClients.create(connectionStringFullAccess)) {
            val adminDatabase = mongoSyncClientFullAccess.getDatabase(
                MongoDBContainer.DEFAULT_AUTHENTICATION_DATABASE
            );
            val testDatabaseFullAccess = mongoSyncClientFullAccess.getDatabase(
                MongoDBContainer.DEFAULT_DATABASE
            );
            val collectionName = "my-collection";
            val document1 = new Document("abc", 1);
            testDatabaseFullAccess.getCollection(collectionName).insertOne(document1);
            val usernameRestrictedAccess = usernameFullAccess + "-restricted";
            val passwordRestrictedAccess = passwordFullAccess + "-restricted";
            runCommand(
                adminDatabase,
                new BasicDBObject("createUser", usernameRestrictedAccess).append("pwd", passwordRestrictedAccess),
                "read"
            );
            val connectionStringRestrictedAccess = new ConnectionString(
                container.getConnectionStringBuilder()
                    .setUserInfo(usernameRestrictedAccess, passwordRestrictedAccess)
                    .toString()
            );
            try (val mongoSyncRestrictedAccess = MongoClients.create(connectionStringRestrictedAccess)) {
                val collection = mongoSyncRestrictedAccess
                    .getDatabase(MongoDBContainer.DEFAULT_DATABASE)
                    .getCollection(collectionName);
                assertThat(collection.find().first()).isEqualTo(document1);
                val document2 = new Document("abc", 2);
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
