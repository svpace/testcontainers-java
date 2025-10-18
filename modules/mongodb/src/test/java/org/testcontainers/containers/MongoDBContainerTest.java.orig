package org.testcontainers.containers;

import lombok.val;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MongoDBContainerTest extends AbstractMongoTest {

    /**
     * Taken from <a href="https://docs.mongodb.com/manual/core/transactions/">https://docs.mongodb.com</a>
     */
    @Test
    public void shouldExecuteTransactions() {
        try (val mongoDBContainer = new MongoDBContainer("mongo:4.0.28")) {
            mongoDBContainer.start();
            // }
            executeTx(mongoDBContainer);
        }
    }

    @Test
    public void supportsMongoDB_7_0() {
        try (val mongoDBContainer = new MongoDBContainer("mongo:7.0")) {
            mongoDBContainer.start();
        }
    }

    @Test
    public void shouldTestDatabaseName() {
        try (val mongoDBContainer = new MongoDBContainer("mongo:4.0.28")) {
            mongoDBContainer.start();
            val databaseName = "my-db";
            assertThat(mongoDBContainer.getReplicaSetUrl(databaseName)).contains(databaseName);
        }
    }
}
