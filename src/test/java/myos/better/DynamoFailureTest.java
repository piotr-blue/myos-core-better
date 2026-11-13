package myos.better;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;

class DynamoFailureTest {
  private static CancellationReason reason(String code) {
    return CancellationReason.builder().code(code).build();
  }

  @Test
  void aMixedConflictAndThrottlingCancellationCanRetryTheWholeAtomicAttempt() {
    assertTrue(
        DynamoStore.retryableCancellation(
            List.of(reason("None"), reason("ThrottlingError"), reason("ConditionalCheckFailed"))));
    assertTrue(
        DynamoStore.retryableCancellation(
            List.of(reason("TransactionConflict"), reason("ProvisionedThroughputExceeded"))));
  }

  @Test
  void malformedOrUnclassifiedTransactionsAreNotHiddenByRetries() {
    assertFalse(DynamoStore.retryableCancellation(List.of()));
    assertFalse(DynamoStore.retryableCancellation(List.of(reason("None"))));
    assertFalse(DynamoStore.retryableCancellation(List.of(CancellationReason.builder().build())));
    assertFalse(
        DynamoStore.retryableCancellation(List.of(reason("None"), reason("ValidationError"))));
    assertFalse(
        DynamoStore.retryableCancellation(List.of(reason("ItemCollectionSizeLimitExceeded"))));
  }
}
