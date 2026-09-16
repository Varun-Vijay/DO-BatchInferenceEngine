package com.digitalocean.batchinference;

import com.digitalocean.batchinference.api.dto.BatchResultsResponse;
import com.digitalocean.batchinference.api.dto.BatchStatusResponse;
import com.digitalocean.batchinference.api.dto.SubmitBatchResponse;
import com.digitalocean.batchinference.domain.BatchStatus;
import com.digitalocean.batchinference.domain.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Exercises the scheduling core, so it pins the stub client: the assertions here are
 * about queueing and status transitions, not about talking to an endpoint.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "inference.client=stub")
class BatchFlowIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void submittedBatchIsAcknowledgedThenProcessedToCompletion() {
        List<String> prompts = IntStream.range(0, 25).mapToObj("prompt-%d"::formatted).toList();

        ResponseEntity<SubmitBatchResponse> submitted =
                rest.postForEntity("/api/v1/batches", Map.of("prompts", prompts), SubmitBatchResponse.class);

        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String batchId = submitted.getBody().batchId();
        assertThat(submitted.getBody().promptCount()).isEqualTo(25);
        assertThat(submitted.getBody().statusUrl()).isEqualTo("/api/v1/batches/" + batchId);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            BatchStatusResponse status =
                    rest.getForObject("/api/v1/batches/" + batchId, BatchStatusResponse.class);
            assertThat(status.status()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(status.counts().get(TaskStatus.SUCCEEDED)).isEqualTo(25);
            assertThat(status.progressPercent()).isEqualTo(100);
        });

        BatchResultsResponse results =
                rest.getForObject("/api/v1/batches/" + batchId + "/results", BatchResultsResponse.class);

        assertThat(results.summary().total()).isEqualTo(25);
        assertThat(results.summary().succeeded()).isEqualTo(25);
        assertThat(results.summary().failed()).isZero();
        assertThat(results.results()).hasSize(25);
        assertThat(results.results()).extracting(BatchResultsResponse.Result::inputIndex)
                .containsExactlyElementsOf(IntStream.range(0, 25).boxed().toList());
        assertThat(results.results()).allSatisfy(result ->
                assertThat(result.completion()).isEqualTo("[stub] echo: " + result.prompt()));
    }

    @Test
    void unknownBatchReturns404() {
        assertThat(rest.getForEntity("/api/v1/batches/does-not-exist", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void emptyPromptListIsRejected() {
        ResponseEntity<String> response =
                rest.postForEntity("/api/v1/batches", Map.of("prompts", List.of()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("validation_failed");
    }
}
