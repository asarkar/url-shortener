package org.asarkar.urlshortener

import io.micronaut.context.annotation.Property
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Put
import io.micronaut.http.annotation.QueryValue
import io.reactivex.Maybe
import io.reactivex.Single
import java.net.URL
import java.util.concurrent.TimeUnit

@Controller(value = "/", produces = [MediaType.TEXT_PLAIN])
class UrlMappingController(
        private val urlMapper: UrlMapper,
        @Property(name = "app.async-timeout-millis") private val timeout: Long
) {
    @Get
    fun get(@QueryValue url: String): Maybe<String> {
        return URL(url)
                .path
                .dropWhile { it == '/' }
                .let(urlMapper::get)
                .timeout(timeout, TimeUnit.SECONDS)
    }

    @Put(consumes = [MediaType.TEXT_PLAIN])
    fun save(@Body longUrl: String): Single<String> {
        return urlMapper.save(longUrl)
                .timeout(timeout, TimeUnit.MILLISECONDS)
    }
}