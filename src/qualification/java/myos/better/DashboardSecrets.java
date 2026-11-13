package myos.better;

import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretResponse;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceExistsException;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

/** In-memory official SDK responses for the loopback fixture; never included in the service. */
final class DashboardSecrets {
  private DashboardSecrets() {}

  static ReaderCustody open() {
    var values = new ConcurrentHashMap<String, GetSecretValueResponse>();
    var client =
        (SecretsManagerClient)
            Proxy.newProxyInstance(
                SecretsManagerClient.class.getClassLoader(),
                new Class<?>[] {SecretsManagerClient.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close")) {
                    return null;
                  }
                  if (method.getName().equals("getSecretValue")) {
                    var request = (GetSecretValueRequest) args[0];
                    var value = values.get(request.secretId());
                    if (value == null || !request.versionId().equals(value.versionId())) {
                      throw ResourceNotFoundException.builder().build();
                    }
                    return value;
                  }
                  if (method.getName().equals("createSecret")) {
                    var request = (CreateSecretRequest) args[0];
                    String arn =
                        "arn:aws:secretsmanager:eu-west-1:111111111111:secret:"
                            + request.name()
                            + "-Test12";
                    var value =
                        GetSecretValueResponse.builder()
                            .arn(arn)
                            .name(request.name())
                            .versionId(request.clientRequestToken())
                            .secretString(request.secretString())
                            .build();
                    if (values.putIfAbsent(request.name(), value) != null) {
                      throw ResourceExistsException.builder().build();
                    }
                    values.put(arn, value);
                    return CreateSecretResponse.builder()
                        .arn(arn)
                        .name(request.name())
                        .versionId(request.clientRequestToken())
                        .build();
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    return new ReaderCustody(client, "eu-west-1", "111111111111", "myos-better-reader-dashboard-");
  }
}
