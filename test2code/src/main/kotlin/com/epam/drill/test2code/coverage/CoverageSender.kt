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
import com.github.luben.zstd.*
import kotlinx.coroutines.*
import kotlinx.serialization.protobuf.*
import mu.*
import java.util.*
import java.util.concurrent.*
import kotlin.coroutines.*

interface CoverageSender {
    fun startSendingCoverage()
    fun stopSendingCoverage()
}

class IntervalCoverageSender(
    intervalMs: Long,
    private val coverageTransport: CoverageTransport,
    private val inMemoryBuffer: InMemoryBuffer<String> = InMemoryBuffer(),
    collectProbes: () -> Sequence<ExecDatum> = { emptySequence() }
) : CoverageSender {
    private val logger = KotlinLogging.logger {}
    private val job = ProbeWorker.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
            delay(intervalMs)
            sendProbes(collectProbes())
        }
    }

    override fun startSendingCoverage() {
        job.start()
        logger.debug { "Coverage sending job is started." }
    }

    override fun stopSendingCoverage() {
        job.cancel()
        logger.debug { "Coverage sending job is stopped." }
    }

    /**
     * Create a function which sends chunks of test coverage to the admin part of the plugin
     * @return the function of sending test coverage
     * @features Coverage data sending
     */
    private fun sendProbes(data: Sequence<ExecDatum>) {
        val compressedStrings = data
            .map(ExecDatum::toExecClassData)
            .chunked(0xffff)
            .map { chunk -> CoverDataPart(data = chunk) }
            .map { message ->
                logger.debug { "Compress message $message" }
                val encoded = ProtoBuf.encodeToByteArray(CoverMessage.serializer(), message)
                val compressed = Zstd.compress(encoded)
                Base64.getEncoder().encodeToString(compressed)
            }
        if (coverageTransport.isAvailable().get()) {
            (compressedStrings + inMemoryBuffer.flush()).forEach {
                coverageTransport.send(it)
            }
        } else {
            inMemoryBuffer.add(compressedStrings)
        }
    }
}

internal object ProbeWorker : CoroutineScope {
    override val coroutineContext: CoroutineContext = run {
        // TODO ProbeWorker thread count configure via env.variable?
        Executors.newFixedThreadPool(2).asCoroutineDispatcher() + SupervisorJob()
    }
}