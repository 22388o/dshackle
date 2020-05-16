/**
 * Copyright (c) 2020 EmeraldPay, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.emeraldpay.dshackle.upstream.rpcclient

import com.fasterxml.jackson.databind.ObjectMapper
import io.emeraldpay.dshackle.config.AuthConfig
import io.emeraldpay.dshackle.reader.Reader
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaders
import io.netty.handler.ssl.SslContextBuilder
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import reactor.netty.tcp.SslProvider
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*
import java.util.function.Consumer

/**
 * JSON RPC client
 */
class JsonRpcHttpClient(
        private val target: String,
        private val objectMapper: ObjectMapper,
        basicAuth: AuthConfig.ClientBasicAuth? = null,
        tlsCAAuth: ByteArray? = null
) : Reader<JsonRpcRequest, JsonRpcResponse> {

    companion object {
        private val log = LoggerFactory.getLogger(JsonRpcHttpClient::class.java)
    }

    private val parser = JsonRpcParser()
    private val httpClient: HttpClient

    init {
        var build = HttpClient.create()

        build = build.headers { h ->
            h.add(HttpHeaderNames.CONTENT_TYPE, "application/json")
        }

        basicAuth?.let { auth ->
            val authString: String = auth.username + ":" + auth.password
            val authBase64 = Base64.getEncoder().encodeToString(authString.toByteArray())
            val encodedAuth = "Basic $authBase64"
            val headers = Consumer { h: HttpHeaders -> h.add(HttpHeaderNames.AUTHORIZATION, encodedAuth) }
            build = build.headers(headers)
        }

        tlsCAAuth?.let { auth ->
            val cf = CertificateFactory.getInstance("X.509")
            val cert = cf.generateCertificate(ByteArrayInputStream(auth)) as X509Certificate
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, "".toCharArray())
            ks.setCertificateEntry("server", cert)
            val sslContext = SslContextBuilder.forClient().trustManager(cert).build()

            build.secure { spec ->
                spec.sslContext(sslContext)
            }
        }

        this.httpClient = build
    }

    fun execute(request: ByteArray): Mono<ByteArray> {
        val response = httpClient
                .post()
                .uri(target)
                .send(Mono.just(request).map { Unpooled.wrappedBuffer(it) })

        return response.responseContent()
                .aggregate()
                .asByteArray()
    }

    override fun read(key: JsonRpcRequest): Mono<JsonRpcResponse> {
        return Mono.just(key)
                .map { it.toJson(objectMapper) }
                .flatMap(this@JsonRpcHttpClient::execute)
                .map(parser::parse)
    }
}