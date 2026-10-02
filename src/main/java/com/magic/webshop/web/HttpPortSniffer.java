package com.magic.webshop.web;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

/**
 * Sits at the head of each new connection on the shared port. It peeks the
 * first bytes: if they look like an HTTP request it swaps the pipeline over to
 * the web handler; otherwise it removes itself and lets the Minecraft handlers
 * process the connection normally.
 */
public class HttpPortSniffer extends ByteToMessageDecoder {

    private static final String[] HTTP_METHODS = {"GET ", "POST", "PUT ", "HEAD", "OPTI", "DELE", "PATC"};

    private final RequestRouter router;
    private final ExecutorService worker;
    private final Logger logger;

    public HttpPortSniffer(RequestRouter router, ExecutorService worker, Logger logger) {
        this.router = router;
        this.worker = worker;
        this.logger = logger;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) return; // wait until we can decide

        if (looksLikeHttp(in)) {
            ChannelPipeline p = ctx.pipeline();
            String self = ctx.name();
            // remove the Minecraft handlers that sit after this sniffer
            boolean afterSelf = false;
            for (String name : new ArrayList<>(p.names())) {
                if (name.equals(self)) { afterSelf = true; continue; }
                if (!afterSelf) continue;
                try { p.remove(name); } catch (Exception ignored) { } // tail is not removable
            }
            // install HTTP handlers right after this sniffer (reverse order)
            p.addAfter(self, "mws-http-handler", new NettyHttpHandler(router, worker, logger));
            p.addAfter(self, "mws-http-agg", new HttpObjectAggregator(1024 * 1024)); // 1MB body cap
            p.addAfter(self, "mws-http-codec", new HttpServerCodec());
            // removing self makes ByteToMessageDecoder forward buffered bytes to the codec
            p.remove(self);
        } else {
            ctx.pipeline().remove(this);
        }
    }

    private boolean looksLikeHttp(ByteBuf in) {
        int base = in.readerIndex();
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4 && base + i < in.writerIndex(); i++) {
            sb.append((char) (in.getByte(base + i) & 0xFF));
        }
        String head = sb.toString();
        for (String m : HTTP_METHODS) {
            if (head.startsWith(m) || head.regionMatches(true, 0, m.trim(), 0, m.trim().length())) return true;
        }
        return false;
    }
}
