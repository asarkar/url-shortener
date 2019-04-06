package org.asarkar.urlshortener

import org.junit.jupiter.api.extension.ExtendWith


@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(DisableIfNoDockerCondition::class)
@MustBeDocumented
annotation class DisabledIfNoDocker