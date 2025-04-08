package org.testcontainers.mongodb;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.TransactionBody;
import lombok.NonNull;
import lombok.val;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;

public abstract class AbstractMongoTest {

    protected MongoDBContainer container;

    protected ListAppender<ILoggingEvent> logs;

    protected @NonNull DockerImageName getTargetImage() {
        return DockerImageName.parse("mongo:4.0.28");
    }

    protected List<String> getLogs() {
        return logs.list.stream().map(Object::toString).collect(toList());
    }

    @BeforeEach
    void init() {
        container = new MongoDBContainer(getTargetImage());
        logs = new ListAppender<>();
        logs.start();
        val logger = (Logger) LoggerFactory.getLogger(MongoDBContainer.class);
        logger.setLevel(Level.DEBUG);
        logger.addAppender(logs);
    }

    protected void executeTx(MongoDBContainer mongoDBContainer) {
        final MongoClient mongoSyncClientBase = MongoClients.create(mongoDBContainer.getConnectionString());
        final MongoClient mongoSyncClient = MongoClients.create(mongoDBContainer.getReplicaSetUrl());
        mongoSyncClient
            .getDatabase("mydb1")
            .getCollection("foo")
            .withWriteConcern(WriteConcern.MAJORITY)
            .insertOne(new Document("abc", 0));
        mongoSyncClient
            .getDatabase("mydb2")
            .getCollection("bar")
            .withWriteConcern(WriteConcern.MAJORITY)
            .insertOne(new Document("xyz", 0));
        mongoSyncClientBase
            .getDatabase("mydb3")
            .getCollection("baz")
            .withWriteConcern(WriteConcern.MAJORITY)
            .insertOne(new Document("def", 0));

        final ClientSession clientSession = mongoSyncClient.startSession();
        final TransactionOptions txnOptions = TransactionOptions
            .builder()
            .readPreference(ReadPreference.primary())
            .readConcern(ReadConcern.LOCAL)
            .writeConcern(WriteConcern.MAJORITY)
            .build();

        final String trxResult = "Inserted into collections in different databases";

        TransactionBody<String> txnBody = () -> {
            final MongoCollection<Document> coll1 = mongoSyncClient.getDatabase("mydb1").getCollection("foo");
            final MongoCollection<Document> coll2 = mongoSyncClient.getDatabase("mydb2").getCollection("bar");

            coll1.insertOne(clientSession, new Document("abc", 1));
            coll2.insertOne(clientSession, new Document("xyz", 999));
            return trxResult;
        };

        try {
            final String trxResultActual = clientSession.withTransaction(txnBody, txnOptions);
            assertThat(trxResultActual).isEqualTo(trxResult);
        } catch (RuntimeException re) {
            throw new IllegalStateException(re.getMessage(), re);
        } finally {
            clientSession.close();
            mongoSyncClient.close();
        }
    }
}
