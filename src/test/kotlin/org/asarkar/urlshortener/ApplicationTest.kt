package org.asarkar.urlshortener

import io.micronaut.context.ApplicationContext
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.client.RxHttpClient
import io.micronaut.http.uri.UriBuilder
import io.micronaut.runtime.server.EmbeddedServer
import io.reactivex.Flowable
import io.reactivex.Single
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.random.Random

class KGenericContainer(private val imageName: String, private val exposedPort: Int) : GenericContainer<KGenericContainer>(imageName) {
    private val LogConsumer = Slf4jLogConsumer(LoggerFactory.getLogger("${javaClass.name}.${imageName.capitalize()}"))

    override fun configure() {
        super.configure()
        super.withStartupTimeout(Duration.ofMinutes(1L))
        super.withExposedPorts(exposedPort)
        super.withLogConsumer(LogConsumer)
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisabledIfNoDocker
class ApplicationTest {
    lateinit var consul: KGenericContainer
    lateinit var cassandra: KGenericContainer
    lateinit var redis: KGenericContainer
    lateinit var server: EmbeddedServer
    lateinit var client: RxHttpClient

    @BeforeAll
    fun beforeAll() {
        consul = KGenericContainer("consul", 8500)
                .withEnv("CONSUL_LOCAL_CONFIG", """{ "bootstrap_expect": 1, "server": true, "log_level": "INFO" }""")
                .apply {
                    start()
                }
        cassandra = KGenericContainer("cassandra:3", 9042)
                .withEnv("JVM_EXTRA_OPTS", "-Dcassandra.skip_wait_for_gossip_to_settle=0 -Dcassandra.load_ring_state=false")
                .apply {
                    start()
                }

        redis = KGenericContainer("redis:5-alpine", 6379)
                .apply { start() }

        val endpoints = mapOf(
                "redis.uri" to "redis://${redis.containerIpAddress}:${redis.firstMappedPort}",
                "cassandra.default.contactPoint" to cassandra.containerIpAddress,
                "cassandra.default.port" to cassandra.firstMappedPort,
                "consul.client.host" to consul.containerIpAddress,
                "consul.client.port" to consul.firstMappedPort
        )

        server = Single.just(ApplicationContext.run(EmbeddedServer::class.java, endpoints))
                .retryWhen { errors ->
                    errors.zipWith(1..3) { _, i -> i }
                            .flatMap { retryCount -> Flowable.timer(2.toDouble().pow(retryCount).toLong(), TimeUnit.SECONDS) }
                }
                .timeout(30L, TimeUnit.SECONDS)
                .blockingGet()
        client = RxHttpClient.create(server.url)
    }

    @AfterAll
    fun afterAll() {
        listOf(client, server, consul, cassandra, redis)
                .forEach { it.close() }
    }

    @Test
    fun testSaveAndGet() {
        for (i in 1..3) {
            val scheme = if (Random.nextBoolean()) "https" else "http"
            val port = if (Random.nextBoolean()) ":${Random.nextInt(1024, 49151)}" else ""

            val longUrl = "$scheme://localhost$port/${randomPath()}"
            val shortUrl = client.toBlocking()
                    .retrieve(HttpRequest.PUT("/", longUrl).contentType(MediaType.TEXT_PLAIN), String::class.java)
            val uri = UriBuilder.of(URI.create("/"))
                    .queryParam("url", shortUrl)
                    .build()
            val saved = client.toBlocking()
                    .retrieve(HttpRequest.GET<Unit>(uri), String::class.java)
            assertEquals(longUrl, saved)
        }
    }

    private fun randomPath(): String {
        val charPool = ('0'..'9') + ('a'..'z') + ('A'..'Z')

        return generateSequence { Random.nextInt(0, charPool.size) }
                .take(6)
                .map(charPool::get)
                .joinToString("")
    }
}