/*
 * Copyright (c) 2020. Eugen Covaci
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.kpax.winfoom.proxy;

import org.apache.hc.client5.http.AuthenticationStrategy;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.RouteInfo.LayerType;
import org.apache.hc.client5.http.RouteInfo.TunnelType;
import org.apache.hc.client5.http.auth.*;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultAuthenticationStrategy;
import org.apache.hc.client5.http.impl.auth.*;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.io.ManagedHttpClientConnection;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.protocol.RequestClientConnControl;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.config.CharCodingConfig;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.impl.DefaultConnectionReuseStrategy;
import org.apache.hc.core5.http.impl.io.HttpRequestExecutor;
import org.apache.hc.core5.http.io.HttpConnectionFactory;
import org.apache.hc.core5.http.io.entity.BufferedHttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.http.protocol.*;
import org.apache.hc.core5.util.Args;
import org.kpax.winfoom.exception.TunnelRefusedHttpException;
import org.kpax.winfoom.util.LocalIOUtils;

import java.io.IOException;
import java.net.Socket;

/**
 * This class can be used to establish a tunnel via an HTTP proxy.<br>
 * It is an adaptation of {@link org.apache.hc.client5.http.impl.classic.ProxyClient}
 *
 * @author Eugen Covaci
 */
public class ProxyClient {

    private final HttpConnectionFactory<ManagedHttpClientConnection> connFactory;
    private final RequestConfig requestConfig;
    private final HttpProcessor httpProcessor;
    private final HttpRequestExecutor requestExec;
    private final AuthenticationStrategy proxyAuthStrategy;
    private final HttpAuthenticator authenticator;
    private final AuthExchange proxyAuthExchange;
    private final Lookup<AuthSchemeFactory> authSchemeRegistry;
    private final ConnectionReuseStrategy reuseStrategy;

    /**
     * @since 5.0
     */
    public ProxyClient(
            final HttpConnectionFactory<ManagedHttpClientConnection> connFactory,
            final Http1Config h1Config,
            final CharCodingConfig charCodingConfig,
            final RequestConfig requestConfig) {
        this.connFactory = connFactory != null ? connFactory : new ManagedHttpClientConnectionFactory(h1Config, charCodingConfig, null, null);
        this.requestConfig = requestConfig != null ? requestConfig : RequestConfig.DEFAULT;
        this.httpProcessor = new DefaultHttpProcessor(
                new RequestTargetHost(), new RequestClientConnControl(), new RequestUserAgent());
        this.requestExec = new HttpRequestExecutor();
        this.proxyAuthStrategy = new DefaultAuthenticationStrategy();
        this.authenticator = new HttpAuthenticator();
        this.proxyAuthExchange = new AuthExchange();
        this.authSchemeRegistry = RegistryBuilder.<AuthSchemeFactory>create()
                .register(StandardAuthScheme.BASIC, BasicSchemeFactory.INSTANCE)
                .register(StandardAuthScheme.DIGEST, DigestSchemeFactory.INSTANCE)
                .register(StandardAuthScheme.NTLM, NTLMSchemeFactory.INSTANCE)
                .register(StandardAuthScheme.SPNEGO, SPNegoSchemeFactory.DEFAULT)
                .register(StandardAuthScheme.KERBEROS, KerberosSchemeFactory.DEFAULT)
                .build();
        this.reuseStrategy = new DefaultConnectionReuseStrategy();
    }

    public ProxyClient(final RequestConfig requestConfig) {
        this(null, null, null, requestConfig);
    }

    public ProxyClient() {
        this(null, null, null, null);
    }

    public Tunnel tunnel(
            final HttpHost proxy,
            final HttpHost target) throws IOException, HttpException {
        Args.notNull(proxy, "Proxy host");
        Args.notNull(target, "Target host");
        HttpHost host = target;
        if (host.getPort() <= 0) {
            host = new HttpHost(host.getSchemeName(), host.getHostName(), 80);
        }
        final HttpRoute route = new HttpRoute(
                host,
                null,
                proxy, false, TunnelType.TUNNELLED, LayerType.PLAIN);

        final ManagedHttpClientConnection conn = this.connFactory.createConnection(null);
        final HttpContext context = new BasicHttpContext();
        ClassicHttpResponse response;

        final ClassicHttpRequest connect = new BasicClassicHttpRequest("CONNECT", host.toHostString());

        final BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
       // credsProvider.setCredentials(new AuthScope(proxy), credentials);

        // Populate the execution context
        context.setAttribute(HttpCoreContext.HTTP_REQUEST, connect);
        context.setAttribute(HttpClientContext.HTTP_ROUTE, route);
        context.setAttribute(HttpClientContext.CREDS_PROVIDER, credsProvider);
        context.setAttribute(HttpClientContext.AUTHSCHEME_REGISTRY, this.authSchemeRegistry);
        context.setAttribute(HttpClientContext.REQUEST_CONFIG, this.requestConfig);

        this.requestExec.preProcess(connect, this.httpProcessor, context);

        while (true) {
            if (!conn.isOpen()) {
                final Socket socket = new Socket(proxy.getHostName(), proxy.getPort());
                conn.bind(socket);
            }

            this.authenticator.addAuthResponse(proxy, ChallengeType.PROXY, connect, this.proxyAuthExchange, context);

            response = this.requestExec.execute(connect, conn, context);

            final int status = response.getCode();
            if (status < 200) {
                throw new HttpException("Unexpected response to CONNECT request: " + response);
            }
            if (this.authenticator.isChallenged(proxy, ChallengeType.PROXY, response, this.proxyAuthExchange, context)) {
                if (this.authenticator.updateAuthState(proxy, ChallengeType.PROXY, response,
                        this.proxyAuthStrategy, this.proxyAuthExchange, context)) {
                    // Retry request
                    if (this.reuseStrategy.keepAlive(connect, response, context)) {
                        // Consume response content
                        final HttpEntity entity = response.getEntity();
                        EntityUtils.consume(entity);
                    } else {
                        LocalIOUtils.close(conn);
                    }
                    // discard previous auth header
                    connect.removeHeaders(HttpHeaders.PROXY_AUTHORIZATION);
                } else {
                    break;
                }
            } else {
                break;
            }
        }

        final int status = response.getCode();

        if (status > 299) {

            // Buffer response content
            final HttpEntity entity = response.getEntity();
            if (entity != null) {
                response.setEntity(new BufferedHttpEntity(entity));
            }
            LocalIOUtils.close(conn);
            throw new TunnelRefusedHttpException("CONNECT refused by proxy: " + new StatusLine(response), response);
        }

        return new Tunnel(conn, response);
    }

}
