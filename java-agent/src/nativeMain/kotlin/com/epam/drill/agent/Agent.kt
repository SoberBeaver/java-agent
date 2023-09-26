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
package com.epam.drill.agent

import kotlin.native.concurrent.*
import kotlin.test.*
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.cinterop.*
import platform.posix.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.streams.*
import mu.*
import com.epam.drill.agent.configuration.*
import com.epam.drill.agent.jvmti.event.*
import com.epam.drill.jvmapi.gen.*

@SharedImmutable
private val logger = KotlinLogging.logger("com.epam.drill.agent.Agent")

private val LOGO = """
  ____    ____                 _       _          _  _                _      
 |  _"\U |  _"\ u     ___     |"|     |"|        | ||"|            U |"| u   
/| | | |\| |_) |/    |_"_|  U | | u U | | u      | || |_          _ \| |/    
U| |_| |\|  _ <       | |    \| |/__ \| |/__     |__   _|        | |_| |_,-. 
 |____/ u|_| \_\    U/| |\u   |_____| |_____|      /|_|\          \___/-(_/  
  |||_   //   \\_.-,_|___|_,-.//  \\  //  \\      u_|||_u          _//       
 (__)_) (__)  (__)\_)-' '-(_/(_")("_)(_")("_)     (__)__)         (__)  v. ${agentVersion}⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀
        """.trimIndent()

object Agent {

    val startTimeMark: TimeMark = TimeSource.Monotonic.markNow().freeze()

    val isHttpHookEnabled: Boolean by lazy {
        getenv(SYSTEM_HTTP_HOOK_ENABLED)?.toKString()?.toBoolean() ?: memScoped {
            alloc<CPointerVar<ByteVar>>().apply {
                GetSystemProperty(HTTP_HOOK_ENABLED, this.ptr)
            }.value?.toKString()?.toBoolean() ?: true
        }
    }

    fun agentOnLoad(options: String): Int {
        println(LOGO)
        try {
            defaultNativeLoggingConfiguration()
            drillInstallationDir = getDrillInstallationDir()
            val initialParams = agentParams(options)
            performInitialConfiguration(initialParams)
            setUnhandledExceptionHook({ error: Throwable ->
                logger.error(error) { "unhandled event $error" }
            }.freeze())

            memScoped {
                val jvmtiCapabilities = alloc<jvmtiCapabilities>()
                jvmtiCapabilities.can_retransform_classes = 1.toUInt()
                jvmtiCapabilities.can_maintain_original_method_order = 1.toUInt()
                AddCapabilities(jvmtiCapabilities.ptr)
            }
            AddToBootstrapClassLoaderSearch("$drillInstallationDir/drillRuntime.jar")
            callbackRegister()

            logger.info { "The native agent was loaded" }
            logger.info { "Pid is: " + getpid() }
        } catch (ex: Throwable) {
            logger.error { "Can't load the agent. Ex: ${ex.message}" }
            JNI_ERR
        }
        return JNI_OK
    }

    fun agentOnUnload() {
        logger.info { "Agent_OnUnload" }
    }

    private val pathSeparator = if (Platform.osFamily == OsFamily.WINDOWS) "\\" else "/"

    private fun getDrillInstallationDir() = open("/proc/self/cmdline", O_RDONLY).let(::Input).readText().run {
        val isContainsAgetPath: (String) -> Boolean = { it.contains("-agentpath:") }
        val agentLine = this.takeIf(isContainsAgetPath) ?: getenv("JAVA_TOOL_OPTIONS")!!.toKString()
        val agentPath = Regex("-agentpath:(.+?)=.+").matchEntire(agentLine)!!.groups[1]!!.value
        agentPath.substringBeforeLast(pathSeparator)
    }

    private fun agentParams(options: String): Map<String, String> {
        logger.info { "agent options: $options" }
        val agentParams = asAgentParams(options)
        logger.info { "agent parameters: $agentParams" }
        val drillPath = drillInstallationDir
            ?.removeSuffix(pathSeparator)
            ?.takeIf(String::isNotEmpty)
            ?: "."
        val configPath = agentParams["configPath"]
            ?: getenv(SYSTEM_CONFIG_PATH)?.toKString()
            ?: "${drillPath}${pathSeparator}drill.properties"
        logger.info { "config path: $configPath" }
        val configParams = configPath.takeIf(String::isNotEmpty)
            ?.runCatching { readFile(drillPath, this) }
            ?.getOrNull()
            ?.let { asAgentParams(it, "\n", "#") }
        logger.info { "config parameters: $configParams" }
        val resultParams = configParams?.toMutableMap()?.also { it.putAll(agentParams) } ?: agentParams
        logger.info { "result parameters: $resultParams" }
        return resultParams.validate()
    }

    private fun asAgentParams(
        input: String?,
        lineDelimiter: String = ",",
        filterPrefix: String = "",
        mapDelimiter: String = "="
    ): Map<String, String> {
        if (input.isNullOrEmpty()) return emptyMap()
        try {
            return input.split(lineDelimiter)
                .filter { it.isNotEmpty() && (filterPrefix.isEmpty() || !it.startsWith(filterPrefix)) }
                .associate { it.substringBefore(mapDelimiter) to it.substringAfter(mapDelimiter, "") }
        } catch (parseException: Exception) {
            throw IllegalArgumentException("wrong agent parameters: $input")
        }
    }

    private fun readFile(path: String, file: String): String {
        val isFilename: (String) -> Boolean = { Regex("[\\w\\-.]+").matches(it) }
        var fileDescriptor = open(file, O_RDONLY)
        if (fileDescriptor == -1 && isFilename(file))
            fileDescriptor = open(path + pathSeparator + file, EROFS)
        if (fileDescriptor == -1)
            throw IllegalArgumentException("Cannot open the config file with path=$path, file=$file")
        val bytes = Input(fileDescriptor).readBytes()
        return bytes.decodeToString()
    }

    private fun callbackRegister() = memScoped {
        val alloc = alloc<jvmtiEventCallbacks>()
        alloc.VMInit = staticCFunction(::vmInitEvent)
        alloc.VMDeath = staticCFunction(::vmDeathEvent)
        alloc.ClassFileLoadHook = staticCFunction(::classFileLoadHook)
        SetEventCallbacks(alloc.ptr, sizeOf<jvmtiEventCallbacks>().toInt())
        SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, null)
    }

    private fun Map<String, String>.validate(): Map<String, String> = apply {
        check("agentId" in this) { "Please set 'agentId' as agent parameters e.g. -agentpath:/path/to/agent=agentId={your ID}" }
        val adminAddress = get("adminAddress")
        checkNotNull(adminAddress) { "Please set 'adminAddress' as agent parameters e.g. -agentpath:/path/to/agent=adminAddress={hostname:port}" }
        try {
            URL(adminAddress)
        } catch (parseException: RuntimeException) {
            fail("Please check 'adminAddress' parameter. It should be a valid address to the admin service without any additional paths, e.g. ws://localhost:8090")
        }
    }

}
