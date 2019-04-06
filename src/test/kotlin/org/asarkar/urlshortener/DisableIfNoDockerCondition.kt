package org.asarkar.urlshortener

import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.dockerclient.DockerClientProviderStrategy
import java.util.ServiceLoader

class DisableIfNoDockerCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext?): ConditionEvaluationResult {
        val configurationStrategies = ServiceLoader.load(DockerClientProviderStrategy::class.java)
                .map { it }
        return try {
            DockerClientProviderStrategy.getFirstValidStrategy(configurationStrategies)
            ConditionEvaluationResult.enabled(null)
        } catch (e: IllegalStateException) {
            ConditionEvaluationResult.disabled(e.message)
        }
    }
}