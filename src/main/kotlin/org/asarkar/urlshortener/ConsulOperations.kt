package org.asarkar.urlshortener

import io.micronaut.context.annotation.Parallel
import io.micronaut.context.annotation.Property
import io.micronaut.discovery.consul.ConsulConfiguration
import io.micronaut.discovery.consul.client.v1.ConsulClient
import io.micronaut.discovery.consul.client.v1.KeyValue
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.RxHttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Single
import org.slf4j.LoggerFactory
import java.net.URL
import java.util.Base64
import javax.annotation.PreDestroy
import javax.inject.Singleton

interface ConsulOperations {
    fun createSession(): Maybe<String>
    fun destroySession(sessionId: String): Completable
    fun acquireLock(sessionId: String, retryDelay: Int = 2): Maybe<Boolean>
    fun getCounter(): Single<Int>
    fun setCounter(value: Int): Single<Int>
}

@Singleton
@Parallel
class DefaultConsulOperations(
        private val consulClient: ConsulClient,
        consulConfiguration: ConsulConfiguration,
        @Property(name = "micronaut.application.name") private val appName: String,
        @Property(name = "micronaut.application.instance.id") private val instanceId: String
) : ConsulOperations {
    private val Log = LoggerFactory.getLogger(DefaultConsulOperations::class.java)
    private val httpClient = RxHttpClient.create(
            URL("http://${consulConfiguration.host}:${consulConfiguration.port}")
    )
    private val CounterPath = "/$appName/counter"
    private val LockPath = "/$appName/lock"
    private val SessionPath = "/v1/session"

    @PreDestroy
    fun preDestroy() {
        Log.info("Closing HTTP client")
        httpClient.close()
    }

    override fun createSession(): Maybe<String> {
        return httpClient
                .retrieve(HttpRequest.PUT("$SessionPath/create", """{"Name": "$appName"}"""), Map::class.java)
                .filter { it.containsKey("ID") }
                .map { it["ID"].toString() }
                .firstElement()
                .doAfterSuccess { Log.debug("Created session: {} for instance: {}", it, instanceId) }
                .doOnError { Log.error("Failed to create session for instance: {}", instanceId) }
    }

    override fun destroySession(sessionId: String): Completable {
        return httpClient.retrieve(HttpRequest.PUT("$SessionPath/destroy/$sessionId", """{"Name": "$appName"}"""), Boolean::class.java)
                .filter { it }
                .doOnNext { Log.debug("Destroyed session: {} for instance: {}", sessionId, instanceId) }
                .doOnError { Log.error("Failed to destroy session: {} for instance: {}", sessionId, instanceId) }
                .ignoreElements()
    }

    override fun acquireLock(sessionId: String, retryDelay: Int): Maybe<Boolean> {
        return Flowable.fromPublisher(consulClient.putValue("$LockPath?acquire=$sessionId", instanceId))
                .filter { it }
                .firstElement()
                .doAfterSuccess { Log.debug("Acquired lock for session: {} and instance: {}", sessionId, instanceId) }
                .doOnError { Log.error("Failed to acquire lock for session: {} and instance: {}", sessionId, instanceId) }
    }

    override fun getCounter(): Single<Int> {
        return Flowable.fromPublisher(consulClient.readValues(CounterPath))
                .onErrorReturn { ex: Throwable ->
                    if (ex is HttpClientResponseException && ex.status == HttpStatus.NOT_FOUND) {
                        Log.info("Counter does not exist for instance: {}", instanceId)
                        listOf(KeyValue(CounterPath, Base64.getEncoder().encodeToString("1".toByteArray())))
                    } else {
                        throw ex
                    }
                }
                .map { values -> String(Base64.getDecoder().decode(values.first().value)).toInt() }
                .firstOrError()
                .doAfterSuccess { Log.info("Retrieved counter: {} for instance: {}", it, instanceId) }
    }

    override fun setCounter(value: Int): Single<Int> {
        return Flowable.fromPublisher(consulClient.putValue(CounterPath, value.toString()))
                .filter { it }
                .map { value }
                .singleOrError()
                .doAfterSuccess { Log.info("Updated counter to: {} for instance: {}", it, instanceId) }
                .doOnError { Log.error("Failed to update counter for instance: {}", instanceId) }
    }
}