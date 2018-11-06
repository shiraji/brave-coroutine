package com.github.shiraji.bravecoroutine

import brave.Tracing
import brave.context.slf4j.MDCScopeDecorator
import brave.http.HttpTracing
import brave.httpclient.TracingHttpClientBuilder
import brave.propagation.B3Propagation
import brave.propagation.ExtraFieldPropagation
import brave.propagation.ThreadLocalCurrentTraceContext
import brave.servlet.TracingFilter
import brave.spring.webmvc.SpanCustomizingAsyncHandlerInterceptor
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.web.client.RestTemplateCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import zipkin2.Span
import zipkin2.reporter.AsyncReporter
import zipkin2.reporter.Sender
import zipkin2.reporter.okhttp3.OkHttpSender
import javax.servlet.Filter

@SpringBootApplication
class BraveCoroutineApplication

fun main(args: Array<String>) {
    runApplication<BraveCoroutineApplication>(*args)
}

@RestController
class Rest {

    @GetMapping("api")
    fun get() {
        println("1: ${Thread.currentThread()}")
        GlobalScope.launch {
            println("2: ${Thread.currentThread()}")
            Thread.sleep(1000)
        }
        Thread.sleep(2000)
        println("3: ${Thread.currentThread()}")
    }

}

/**
 * This adds tracing configuration to any web mvc controllers or rest template clients.
 */
@Configuration
// Importing a class is effectively the same as declaring bean methods
@Import(SpanCustomizingAsyncHandlerInterceptor::class)
class TracingConfiguration : WebMvcConfigurer {

    @Autowired
    var webMvcTracingCustomizer: SpanCustomizingAsyncHandlerInterceptor? = null

    /** Configuration for how to send spans to Zipkin  */
    @Bean
    fun sender(): Sender {
        return OkHttpSender.create("http://127.0.0.1:9411/api/v2/spans")
    }

    /** Configuration for how to buffer spans into messages for Zipkin  */
    @Bean
    fun spanReporter(): AsyncReporter<Span> {
        return AsyncReporter.create(sender())
    }

    /** Controls aspects of tracing such as the name that shows up in the UI  */
    @Bean
    fun tracing(@Value("backend") serviceName: String): Tracing {
        return Tracing.newBuilder()
                .localServiceName(serviceName)
                .propagationFactory(ExtraFieldPropagation.newFactory(B3Propagation.FACTORY, "user-name"))
                .currentTraceContext(ThreadLocalCurrentTraceContext.newBuilder()
                        .addScopeDecorator(MDCScopeDecorator.create()) // puts trace IDs into logs
                        .build()
                )
                .spanReporter(spanReporter()).build()
    }

    /** decides how to name and tag spans. By default they are named the same as the http method.  */
    @Bean
    fun httpTracing(tracing: Tracing): HttpTracing {
        return HttpTracing.create(tracing)
    }

    /** Creates server spans for http requests  */
    @Bean
    fun tracingFilter(httpTracing: HttpTracing): Filter {
        return TracingFilter.create(httpTracing)
    }

    @Bean
    fun useTracedHttpClient(httpTracing: HttpTracing): RestTemplateCustomizer {
        val httpClient = TracingHttpClientBuilder.create(httpTracing).build()
        return RestTemplateCustomizer { restTemplate -> restTemplate.requestFactory = HttpComponentsClientHttpRequestFactory(httpClient) }
    }

    /** Decorates server spans with application-defined web tags  */
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(webMvcTracingCustomizer!!)
    }
}
