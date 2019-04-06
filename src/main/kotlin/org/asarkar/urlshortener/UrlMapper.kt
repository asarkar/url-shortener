package org.asarkar.urlshortener

import io.micronaut.cache.annotation.Cacheable
import io.micronaut.context.annotation.Context
import io.micronaut.context.annotation.Property
import io.micronaut.runtime.server.EmbeddedServer
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Single
import org.slf4j.LoggerFactory
import java.util.Deque
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow


@Context
open class UrlMapper( // open up to support caching AOP
        private val consulOperations: ConsulOperations,
        private val cassandraRepository: CassandraRepository,
        private val embeddedServer: EmbeddedServer,
        @Property(name = "app.async-timeout-millis") private val timeout: Long
) {
    // TODO: reset when lo == hi
    private val lo = AtomicInteger(-1)
    private val hi = AtomicInteger(-1)
    private val Log = LoggerFactory.getLogger(UrlMapper::class.java)

    init {
        setCounters()
    }

    private fun setCounters() {
        consulOperations.createSession()
                .subscribe({ sessionId ->
                    consulOperations.acquireLock(sessionId)
                            .flatMapSingle { consulOperations.getCounter() }
                            .flatMap { consulOperations.setCounter(it + RANGE) }
                            .doOnSuccess {
                                hi.set(it)
                                lo.set(it - RANGE)
                            }
                            .retryWhen { errors ->
                                errors.zipWith(1..3) { _, i -> i }
                                        .flatMap { retryCount -> Flowable.timer(2.toDouble().pow(retryCount).toLong(), TimeUnit.SECONDS) }
                                        .doOnNext { Log.warn("Retrying to get and set counters") }
                            }
                            .doFinally {
                                consulOperations.destroySession(sessionId)
                                        .subscribe({}, { Log.error(it.message, it) })
                            }
                            .timeout(timeout, TimeUnit.MILLISECONDS)
                            .blockingGet()
                },
                        { t -> Log.error(t.message, t) }
                )
    }

    @Cacheable("url-cache")
    open fun save(longUrl: String): Single<String> {
        return if (hi.get() < 0) {
            Single.error(IllegalStateException("Counters not initialized"))
        } else {
            val shortUrl = encode(hi.getAndDecrement())
            cassandraRepository.save(shortUrl, longUrl)
                    .toSingle { "${embeddedServer.url}/$shortUrl" }
                    .doOnSuccess { Log.info("Mapped {} to {}", longUrl, it) }
        }
    }

    @Cacheable("url-cache")
    open fun get(shortUrl: String): Maybe<String> {
        return cassandraRepository.get(shortUrl)
    }

    companion object {
        val charPool = ('0'..'9') + ('a'..'z') + ('A'..'Z')
        const val RANGE = 100

        tailrec fun encode(num: Int, stack: Deque<Char> = LinkedList<Char>()): String {
            return if (num <= 0) {
                stack.joinToString(separator = "")
            } else {
                encode(num / 62, stack.apply { push(charPool[num % 62]) })
            }
        }
    }
}