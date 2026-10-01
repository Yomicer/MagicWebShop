package com.magic.webshop.web;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * OPTIONAL / ADVANCED: serves the web shop on the SAME TCP port the Minecraft
 * server already listens on, by injecting a byte sniffer into Paper's Netty
 * pipeline. New connections whose first bytes look like HTTP are handed to the
 * web handler; everything else passes through to Minecraft untouched.
 *
 * <p>This uses server internals via reflection, so it is version-sensitive and
 * fails safe: if anything goes wrong it returns false and the caller falls back
 * to the standalone web port.
 */
public class NettyPortSharer {

    private final JavaPlugin plugin;
    private final RequestRouter router;
    private final Logger logger;
    private final ExecutorService worker = Executors.newFixedThreadPool(8);

    private final List<Channel> injectedChannels = new ArrayList<>();
    private Injector injector;

    public NettyPortSharer(JavaPlugin plugin, RequestRouter router, Logger logger) {
        this.plugin = plugin;
        this.router = router;
        this.logger = logger;
    }

    public boolean enable() {
        try {
            Object craftServer = Bukkit.getServer();
            Object mcServer = craftServer.getClass().getMethod("getServer").invoke(craftServer);
            Object connection = findByType(mcServer, "ServerConnection");
            if (connection == null) {
                logger.warning("Port sharing: could not locate the server connection object.");
                return false;
            }
            List<?> channels = findChannelList(connection);
            if (channels == null || channels.isEmpty()) {
                logger.warning("Port sharing: no bound server channels found.");
                return false;
            }
            injector = new Injector();
            synchronized (channels) {
                for (Object o : channels) {
                    if (o instanceof ChannelFuture cf) {
                        Channel ch = cf.channel();
                        ch.pipeline().addFirst("mws-port-injector", injector);
                        injectedChannels.add(ch);
                    }
                }
            }
            if (injectedChannels.isEmpty()) return false;
            logger.info("Port sharing enabled: web shop is served on the Minecraft server port.");
            return true;
        } catch (Throwable t) {
            logger.warning("Port sharing failed to initialise (" + t + "); falling back to a separate port.");
            return false;
        }
    }

    public void disable() {
        for (Channel ch : injectedChannels) {
            try {
                if (ch.pipeline().get("mws-port-injector") != null) ch.pipeline().remove("mws-port-injector");
            } catch (Throwable ignored) { }
        }
        injectedChannels.clear();
        worker.shutdownNow();
    }

    // ------------------------------------------------------------ reflection

    private Object findByType(Object owner, String simpleTypeNameContains) throws IllegalAccessException {
        Class<?> c = owner.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getSimpleName().contains(simpleTypeNameContains)) {
                    f.setAccessible(true);
                    Object v = f.get(owner);
                    if (v != null) return v;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<?> findChannelList(Object connection) throws IllegalAccessException {
        Class<?> c = connection.getClass();
        List<?> named = null;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                List<?> list = (List<?>) f.get(connection);
                if (list == null) continue;
                synchronized (list) {
                    if (!list.isEmpty() && list.get(0) instanceof ChannelFuture) return list;
                }
                if (f.getName().toLowerCase().contains("channel")) named = list;
            }
            c = c.getSuperclass();
        }
        return named;
    }

    // ------------------------------------------------------------ handlers

    @ChannelHandler.Sharable
    private final class Injector extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Channel child) {
                child.pipeline().addFirst("mws-http-sniffer",
                        new HttpPortSniffer(router, worker, logger));
            }
            ctx.fireChannelRead(msg);
        }
    }
}
