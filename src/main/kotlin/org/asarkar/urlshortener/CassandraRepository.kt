package org.asarkar.urlshortener

import com.datastax.driver.core.Cluster
import com.datastax.driver.core.DataType
import com.datastax.driver.core.Session
import com.datastax.driver.core.querybuilder.QueryBuilder
import com.datastax.driver.core.schemabuilder.SchemaBuilder
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.micronaut.context.annotation.Parallel
import io.micronaut.context.annotation.Property
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import javax.annotation.PreDestroy
import javax.inject.Singleton
import kotlin.math.pow


interface CassandraRepository {
    fun save(shortUrl: String, longUrl: String): Completable
    fun get(shortUrl: String): Maybe<String>
}

@Singleton
@Parallel
class DefaultCassandraRepository(
        private val cluster: Cluster,
        @Property(name = "app.async-timeout-millis") private val timeout: Long
) : CassandraRepository {
    companion object {
        const val KEYSPACE = "us";
        const val TABLE = "urls"
        const val SHORT_URL = "short_url"
        const val LONG_URL = "long_url"
    }

    private val Log = LoggerFactory.getLogger(DefaultCassandraRepository::class.java)
    private lateinit var session: Session

    private fun <T> ListenableFuture<T>.toMaybe(): Maybe<T> {
        return Maybe.defer {
            Maybe.create<T> { e ->
                Futures.addCallback(this, object : FutureCallback<T> {
                    override fun onSuccess(result: T?) {
                        if (result != null) {
                            e.onSuccess(result)
                        }
                        e.onComplete()
                    }

                    override fun onFailure(t: Throwable) {
                        e.onError(t)
                    }
                })
            }
        }
    }

    @PreDestroy
    fun preDestroy() {
        Log.info("Closing Cassandra session")
        session.close()
    }

    init {
        cluster.connectAsync().toMaybe()
                .flatMap { session ->
                    SchemaBuilder.createKeyspace(KEYSPACE)
                            .ifNotExists()
                            .with()
                            .replication(mapOf("class" to "SimpleStrategy", "replication_factor" to 1))
                            .durableWrites(true)
                            .let { session.executeAsync(it).toMaybe() }
                            .flatMap {
                                SchemaBuilder.createTable(KEYSPACE, TABLE)
                                        .ifNotExists()
                                        .addPartitionKey(SHORT_URL, DataType.text())
                                        .addColumn(LONG_URL, DataType.text())
                                        .let { session.executeAsync(it).toMaybe() }
                            }
                            .doOnSuccess {
                                Log.info("Created keyspace: {} and table: {}", KEYSPACE, TABLE)
                                this.session = session
                            }
                }
                .retryWhen { errors ->
                    errors.zipWith(1..3) { _, i -> i }
                            .flatMap { retryCount -> Flowable.timer(2.toDouble().pow(retryCount).toLong(), TimeUnit.SECONDS) }
                            .doOnNext { Log.warn("Retrying to create keyspace and table") }
                }
                .timeout(timeout, TimeUnit.MILLISECONDS)
                .blockingGet()
    }

    override fun save(shortUrl: String, longUrl: String): Completable {
        return QueryBuilder.insertInto(KEYSPACE, TABLE)
                .values(listOf(SHORT_URL, LONG_URL), listOf(shortUrl, longUrl))
                .let { session.executeAsync(it).toMaybe().ignoreElement() }
    }

    override fun get(shortUrl: String): Maybe<String> {
        return QueryBuilder.select()
                .from(KEYSPACE, TABLE)
                .where(QueryBuilder.eq(SHORT_URL, shortUrl))
                .let { session.executeAsync(it).toMaybe() }
                .flatMap { rs ->
                    val row = rs.firstOrNull()
                    if (row == null) {
                        Maybe.empty()
                    } else {
                        Maybe.just(row.get(LONG_URL, String::class.java))
                    }
                }
    }
}