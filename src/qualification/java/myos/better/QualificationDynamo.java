package myos.better;

import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** A new independent connection to a disposable qualification table on the loopback emulator. */
final class QualificationDynamo {
  private QualificationDynamo() {}

  static DynamoStore open(String address, String table, String tablePrefix) {
    URI endpoint = URI.create(address);
    if (!"http".equals(endpoint.getScheme())
        || !"127.0.0.1".equals(endpoint.getHost())
        || endpoint.getPort() < 1
        || !table.startsWith(tablePrefix)) {
      throw new IllegalArgumentException("Expected a loopback DynamoDB qualification table");
    }
    var client =
        DynamoDbClient.builder()
            .region(Region.EU_WEST_1)
            .endpointOverride(endpoint)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .build();
    return new DynamoStore(table, client);
  }
}
