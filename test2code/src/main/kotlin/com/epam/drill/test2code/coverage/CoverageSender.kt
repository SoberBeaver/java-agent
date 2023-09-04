/**
 * Copyright 2020 - 2022 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.epam.drill.test2code.coverage

import com.epam.drill.plugins.test2code.common.api.*
import com.epam.drill.test2code.*
import com.github.luben.zstd.*
import kotlinx.coroutines.*
import kotlinx.serialization.protobuf.*
import mu.*
import java.math.*
import java.util.*
import java.util.concurrent.*

interface CoverageSender {
    fun startSendingCoverage()
    fun stopSendingCoverage()
}

private val COVERAGE_RETENTION_LIMIT_BYTES = BigInteger.valueOf(JvmModuleConfiguration.getCoverageRetentionLimit())

class IntervalCoverageSender(
    private val intervalMs: Long,
    private val coverageTransport: CoverageTransport,
    private val inMemoryRetentionQueue: RetentionQueue = InMemoryRetentionQueue(totalSizeByteLimit = COVERAGE_RETENTION_LIMIT_BYTES),
    private val collectProbes: () -> Sequence<ExecDatum> = { emptySequence() }
) : CoverageSender {
    private val logger = KotlinLogging.logger {}
    private val scheduledThreadPool = Executors.newSingleThreadScheduledExecutor()

    override fun startSendingCoverage() {
        scheduledThreadPool.scheduleAtFixedRate(
            Runnable { sendProbes(collectProbes()) },
            0,
            //TODO investigate which number is preferable
            intervalMs,
            TimeUnit.MILLISECONDS
        )
        logger.debug { "Coverage sending job is started." }
    }

    override fun stopSendingCoverage() {
        scheduledThreadPool.shutdown()
        logger.debug { "Coverage sending job is stopped." }
    }

    /**
     * Create a function which sends chunks of test coverage to the admin part of the plugin
     * @return the function of sending test coverage
     * @features Coverage data sending
     */
    private fun sendProbes(data: Sequence<ExecDatum>) {
        val dataToSend = data
            .map {
                ExecClassData(
                    id = it.id,
                    className = it.name,
                    probes = it.probes.values.toBitSet(),
                    sessionId = it.sessionId,
                    testId = it.testId,
                )
            }
            //TODO investigate which number is preferable
            .chunked(0xffff)
            .map { chunk -> CoverDataPart(data = chunk) }
            .map { message ->
                logger.debug { "Compress message $message." }
                val encoded = ProtoBuf.encodeToByteArray(CoverMessage.serializer(), message)
                Zstd.compress(encoded)
            }

        if (coverageTransport.isAvailable()) {
            val failedToSend = mutableListOf<ByteArray>()

            val send = { message: ByteArray ->
                val encoded = Base64.getEncoder().encodeToString(message)
                try {
                    coverageTransport.send(encoded)
                } catch (e: Exception) {
                    failedToSend.add(message)
                }
            }

            dataToSend.forEach { send(it) }
            inMemoryRetentionQueue.flush().forEach { send(it) }
            if (failedToSend.size > 0) inMemoryRetentionQueue.addAll(failedToSend.asSequence())
        } else {
            inMemoryRetentionQueue.addAll(dataToSend)
        }
    }
}
