/*
 * Copyright 2018 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */

package com.netflix.zuul.netty.server;

import com.netflix.zuul.context.Debug;
import com.netflix.zuul.context.SessionContext;
import com.netflix.zuul.context.SessionContextDecorator;
import com.netflix.zuul.context.CommonContextKeys;
import com.netflix.zuul.exception.ZuulException;
import com.netflix.zuul.message.Headers;
import com.netflix.zuul.message.http.HttpQueryParams;
import com.netflix.zuul.message.http.HttpRequestMessage;
import com.netflix.zuul.message.http.HttpRequestMessageImpl;
import com.netflix.zuul.message.http.HttpResponseMessage;
import com.netflix.zuul.netty.ChannelUtils;
import com.netflix.zuul.netty.server.ssl.SslHandshakeInfoHandler;
import com.netflix.zuul.passport.CurrentPassport;
import com.netflix.zuul.passport.PassportState;
import com.netflix.zuul.stats.status.StatusCategoryUtils;
import com.netflix.zuul.stats.status.ZuulStatusCategory;
import com.netflix.zuul.util.HttpUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.unix.Errors;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import com.netflix.netty.common.SourceAddressChannelHandler;
import com.netflix.netty.common.ssl.SslHandshakeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static com.netflix.zuul.netty.server.http2.Http2OrHttpHandler.PROTOCOL_NAME;
import static com.netflix.netty.common.HttpLifecycleChannelHandler.CompleteEvent;
import static com.netflix.netty.common.HttpLifecycleChannelHandler.CompleteReason;
import static com.netflix.netty.common.HttpLifecycleChannelHandler.CompleteReason.SESSION_COMPLETE;


/**
 * Created by saroskar on 1/6/17.
 */
public class ClientRequestReceiver extends ChannelDuplexHandler {

    private final SessionContextDecorator decorator;

    private HttpRequestMessage zuulRequest;
    private HttpRequest clientRequest;

    private static final Logger LOG = LoggerFactory.getLogger(ClientRequestReceiver.class);
    private static final String SCHEME_HTTP = "http";
    private static final String SCHEME_HTTPS = "https";
    public static final AttributeKey<HttpRequestMessage> ATTR_ZUUL_REQ = AttributeKey.newInstance("_zuul_request");
    public static final AttributeKey<HttpResponseMessage> ATTR_ZUUL_RESP = AttributeKey.newInstance("_zuul_response");
    public static final AttributeKey<Boolean> ATTR_LAST_CONTENT_RECEIVED = AttributeKey.newInstance("_last_content_received");


    public ClientRequestReceiver(SessionContextDecorator decorator) {
        this.decorator = decorator;
    }

    public static HttpRequestMessage getRequestFromChannel(Channel ch) {
        return ch.attr(ATTR_ZUUL_REQ).get();
    }

    public static HttpResponseMessage getResponseFromChannel(Channel ch) {
        return ch.attr(ATTR_ZUUL_RESP).get();
    }

    public static boolean isLastContentReceivedForChannel(Channel ch) {
        Boolean value = ch.attr(ATTR_LAST_CONTENT_RECEIVED).get();
        return value == null ? false : value.booleanValue();
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {

        // Flag that we have now received the LastContent for this request from the client.
        // This is needed for ClientResponseReceiver to know whether it's yet safe to start writing
        // a response to the client channel.
        // 我们现在收到来自客户端的此请求的LastContent标记。
        // ClientResponseReceiver需要知道开始编写是否安全
        // 对客户端频道的响应。
        if (msg instanceof LastHttpContent) {
            ctx.channel().attr(ATTR_LAST_CONTENT_RECEIVED).set(Boolean.TRUE);
        }

        if (msg instanceof HttpRequest) {
            clientRequest = (HttpRequest) msg;

            // Don't process invalid requests.
            // 不处理无效请求。
            if (clientRequest.decoderResult().isFailure()) {
                String errorMsg = "Invalid http request. "
                        + "clientRequest = " + clientRequest.toString()
                        + ", uri = " + String.valueOf(clientRequest.uri())
                        + ", info = " + ChannelUtils.channelInfoForLogging(ctx.channel());
                String causeMsg = String.valueOf(clientRequest.decoderResult().cause());
                ReferenceCountUtil.release(msg);
                clientRequest = null;
                final ZuulException ze = new ZuulException(errorMsg, causeMsg, true);
                ze.setStatusCode(400);
                throw ze;
            }

            // ----------------------------关键方法-------------------------
            // 从netty请求构建ZuulMessage。
            zuulRequest = buildZuulHttpRequest(clientRequest, ctx);
            // ----------------------------关键方法-------------------------
            // 处理http 100状态
            handleExpect100Continue(ctx, clientRequest);

            // Send the request down the filter pipeline
            // 沿过滤器管道发送请求
            ctx.fireChannelRead(zuulRequest);
        }
        else if (msg instanceof HttpContent) {
            if ((zuulRequest != null) && (! zuulRequest.getContext().isCancelled())) {
                ctx.fireChannelRead(msg);
            } else {
                //We already sent response for this request, these are laggard request body chunks that are still arriving
                ReferenceCountUtil.release(msg);
            }
        }
        else if (msg instanceof HAProxyMessage) {
            // do nothing, should already be handled by ElbProxyProtocolHandler
            // 什么都不做，应该已经由ElbProxyProtocolHandler处理了
            LOG.debug("Received HAProxyMessage for Proxy Protocol IP: {}", ((HAProxyMessage) msg).sourceAddress());
            ReferenceCountUtil.release(msg);
        }
        else {
            LOG.debug("Received unrecognized message type. " + msg.getClass().getName());
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof CompleteEvent) {
            final CompleteReason reason = ((CompleteEvent) evt).getReason();
            if (zuulRequest != null) {
                zuulRequest.getContext().cancel();
                zuulRequest.disposeBufferedBody();
                final CurrentPassport passport = CurrentPassport.fromSessionContext(zuulRequest.getContext());
                if ((passport != null) && (passport.findState(PassportState.OUT_RESP_LAST_CONTENT_SENT) == null)) {
                    // Only log this state if the response does not seem to have completed normally.
                    passport.add(PassportState.IN_REQ_CANCELLED);
                }
            }

            if (reason != SESSION_COMPLETE && zuulRequest != null) {
                final SessionContext zuulCtx = zuulRequest.getContext();
                if (clientRequest != null) {
                    if (LOG.isInfoEnabled()) {
                        // With http/2, the netty codec closes/completes the stream immediately after writing the lastcontent
                        // of response to the channel, which causes this CompleteEvent to fire before we have cleaned up state. But
                        // thats ok, so don't log in that case.
                        if (! "HTTP/2".equals(zuulRequest.getProtocol())) {
                            LOG.info("Client {} request UUID {} to {} completed with reason = {}, {}", clientRequest.method(),
                                    zuulCtx.getUUID(), clientRequest.uri(), reason.name(), ChannelUtils.channelInfoForLogging(ctx.channel()));
                        }
                    }
                }
                if (zuulCtx.debugRequest()) {
                    LOG.debug("Endpoint = {}", zuulCtx.getEndpoint());
                    dumpDebugInfo(Debug.getRequestDebug(zuulCtx));
                    dumpDebugInfo(Debug.getRoutingDebug(zuulCtx));
                }
            }

            clientRequest = null;
            zuulRequest = null;
        }

        super.userEventTriggered(ctx, evt);

        if (evt instanceof  CompleteEvent) {
            final Channel channel = ctx.channel();
            channel.attr(ATTR_ZUUL_REQ).set(null);
            channel.attr(ATTR_ZUUL_RESP).set(null);
            channel.attr(ATTR_LAST_CONTENT_RECEIVED).set(null);
        }
    }

    private static void dumpDebugInfo(final List<String> debugInfo) {
        debugInfo.forEach((dbg) -> LOG.debug(dbg));
    }

    private void handleExpect100Continue(ChannelHandlerContext ctx, HttpRequest req) {
        if (HttpUtil.is100ContinueExpected(req)) {
            // http 状态为100
            final ChannelFuture f = ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
            f.addListener((s) -> {
                if (! s.isSuccess()) {
                    throw new ZuulException( s.cause(), "Failed while writing 100-continue response", true);
                }
            });
            // Remove the Expect: 100-Continue header from request as we don't want to proxy it downstream.
            // 从请求中删除Expect：100-Continue标头，因为我们不想在下游代理它。
            req.headers().remove(HttpHeaderNames.EXPECT);
            zuulRequest.getHeaders().remove(HttpHeaderNames.EXPECT.toString());
        }
    }

    // Build a ZuulMessage from the netty request.
    // 从netty请求构建ZuulMessage。
    private HttpRequestMessage buildZuulHttpRequest(final HttpRequest nativeRequest, final ChannelHandlerContext clientCtx) {
        // Setup the context for this request.
        // 为此请求设置上下文。
        final SessionContext context;
        if (decorator != null) {
            // Optionally decorate the context.
            // 可选择装饰上下文。
            SessionContext tempContext = new SessionContext();
            // Store the netty channel in SessionContext.
            // 在NETContext中存储netty频道。
            tempContext.set(CommonContextKeys.NETTY_SERVER_CHANNEL_HANDLER_CONTEXT, clientCtx);
            // -----------------------关键方法--------------------------
            // 装饰sessionContext
            context = decorator.decorate(tempContext);
        }
        else {
            context = new SessionContext();
        }

        // Get the client IP (ignore XFF headers at this point, as that can be app specific).
        // 获取客户端IP（此时忽略XFF标头，因为它可以是特定于应用程序的）。
        final Channel channel = clientCtx.channel();
        final String clientIp = channel.attr(SourceAddressChannelHandler.ATTR_SOURCE_ADDRESS).get();

        // This is the only way I found to get the port of the request with netty...
        // 这是我发现使用netty获取请求端口的唯一方法
        final int port = channel.attr(SourceAddressChannelHandler.ATTR_LOCAL_PORT).get();
        final String serverName = channel.attr(SourceAddressChannelHandler.ATTR_LOCAL_ADDRESS).get();

        // Store info about the SSL handshake if applicable, and choos  e the http scheme.
        // 存储有关SSL握手的信息（如果适用），然后选择http方案。
        String scheme = SCHEME_HTTP;
        final SslHandshakeInfo sslHandshakeInfo = channel.attr(SslHandshakeInfoHandler.ATTR_SSL_INFO).get();
        if (sslHandshakeInfo != null) {
            context.set(CommonContextKeys.SSL_HANDSHAKE_INFO, sslHandshakeInfo);
            scheme = SCHEME_HTTPS;
        }

        // Decide if this is HTTP/1 or HTTP/2.
        // 确定这是HTTP / 1还是HTTP / 2。
        String protocol = channel.attr(PROTOCOL_NAME).get();
        if (protocol == null) {
            protocol = nativeRequest.protocolVersion().text();
        }

        // Strip off the query from the path.
        // 从路径中删除查询。
        String path = nativeRequest.uri();
        int queryIndex = path.indexOf('?');
        if (queryIndex > -1) {
            path = path.substring(0, queryIndex);
        }

        // Setup the req/resp message objects.
        // 设置req / resp消息对象。
        final HttpRequestMessage request = new HttpRequestMessageImpl(
                context,
                protocol,
                nativeRequest.method().asciiName().toString().toLowerCase(),
                path,
                // --------------------关键方法---------------------
                // 复制查询参数
                copyQueryParams(nativeRequest),
                // --------------------关键方法---------------------
                // 复制head
                copyHeaders(nativeRequest),
                clientIp,
                scheme,
                port,
                serverName
        );

        // Try to decide if this request has a body or not based on the headers (as we won't yet have
        // received any of the content).
        // NOTE that we also later may override this if it is Chunked encoding, but we receive
        // a LastHttpContent without any prior HttpContent's.
        // 尝试根据标题决定此请求是否有正文（因为我们还没有收到任何内容）。
        // 请注意，如果它是Chunked编码，我们以后也可以覆盖它，但我们会收到
        // 没有任何先前HttpContent的LastHttpContent。
        if (HttpUtils.hasChunkedTransferEncodingHeader(request) || HttpUtils.hasNonZeroContentLengthHeader(request)) {
            request.setHasBody(true);
        }

        // Store this original request info for future reference (ie. for metrics and access logging purposes).
        // 存储此原始请求信息以供将来参考（即用于度量和访问日志记录）。
        request.storeInboundRequest();

        // Store the netty request for use later.
        // 存储netty请求以供日后使用。
        context.set(CommonContextKeys.NETTY_HTTP_REQUEST, nativeRequest);

        // Store zuul request on netty channel for later use.
        // 在网络频道上存储zuul请求供以后使用。
        channel.attr(ATTR_ZUUL_REQ).set(request);

        if (nativeRequest instanceof DefaultFullHttpRequest) {
            final ByteBuf chunk = ((DefaultFullHttpRequest) nativeRequest).content();
            request.bufferBodyContents(new DefaultLastHttpContent(chunk));
        }

        return request;
    }

    private static Headers copyHeaders(final HttpRequest req) {
        final Headers headers = new Headers();
        for (Map.Entry<String, String> entry : req.headers().entries()) {
            headers.add(entry.getKey(), entry.getValue());
        }
        return headers;
    }

    public static HttpQueryParams copyQueryParams(final HttpRequest nativeRequest) {
        final String uri = nativeRequest.uri();
        int queryStart = uri.indexOf('?');
        final String query = queryStart == -1 ? null : uri.substring(queryStart + 1);
        // ------------------------关键方法---------------------
        // 格式化http query 参数
        return HttpQueryParams.parse(query);
    }


    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof HttpResponse) {
            promise.addListener((future) -> {
                if (! future.isSuccess()) {
                    fireWriteError("response headers", future.cause(), ctx);
                }
            });
            super.write(ctx, msg, promise);
        }
        else if (msg instanceof HttpContent) {
            promise.addListener((future) -> {
                if (! future.isSuccess())  {
                    fireWriteError("response content", future.cause(), ctx);
                }
            });
            super.write(ctx, msg, promise);
        }
        else {
            //should never happen
            ReferenceCountUtil.release(msg);
            throw new ZuulException("Attempt to write invalid content type to client: "+msg.getClass().getSimpleName(), true);
        }
    }

    private void fireWriteError(String requestPart, Throwable cause, ChannelHandlerContext ctx) throws Exception {

        final String errMesg = String.format("Error writing %s to client", requestPart);

        if (cause instanceof java.nio.channels.ClosedChannelException ||
                cause instanceof Errors.NativeIoException) {
            LOG.info(errMesg + " - client connection is closed.");
            if (zuulRequest != null) {
                zuulRequest.getContext().cancel();
                StatusCategoryUtils.storeStatusCategoryIfNotAlreadyFailure(zuulRequest.getContext(), ZuulStatusCategory.FAILURE_CLIENT_CANCELLED);
            }
        }
        else {
            LOG.error(errMesg, cause);
            ctx.fireExceptionCaught(new ZuulException(cause, errMesg, true));
        }
    }

}