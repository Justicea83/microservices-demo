package com.microservices.demo.kafka.admin.client;

import com.microservices.demo.config.KafkaConfigData;
import com.microservices.demo.config.RetryConfigData;
import com.microservices.demo.kafka.admin.exception.KafkaClientException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicListing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Component
public class KafkaAdminClient {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaAdminClient.class);

    private final KafkaConfigData kafkaConfigData;
    private final RetryConfigData retryConfigData;
    private final AdminClient adminClient;
    private final RetryTemplate retryTemplate;
    private final WebClient webClient;

    public KafkaAdminClient(KafkaConfigData kafkaConfigData,WebClient webClient, RetryConfigData retryConfigData, AdminClient adminClient, RetryTemplate retryTemplate) {
        this.kafkaConfigData = kafkaConfigData;
        this.retryConfigData = retryConfigData;
        this.adminClient = adminClient;
        this.retryTemplate = retryTemplate;
        this.webClient = webClient;
    }

    public void createTopics() {
        CreateTopicsResult createTopicsResult;
        try {
            createTopicsResult = retryTemplate.execute(this::doCreateTopics);
        } catch (Throwable t) {
            throw new KafkaClientException("Reached max number of retry for creating kafka topic(s)", t);
        }
        checkTopicsCreated();
    }


    public void checkSchemaRegistry() {
        int retryCount = 1;
        int maxRetry = retryConfigData.getMaxAttempts();
        int multiplier = retryConfigData.getMultiplier().intValue();
        long sleepTimeMs = retryConfigData.getSleepTimeMs();
        while(!getSchemaRegistryStatus().is2xxSuccessful()){
            checkMaxRetry(retryCount++,maxRetry);
            sleep(sleepTimeMs);
            sleepTimeMs *= multiplier;
        }
    }

    public HttpStatus getSchemaRegistryStatus(){
        try {
            return webClient
                    .method(HttpMethod.GET)
                    .uri(kafkaConfigData.getSchemaRegistryUrl())
                    .exchange()
                   // .exchangeToMono()
                    .map(ClientResponse::statusCode)
                    .block();
        }catch (Exception e){
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
    }

    public void checkTopicsCreated() {
        Collection<TopicListing> topics = getTopics();
        int retryCount = 1;
        int maxRetry = retryConfigData.getMaxAttempts();
        int multiplier = retryConfigData.getMultiplier().intValue();
        long sleepTimeMs = retryConfigData.getSleepTimeMs();
        for (String topic : kafkaConfigData.getTopicNamesToCreate()) {
            while (!isTopicCreated(topics, topic)) {
                checkMaxRetry(retryCount++, maxRetry);
                sleep(sleepTimeMs);
                sleepTimeMs *= multiplier;
                topics = getTopics();
            }
        }

    }

    private void sleep(long sleepTimeMs) {
        try {
            Thread.sleep(sleepTimeMs);
        } catch (InterruptedException e) {
            throw new KafkaClientException("Error while sleeping for waiting new created topics");
        }
    }

    private void checkMaxRetry(int retry, int maxRetry) {
        if (retry > maxRetry)
            throw new KafkaClientException("Reached the max number of retry for reading kafka topic(s)");
    }

    private boolean isTopicCreated(Collection<TopicListing> topics, String topicName) {
        if (topics == null) return false;
        return topics.stream().anyMatch(topic -> topic.name().equals(topicName));
    }

    public CreateTopicsResult doCreateTopics(RetryContext retryContext) {
        List<String> topicNamesToCreate = kafkaConfigData.getTopicNamesToCreate();
        LOG.info("Creating {} topic(s), attempt {}", topicNamesToCreate.size(), retryContext.getRetryCount());

        List<NewTopic> kafkaTopics = topicNamesToCreate.stream().map(topic -> new NewTopic(
                topic.trim(),
                Math.toIntExact(kafkaConfigData.getNumOfPartitions()),
                kafkaConfigData.getReplicationFactor()
        )).collect(Collectors.toList());
        return adminClient.createTopics(kafkaTopics);
    }

    private Collection<TopicListing> getTopics() {
        Collection<TopicListing> topics;
        try {
            topics = retryTemplate.execute(this::doGetTopics);
        } catch (Throwable t) {
            throw new KafkaClientException("Reached max number of retry for reading kafka topics", t);
        }
        return topics;
    }

    private Collection<TopicListing> doGetTopics(RetryContext retryContext) throws InterruptedException, ExecutionException {
        LOG.info("Reading kafka topic {}, attempt {}", kafkaConfigData.getTopicNamesToCreate().toArray(), retryContext.getRetryCount());
        Collection<TopicListing> topics = adminClient.listTopics().listings().get();
        if (topics != null) {
            topics.forEach(topic -> LOG.debug("Topic with name {}", topic.name()));
        }
        return topics;
    }


}
