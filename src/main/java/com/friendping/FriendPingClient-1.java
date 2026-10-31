package com.friendping;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FriendPingClient implements ClientModInitializer {
    private static final long LIFETIME_MS = 20_000;
    private static final long COOLDOWN_MS = 3_000;
    /** Команда личных сообщений на сервере (без слэша). */
    private static final String MSG_CMD = "msg";

    // Цвета (RGB): серый неон для блоков, кроваво-красный для игроков
    private static final int GRAY_GLOW = 0xC8D2DC, GRAY_CORE = 0xE4EAF0, GRAY_HOT = 0xFFFFFF;
    private static final int RED_GLOW = 0xB00018, RED_CORE = 0xD0102A, RED_HOT = 0xFF6A78;

    private static final String NUM = "(-?\\d+\\.\\d+)";
    private static final String DIM = "([a-z0-9_.\\-]+)";
    // блок: @fb:hx:hy:hz:SIDE:dim
    private static final Pattern TOKEN_BLOCK = Pattern.compile("@fb:" + NUM + ":" + NUM + ":" + NUM + ":([A-Z]+):" + DIM);
    // игрок: @fq:uuid:x:y:z:bodyYaw:headYaw:pitch:POSE:dim
    private static final Pattern TOKEN_PLAYER = Pattern.compile(
            "@fq:([0-9a-f\\-]{36}):" + NUM + ":" + NUM + ":" + NUM + ":" + NUM + ":" + NUM + ":" + NUM + ":([A-Z_]+):" + DIM);

    private record Ping(Vec3d icon, Vec3d ringCenter, Vec3d ringNormal, double ringRadius, boolean player,
                        String dim, String sender, String key, long expires, Entity ghost) {}

    private static final List<Ping> PINGS = new ArrayList<>();
    private static long lastSent = 0;
    private static ClientWorld lastWorld = null;
    private static int nextGhostId = -1000;

    @Override
    public void onInitializeClient() {
        FriendList.load();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("fp")
                .then(ClientCommandManager.literal("add")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(ctx -> {
                        String n = StringArgumentType.getString(ctx, "name");
                        ctx.getSource().sendFeedback(Text.literal(FriendList.add(n) ? n + " добавлен." : n + " уже в списке."));
                        return 1;
                    })))
                .then(ClientCommandManager.literal("remove")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(ctx -> {
                        String n = StringArgumentType.getString(ctx, "name");
                        ctx.getSource().sendFeedback(Text.literal(FriendList.remove(n) ? n + " удалён." : n + " не найден."));
                        return 1;
                    })))
                .then(ClientCommandManager.literal("list").executes(ctx -> {
                    var l = FriendList.all();
                    ctx.getSource().sendFeedback(Text.literal(l.isEmpty() ? "Список пуст." : "Друзья: " + String.join(", ", l)));
                    return 1;
                }))));

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.world != lastWorld) {
                PINGS.clear();
                lastWorld = client.world;
            }
            if (client.options != null) {
                while (client.options.pickItemKey.wasPressed()) {
                    if (client.player == null || client.world == null) continue;
                    long now = System.currentTimeMillis();
                    if (now - lastSent < COOLDOWN_MS) continue;
                    try {
                        placeMarker(client);
                    } catch (Throwable t) {
                        FriendPing_log("placeMarker failed", t);
                        client.player.sendMessage(Text.literal("FriendPing: ошибка " + t), true);
                    }
                }
            }
            long now = System.currentTimeMillis();
            Iterator<Ping> it = PINGS.iterator();
            while (it.hasNext()) {
                Ping p = it.next();
                if (p.expires() < now) {
                    removeGhost(p);
                    it.remove();
                }
            }
        });

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> !handleIncoming(message.getString()));
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, ts) -> !handleIncoming(message.getString()));

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            try { renderHud(ctx); } catch (Throwable t) { FriendPing_log("renderHud failed", t); }
        });
    }

    private static void FriendPing_log(String msg, Throwable t) {
        org.slf4j.LoggerFactory.getLogger("friendping").error(msg, t);
    }

    // ---------- постановка метки ----------

    private static void placeMarker(MinecraftClient client) {
        var player = client.player;
        Vec3d start = player.getCameraPosVec(1.0f);
        Vec3d dir = player.getRotationVec(1.0f);
        Vec3d end = start.add(dir.multiply(256.0));

        HitResult bh = player.raycast(256.0, 1.0f, false);
        double blockDist = bh.getType() == HitResult.Type.BLOCK ? start.squaredDistanceTo(bh.getPos()) : Double.MAX_VALUE;

        Box box = player.getBoundingBox().stretch(dir.multiply(256.0)).expand(1.0);
        EntityHitResult eh = ProjectileUtil.raycast(player, start, end, box,
                e -> e instanceof AbstractClientPlayerEntity && !(e instanceof FrozenPlayer) && !e.isSpectator(),
                256.0 * 256.0);

        String dim = dimOf(client);

        if (eh != null && start.squaredDistanceTo(eh.getPos()) < blockDist && eh.getEntity() instanceof LivingEntity t) {
            lastSent = System.currentTimeMillis();
            UUID id = t.getUuid();
            double x = t.getX(), y = t.getY(), z = t.getZ();
            float by = t.getBodyYaw(), hy = t.getHeadYaw(), pitch = t.getPitch();
            String pose = t.getPose().name();
            Entity ghost = spawnGhost(client, id, x, y, z, by, hy, pitch, pose);
            addPing(playerPing(x, y, z, dim, "ты", "p:" + id, ghost));
            player.sendMessage(Text.literal("Метка на игрока " + t.getName().getString()
                    + (ghost == null ? " (копию создать не удалось)" : "")), true);
            String code = String.format(Locale.ROOT, "@fq:%s:%.2f:%.2f:%.2f:%.2f:%.2f:%.2f:%s:%s",
                    id, x, y, z, by, hy, pitch, pose, dim);
            sendToFriends(client, code);
        } else if (bh.getType() == HitResult.Type.BLOCK) {
            lastSent = System.currentTimeMillis();
            BlockHitResult b = (BlockHitResult) bh;
            Vec3d h = b.getPos();
            Direction side = b.getSide();
            Vec3d n = new Vec3d(side.getOffsetX(), side.getOffsetY(), side.getOffsetZ());
            String key = "b:" + b.getBlockPos().getX() + ":" + b.getBlockPos().getY() + ":" + b.getBlockPos().getZ();
            addPing(blockPing(h, n, dim, "ты", key));
            player.sendMessage(Text.literal("Метка на блок"), true);
            String code = String.format(Locale.ROOT, "@fb:%.2f:%.2f:%.2f:%s:%s", h.x, h.y, h.z, side.name(), dim);
            sendToFriends(client, code);
        }
    }

    private static void sendToFriends(MinecraftClient client, String code) {
        for (String friend : FriendList.all()) {
            client.player.networkHandler.sendChatCommand(MSG_CMD + " " + friend + " " + code);
        }
    }

    private static String dimOf(MinecraftClient mc) {
        return mc.world.getRegistryKey().getValue().getPath();
    }

    private static Ping blockPing(Vec3d hit, Vec3d n, String dim, String sender, String key) {
        Vec3d c = hit.add(n.multiply(0.03));
        return new Ping(c, c, n, 0.45, false, dim, sender, key, System.currentTimeMillis() + LIFETIME_MS, null);
    }

    private static Ping playerPing(double x, double y, double z, String dim, String sender, String key, Entity ghost) {
        return new Ping(new Vec3d(x, y + 2.4, z), new Vec3d(x, y + 0.05, z), new Vec3d(0, 1, 0), 0.8, true,
                dim, sender, key, System.currentTimeMillis() + LIFETIME_MS, ghost);
    }

    // ---------- застывшая копия игрока ----------

    private static Entity spawnGhost(MinecraftClient mc, UUID target, double x, double y, double z,
                                     float bodyYaw, float headYaw, float pitch, String pose) {
        try {
            if (mc.world == null) return null;
            PlayerListEntry entry = mc.getNetworkHandler() != null ? mc.getNetworkHandler().getPlayerListEntry(target) : null;
            GameProfile profile = entry != null ? entry.getProfile() : new GameProfile(target, "Player");

            FrozenPlayer g = new FrozenPlayer(mc.world, profile);
            g.setUuid(UUID.randomUUID());
            g.setId(nextGhostId--);
            g.refreshPositionAndAngles(x, y, z, bodyYaw, pitch);
            g.bodyYaw = bodyYaw;
            g.prevBodyYaw = bodyYaw;
            g.headYaw = headYaw;
            g.prevHeadYaw = headYaw;
            try { g.setPose(EntityPose.valueOf(pose)); } catch (IllegalArgumentException ignored) {}
            g.setGlowing(true);
            g.noClip = true;
            g.setNoGravity(true);
            mc.world.addEntity(g);
            return g;
        } catch (Throwable t) {
            FriendPing_log("spawnGhost failed", t);
            return null;
        }
    }

    private static void removeGhost(Ping p) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (p.ghost() != null && mc.world != null) {
                mc.world.removeEntity(p.ghost().getId(), Entity.RemovalReason.DISCARDED);
            }
        } catch (Throwable t) {
            FriendPing_log("removeGhost failed", t);
        }
    }

    // ---------- приём ----------

    private static boolean handleIncoming(String text) {
        Matcher mp = TOKEN_PLAYER.matcher(text);
        if (mp.find()) {
            String friend = FriendList.findIn(text);
            if (friend != null) {
                UUID id = UUID.fromString(mp.group(1));
                double x = Double.parseDouble(mp.group(2));
                double y = Double.parseDouble(mp.group(3));
                double z = Double.parseDouble(mp.group(4));
                float by = Float.parseFloat(mp.group(5));
                float hy = Float.parseFloat(mp.group(6));
                float pitch = Float.parseFloat(mp.group(7));
                String pose = mp.group(8);
                String dim = mp.group(9);
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> {
                    if (mc.world == null) return;
                    Entity ghost = dim.equals(dimOf(mc)) ? spawnGhost(mc, id, x, y, z, by, hy, pitch, pose) : null;
                    addPing(playerPing(x, y, z, dim, friend, "p:" + id, ghost));
                    beep(mc);
                });
            }
            return true;
        }

        Matcher mb = TOKEN_BLOCK.matcher(text);
        if (mb.find()) {
            String friend = FriendList.findIn(text);
            if (friend != null) {
                Vec3d h = new Vec3d(Double.parseDouble(mb.group(1)), Double.parseDouble(mb.group(2)), Double.parseDouble(mb.group(3)));
                Direction side;
                try { side = Direction.valueOf(mb.group(4)); } catch (IllegalArgumentException e) { side = Direction.UP; }
                Vec3d n = new Vec3d(side.getOffsetX(), side.getOffsetY(), side.getOffsetZ());
                String dim = mb.group(5);
                String key = String.format(Locale.ROOT, "b:%d:%d:%d", (int) Math.floor(h.x), (int) Math.floor(h.y), (int) Math.floor(h.z));
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> {
                    addPing(blockPing(h, n, dim, friend, key));
                    beep(mc);
                });
            }
            return true;
        }
        return false;
    }

    private static void beep(MinecraftClient mc) {
        if (mc.player != null) mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.0f, 1.5f);
    }

    private static void addPing(Ping np) {
        Iterator<Ping> it = PINGS.iterator();
        while (it.hasNext()) {
            Ping old = it.next();
            if (old.key().equals(np.key()) && old.dim().equals(np.dim())) {
                removeGhost(old);
                it.remove();
            }
        }
        PINGS.add(np);
    }

    // ---------- HUD ----------

    private static final class Proj {
        Vec3d cam, f, u, r;
        double scale;
        int W, H;

        /** координаты точки в системе камеры: {вправо, вверх, вперёд} */
        double[] rel(Vec3d p) {
            Vec3d d = p.subtract(cam);
            return new double[]{d.dotProduct(r), d.dotProduct(u), d.dotProduct(f)};
        }

        /** экранные координаты или null, если точка позади камеры */
        double[] screen(Vec3d p) {
            double[] c = rel(p);
            if (c[2] < 0.05) return null;
            return new double[]{W / 2.0 + c[0] / c[2] * scale, H / 2.0 - c[1] / c[2] * scale};
        }
    }

    private static void renderHud(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (PINGS.isEmpty() || mc.world == null || mc.player == null || mc.options.hudHidden) return;

        Camera cam = mc.gameRenderer.getCamera();
        Proj pr = new Proj();
        pr.cam = cam.getPos();
        float yaw = cam.getYaw(), pitch = cam.getPitch();
        pr.f = Vec3d.fromPolar(pitch, yaw);
        pr.u = Vec3d.fromPolar(pitch - 90f, yaw);
        pr.r = pr.f.crossProduct(pr.u);
        pr.W = ctx.getScaledWindowWidth();
        pr.H = ctx.getScaledWindowHeight();
        double fov = Math.toRadians(mc.options.getFov().getValue());
        pr.scale = (pr.H / 2.0) / Math.tan(fov / 2.0);

        String dim = dimOf(mc);
        for (Ping p : new ArrayList<>(PINGS)) {
            if (!p.dim().equals(dim)) continue;
            int glow = p.player() ? RED_GLOW : GRAY_GLOW;
            int core = p.player() ? RED_CORE : GRAY_CORE;
            int hot = p.player() ? RED_HOT : GRAY_HOT;

            drawRing(ctx, pr, p.ringCenter(), p.ringNormal(), p.ringRadius(), glow, core);

            double[] c = pr.rel(p.icon());
            if (c[2] > 0.05) {
                double sx = pr.W / 2.0 + c[0] / c[2] * pr.scale;
                double sy = pr.H / 2.0 - c[1] / c[2] * pr.scale;
                int m = 22;
                if (sx >= m && sx <= pr.W - m && sy >= m && sy <= pr.H - m) {
                    drawDisc(ctx, (int) sx, (int) sy, glow, core, hot);
                    ctx.drawCenteredTextWithShadow(mc.textRenderer, p.sender(), (int) sx, (int) sy + 24, 0xFFE6ECF0);
                    continue;
                }
            }

            double dx = c[0], dy = -c[1];
            double len = Math.hypot(dx, dy);
            if (len < 1e-6) { dx = 0; dy = 1; len = 1; }
            dx /= len; dy /= len;
            double mx = pr.W / 2.0 - 24, my = pr.H / 2.0 - 24;
            double t = Math.min(Math.abs(dx) > 1e-6 ? mx / Math.abs(dx) : 1e9,
                                Math.abs(dy) > 1e-6 ? my / Math.abs(dy) : 1e9);
            double ex = pr.W / 2.0 + dx * t;
            double ey = pr.H / 2.0 + dy * t;
            drawArrow(ctx, ex, ey, Math.atan2(dy, dx), glow, core);
            ctx.drawCenteredTextWithShadow(mc.textRenderer, p.sender(), (int) ex, (int) ey + 12, 0xFFE6ECF0);
        }
    }

    private static int argb(int a, int rgb) { return (Math.max(0, Math.min(255, a)) << 24) | (rgb & 0xFFFFFF); }

    /** Плавный неоновый кружок: яркое ядро и мягкое свечение, плавно затухающее к краю. */
    private static void drawDisc(DrawContext ctx, int cx, int cy, int glow, int core, int hot) {
        final int R = 22;
        final double coreR = 6.0;
        for (int dy = -R; dy <= R; dy++) {
            for (int dx = -R; dx <= R; dx++) {
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d > R) continue;
                int color;
                if (d <= 3.0) {
                    color = argb(255, hot);
                } else if (d <= coreR) {
                    double k = (d - 3.0) / (coreR - 3.0);
                    color = argb(255, k < 0.5 ? hot : core);
                } else {
                    double t = (d - coreR) / (R - coreR);
                    double a = Math.pow(1.0 - t, 2.4) * 0.80;
                    color = argb((int) (a * 255), glow);
                }
                ctx.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
            }
        }
    }

    /** Кольцо, лежащее в мире (плоскость задаётся нормалью). Размер меняется с расстоянием. */
    private static void drawRing(DrawContext ctx, Proj pr, Vec3d center, Vec3d n, double radius, int glow, int core) {
        Vec3d helper = Math.abs(n.y) < 0.9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
        Vec3d t1 = n.crossProduct(helper).normalize();
        Vec3d t2 = n.crossProduct(t1).normalize();
        final int N = 72;
        double[] prev = null;
        double[] first = null;
        for (int k = 0; k <= N; k++) {
            double a = 2.0 * Math.PI * k / N;
            Vec3d pt = center.add(t1.multiply(Math.cos(a) * radius)).add(t2.multiply(Math.sin(a) * radius));
            double[] s = pr.screen(pt);
            if (s != null && prev != null) drawSegment(ctx, pr, prev[0], prev[1], s[0], s[1], glow, core);
            prev = s;
        }
    }

    private static void drawSegment(DrawContext ctx, Proj pr, double x0, double y0, double x1, double y1, int glow, int core) {
        double dx = x1 - x0, dy = y1 - y0;
        int steps = (int) Math.min(120, Math.max(1, Math.hypot(dx, dy)));
        for (int s = 0; s <= steps; s++) {
            int x = (int) (x0 + dx * s / steps);
            int y = (int) (y0 + dy * s / steps);
            if (x < -10 || y < -10 || x > pr.W + 10 || y > pr.H + 10) continue;
            ctx.fill(x - 3, y - 3, x + 4, y + 4, argb(0x08, glow));
            ctx.fill(x - 2, y - 2, x + 3, y + 3, argb(0x10, glow));
            ctx.fill(x - 1, y - 1, x + 2, y + 2, argb(0x30, glow));
            ctx.fill(x, y, x + 1, y + 1, argb(0xFF, core));
        }
    }

    private static void drawArrow(DrawContext ctx, double ex, double ey, double angle, int glow, int core) {
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate((float) ex, (float) ey, 0f);
        m.multiply(RotationAxis.POSITIVE_Z.rotation((float) angle));
        int[] pads = {6, 5, 4, 3, 2, 1};
        int[] alphas = {0x0C, 0x14, 0x20, 0x34, 0x55, 0x80};
        for (int k = 0; k < pads.length; k++) {
            int pad = pads[k];
            for (int i = 0; i < 10; i++) {
                int hh = 5 - i / 2;
                ctx.fill(i - 5 - pad, -hh - pad, i - 4 + pad, hh + 1 + pad, argb(alphas[k], glow));
            }
        }
        for (int i = 0; i < 10; i++) {
            int hh = 5 - i / 2;
            ctx.fill(i - 5, -hh, i - 4, hh + 1, argb(0xFF, core));
        }
        m.pop();
    }
}
