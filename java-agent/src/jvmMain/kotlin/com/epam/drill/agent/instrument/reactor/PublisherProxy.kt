package com.epam.drill.agent.instrument.reactor

import com.epam.drill.agent.request.RequestHolder
import com.epam.drill.common.agent.request.DrillRequest
import mu.KotlinLogging
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.implementation.bind.annotation.*
import java.lang.reflect.Method
import java.util.function.Function

private val logger = KotlinLogging.logger {}

object PublisherInterceptor {

    @RuntimeType
    fun intercept(
        @FieldValue(DRILL_DELEGATE_FIELD) target: Any,
        @FieldValue(DRILL_REQUEST_FIELD) drillRequest: DrillRequest?,
        @Origin superMethod: Method,
        @Pipe pipe: Function<Any?, Any?>,
        @AllArguments args: Array<Any?>,
    ): Any? {
        return when (superMethod.name) {
            "subscribe" -> subscribe(target, drillRequest, superMethod, pipe, args[0])
            else -> pipe.apply(target)
        }
    }

    private fun subscribe(
        target: Any,
        drillRequest: DrillRequest?,
        superMethod: Method,
        pipe: Function<Any?, Any?>,
        subscriber: Any?,
    ): Any? {
        if (subscriber == null) return pipe.apply(target)
        val currentContextMethod = subscriber.javaClass.getMethod("currentContext")
        currentContextMethod.isAccessible = true
        val context = currentContextMethod.invoke(subscriber)

        val getOrDefaultMethod = context.javaClass.getMethod("getOrDefault", Any::class.java, Any::class.java)
        getOrDefaultMethod.isAccessible = true
        val contextualDrillRequest = getOrDefaultMethod.invoke(context, "DrillRequest", null) as DrillRequest?

        val parentDrillRequest = contextualDrillRequest
            ?: drillRequest
            ?: return pipe.apply(target)

        val newContext = if (drillRequest != contextualDrillRequest) {
            val putMethod = context.javaClass.getMethod("put", Any::class.java, Any::class.java)
            putMethod.isAccessible = true
            putMethod.invoke(context, DRILL_CONTEXT, drillRequest)
        } else context

        val subscriberProxy = createProxyDelegate(
            subscriber,
            Class.forName(SUBSCRIBER_CLASS, true, target.javaClass.classLoader),
            SubscriberInterceptor,
            configure = {
                defineField(DRILL_REQUEST_FIELD, DrillRequest::class.java, Visibility.PUBLIC).defineField(
                    DRILL_CONTEXT_FIELD,
                    Object::class.java,
                    Visibility.PUBLIC
                )
            },
            initialize = { proxy, proxyType ->
                proxyType.getField(DRILL_REQUEST_FIELD).set(proxy, parentDrillRequest)
                if (newContext != null)
                    proxyType.getField(DRILL_CONTEXT_FIELD).set(proxy, newContext)
            }
        )
        return propagateDrillRequest(parentDrillRequest) {
            logger.trace { "${target.javaClass.simpleName}.${superMethod.name}():${target.hashCode()}, sessionId = ${drillRequest?.drillSessionId}" }
            superMethod.invoke(target, subscriberProxy)
        }
    }
}

object PublisherAssembler {
    @JvmStatic
    fun onAssembly(
        target: Any,
        publisherClass: Class<*>
    ): Any {
        val drillRequest = RequestHolder.retrieve()
        logger.trace { "${publisherClass.simpleName}.onAssembly(${target.javaClass.simpleName}):${target.hashCode()}, sessionId = ${drillRequest?.drillSessionId}" }
        return createProxyDelegate(
            target,
            publisherClass,
            PublisherInterceptor,
            configure = { defineField(DRILL_REQUEST_FIELD, DrillRequest::class.java, Visibility.PUBLIC) },
            initialize = { proxy, proxyType ->
                if (drillRequest != null)
                    proxyType.getField(DRILL_REQUEST_FIELD).set(proxy, drillRequest)
            }
        )
    }
}