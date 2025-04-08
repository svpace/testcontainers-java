package org.testcontainers.containers;

import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ShellTest {

    protected static final String HELLO = "db.runCommand({ hello: 1 });";

    private static final Arguments[] IMAGES = new Arguments[] {
        Arguments.of("mongo:4.0.28", "mongo"),
        Arguments.arguments("mongo:5.0.31", "mongosh")
    };

    @ParameterizedTest
    @FieldSource("IMAGES")
    void shouldExecuteCommandWhenExecInMongoUsed(String image, String executable) throws IOException, InterruptedException {
        try(val container = new MongoDBContainer(image)) {
            container.start();
            val result = container.execInMongo(HELLO);
            assertThat(result.getExitCode()).isEqualTo(0);
            assertThat(result.getStdout()).containsPattern("\"?\\bok\\b\"?\\s*:\\s*1\\b");
            assertThat(container.getShellExecutable()).isEqualTo(executable);
        }
    }

    @Test
    void shouldThrowWhenNotStartedAndExecInMongo() {
        try(val container = new MongoDBContainer("mongo:4.0.28")) {
            assertThatThrownBy(() -> {
                container.execInMongo(HELLO);
            })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execInMongo can only be used while the container is running");
        }
    }
}
