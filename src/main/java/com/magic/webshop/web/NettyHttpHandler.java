package com.magic.webshop.web;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

/**
 * Serves web-shop HTTP requests that arrived on the shared game port. Routing
 * runs on a worker executor (never the Netty event loop) because it can block
 * on the main thread and on peer servers.
 */
public class NettyHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final RequestRouter router;
    private final ExecutorService worker;
    private final Logger logger;

    public NettyHttpHandler(RequestRouter router, ExecutorService worker, Logger logger) {
        this.router = router;
        this.worker = worker;
        this.logger = logger;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        final String method = req.method().name();
        final String uri = req.uri();
        int q = uri.indexOf('?');
        final String path = q >= 0 ? uri.substring(0, q) : uri;
        final Map<String, String> query = RequestRouter.parseQuery(q >= 0 ? uri.substring(q + 1) : null);

        final Map<String, String> headers = new HashMap<>();
        req.headers().forEach(e -> headers.put(e.getKey().toLowerCase(), e.getValue()));

        byte[] body = new byte[req.content().readableBytes()];
        req.content().readBytes(body);
        final byte[] bodyFinal = body;

        worker.submit(() -> {
            RequestRouter.Response r;
            try {
                r = router.handle(method, path, query, headers, bodyFinal);
            } catch (Exception e) {
                logger.warning("Netty HTTP route error: " + e.getMessage());
                r = new RequestRouter.Response(500, "text/plain", "error".getBytes(StandardCharsets.UTF_8));
            }
            write(ctx, r);
        });
    }

    private void write(ChannelHandlerContext ctx, RequestRouter.Response r) {
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(r.status),
                Unpooled.wrappedBuffer(r.body));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, r.contentType);
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, r.body.length);
        resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        resp.headers().set(HttpHeaderNames.CONNECTION, "close");
        r.headers.forEach((k, v) -> resp.headers().set(k, v));
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
