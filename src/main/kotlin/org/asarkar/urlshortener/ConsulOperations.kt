package org.asarkar.urlshortener

import io.micronaut.context.annotation.Parallel
import io.micronaut.context.env.Environment
import io.micronaut.core.io.socket.SocketUtils
import io.micronaut.discovery.consul.client.v1.ConsulClient
import io.micronaut.discovery.consul.client.v1.KeyValue
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.RxHttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.runtime.ApplicationConfiguration
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Single
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.Optional
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
        @Client("http://\${consul.client.host}:\${consul.client.port}/v1") private val httpClient: RxHttpClient,
        private val appConfig: ApplicationConfiguration
) : ConsulOperations {
    private val Log = LoggerFactory.getLogger(DefaultConsulOperations::class.java)

    private val AppName = appConfig.name.orElse("unknown")
    private val InstanceId = appConfig.instance.id
            .or { Optional.ofNullable(System.getenv(Environment.HOSTNAME)) }
            .orElse(SocketUtils.LOCALHOST)
    private val CounterPath = "/$AppName/counter"
    private val LockPath = "/$AppName/lock"
    private val SessionPath = "/session"

    override fun createSession(): Maybe<String> {
        return httpClient
                .retrieve(HttpRequest.PUT("$SessionPath/create", """{"Name": "$AppName"}"""), Map::class.java)
                .filter { it.containsKey("ID") }
                .map { it["ID"].toString() }
                .firstElement()
                .doAfterSuccess { Log.debug("Created session: {} for instance: {}", it, InstanceId) }
                .doOnError { Log.error("Failed to create session for instance: {}", InstanceId) }
    }

    override fun destroySession(sessionId: String): Completable {
        return httpClient.retrieve(HttpRequest.PUT("$SessionPath/destroy/$sessionId", """{"Name": "$AppName"}"""), Boolean::class.java)
                .filter { it }
                .doOnNext { Log.debug("Destroyed session: {} for instance: {}", sessionId, InstanceId) }
                .doOnError { Log.error("Failed to destroy session: {} for instance: {}", sessionId, InstanceId) }
                .ignoreElements()
    }

    override fun acquireLock(sessionId: String, retryDelay: Int): Maybe<Boolean> {
        return Flowable.fromPublisher(consulClient.putValue("$LockPath?acquire=$sessionId", InstanceId))
                .filter { it }
                .firstElement()
                .doAfterSuccess { Log.debug("Acquired lock for session: {} and instance: {}", sessionId, InstanceId) }
                .doOnError { Log.error("Failed to acquire lock for session: {} and instance: {}", sessionId, InstanceId) }
    }

    override fun getCounter(): Single<Int> {
        return Flowable.fromPublisher(consulClient.readValues(CounterPath))
                .onErrorReturn { ex: Throwable ->
                    if (ex is HttpClientResponseException && ex.status == HttpStatus.NOT_FOUND) {
                        Log.info("Counter does not exist for instance: {}", InstanceId)
                        listOf(KeyValue(CounterPath, Base64.getEncoder().encodeToString("1".toByteArray())))
                    } else {
                        throw ex
                    }
                }
                .map { values -> String(Base64.getDecoder().decode(values.first().value)).toInt() }
                .firstOrError()
                .doAfterSuccess { Log.info("Retrieved counter: {} for instance: {}", it, InstanceId) }
    }

    override fun setCounter(value: Int): Single<Int> {
        return Flowable.fromPublisher(consulClient.putValue(CounterPath, value.toString()))
                .filter { it }
                .map { value }
                .singleOrError()
                .doAfterSuccess { Log.info("Updated counter to: {} for instance: {}", it, InstanceId) }
                .doOnError { Log.error("Failed to update counter for instance: {}", InstanceId) }
    }
}