package com.example.springkafkaoauthproducer.service;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.strimzi.kafka.oauth.client.ClientConfig;
import io.strimzi.kafka.oauth.common.Config;
import io.strimzi.kafka.oauth.common.ConfigProperties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;

import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.ExecutionException;


@Service
public class ProducerService {
  private static String bootstrapServers = null;
  private static String topic = null;
  private static String clientId = null;
  private static String clientSecret = null;
  private static Long sleepTimeMs = null;
  private static String acks = null;

  public ProducerService(){}

  public void produce(){

    try (final KubernetesClient client = new KubernetesClientBuilder().build()){
      // topic, client_id, bootstrap, acks, sleepMs
      ConfigMap configMap = client.configMaps().inNamespace("default").withName("producer-configmap").get();
      topic = configMap.getData().get("producer-topic");
      clientId = configMap.getData().get("producer-client-id");
      bootstrapServers = configMap.getData().get("producer-bootstrap-servers");
      sleepTimeMs = Long.valueOf(configMap.getData().get("producer-sleep-ms"));
      acks = configMap.getData().get("producer-acks");
      // client_secret
      Secret secret = client.secrets().inNamespace("default").withName("producer-secret").get();
      String clientSecretEncoded = secret.getData().get("producer-client-secret");
      clientSecret = new String(Base64.getDecoder().decode(clientSecretEncoded));
    } catch (KubernetesClientException kce){
      // log exception
    }
    System.out.println("[client_id]: " + clientId);
    System.out.println("[client_secret]: " + clientSecret);
    Properties defaults = new Properties();
    Config external = new Config();

    //  Set KEYCLOAK_HOST to connect to Keycloak host other than 'keycloak'
    //  Use 'keycloak.host' system property or KEYCLOAK_HOST env variable

    final String keycloakHost = external.getValue("localhost", "localhost");
    final String realm = external.getValue("realm", "kafka");
    final String tokenEndpointUri = "http://" + keycloakHost + ":8080/realms/" + realm + "/protocol/openid-connect/token";

    //  You can also configure token endpoint uri directly via 'oauth.token.endpoint.uri' system property,
    //  or OAUTH_TOKEN_ENDPOINT_URI env variable

    defaults.setProperty(ClientConfig.OAUTH_TOKEN_ENDPOINT_URI, tokenEndpointUri);

    //  By defaut this client uses preconfigured clientId and secret to authenticate.
    //  You can set OAUTH_ACCESS_TOKEN or OAUTH_REFRESH_TOKEN to override default authentication.
    //
    //  If access token is configured, it is passed directly to Kafka broker
    //  If refresh token is configured, it is used in conjunction with clientId and secret
    //
    //  See examples README.md for more info.

    final String accessToken = external.getValue(ClientConfig.OAUTH_ACCESS_TOKEN, null);
    System.out.println("[access_token]: " + accessToken);

    if (accessToken == null) {
      defaults.setProperty(Config.OAUTH_CLIENT_ID, clientId);
      System.out.println("[client_id]: " + clientId);

      defaults.setProperty(Config.OAUTH_CLIENT_SECRET, clientSecret);
      System.out.println("[client_secret]: " + clientSecret);
    }

    // Use 'preferred_username' rather than 'sub' for principal name
    if (isAccessTokenJwt(external)) {
      defaults.setProperty(Config.OAUTH_USERNAME_CLAIM, "preferred_username");
    }

    // Resolve external configurations falling back to provided defaults
    ConfigProperties.resolveAndExportToSystemProperties(defaults);

    Properties props = buildProducerConfig();
    Producer<String, String> producer = new KafkaProducer<>(props);

    for (int i = 0; ; i++) {

      try {
        producer.send(new ProducerRecord<>(topic, "Message " + i))
                .get();
        System.out.println("Produced Message " + i);
      } catch (InterruptedException e) {
        throw new RuntimeException("Interrupted while sending!");
      } catch (ExecutionException e) {
        if (e.getCause() instanceof AuthenticationException
                || e.getCause() instanceof AuthorizationException) {
          producer.close();
          producer = new KafkaProducer<>(props);
        } else {
          throw new RuntimeException("Failed to send message: " + i, e);
        }
      }

      try {
        Thread.sleep(sleepTimeMs);
      } catch (InterruptedException e) {
        throw new RuntimeException("Interrupted while sleeping!");
      }

    } // end for loop
  } // end method

  @SuppressWarnings("deprecation")
  private static boolean isAccessTokenJwt(Config config) {
    String legacy = config.getValue(Config.OAUTH_TOKENS_NOT_JWT);
    if (legacy != null) {
      System.out.println("[WARN] Config option 'oauth.tokens.not.jwt' is deprecated. Use 'oauth.access.token.is.jwt' (with reverse meaning) instead.");
    }
    return legacy != null ? !Config.isTrue(legacy) :
            config.getValueAsBoolean(Config.OAUTH_ACCESS_TOKEN_IS_JWT, true);
  }

  /**
   * Build KafkaProducer properties. The specified values are defaults that can be overridden
   * through runtime system properties or env variables.
   *
   * @return Configuration properties
   */
  private static Properties buildProducerConfig() {

    Properties p = new Properties();

    p.setProperty("security.protocol", "SASL_SSL");
    p.setProperty("sasl.mechanism", "OAUTHBEARER");

    p.setProperty("sasl.jaas.config", "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required ;");
//            "login.id=" +
//            "debug=\"true\" " +
//            "OAUTH_LOGIN_SERVER=\"localhost:8080\" " +
//            "OAUTH_LOGIN_ENDPOINT='/realms/kafka/protocol/openid-connect/token' " +
//            "OAUTH_LOGIN_GRANT_TYPE=client_credentials " +
//            "OAUTH_LOGIN_SCOPE=openid " +
//            "OAUTH_AUTHORIZATION='Basic test-producer-client:4fvZCpeXAM6dTT14W8hGfuviNM8u5Kud' " +
//            "OAUTH_INTROSPECT_SERVER=localhost:8080 " +
//            "OAUTH_INTROSPECT_ENDPOINT='/realms/kafka/protocol/openid-connect/token/introspect' " +
//            "OAUTH_INTROSPECT_AUTHORIZATION='Basic test-producer-client:4fvZCpeXAM6dTT14W8hGfuviNM8u5Kud';");
    p.setProperty("sasl.login.callback.handler.class", "io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler");
    p.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    p.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    p.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    System.out.println("[producer_acks]: " + acks);
    p.setProperty(ProducerConfig.ACKS_CONFIG, acks);
    //p.setProperty(Config.OAUTH_SCOPE, "client_credentials");

    // TrustStore
    String trustStoreLocation = "";
    p.setProperty(Config.OAUTH_SSL_TRUSTSTORE_LOCATION, trustStoreLocation);
    String trustStoreType = "";
    p.setProperty(Config.OAUTH_SSL_TRUSTSTORE_TYPE, trustStoreType);
    String trustStorePassword = "";
    p.setProperty(Config.OAUTH_SSL_TRUSTSTORE_PASSWORD, trustStorePassword);

    // Adjust re-authentication options
    // See: strimzi-kafka-oauth/README.md
    p.setProperty("sasl.login.refresh.buffer.seconds", "30");
    p.setProperty("sasl.login.refresh.min.period.seconds", "30");
    p.setProperty("sasl.login.refresh.window.factor", "0.8");
    p.setProperty("sasl.login.refresh.window.jitter", "0.01");

    return ConfigProperties.resolve(p);
  }
}

