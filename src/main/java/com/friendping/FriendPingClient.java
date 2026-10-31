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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
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
    /** Сколько живёт метка (мс). */
    private static final long LIFETIME_MS = 20_000;
    /** Пауза между метками, чтобы не словить антиспам сервера. */
    private static final long COOLDOWN_MS = 3_000;
    /** Команда личных сообщений на сервере (без слэша). */
    private static final String MSG_CMD = "msg";

    /** Цвет серого неона (RGB). */
    private static final int NEON = 0xC8D2DC;

    private static final String NUM = "(-?\\d+\\.\\d+)";
    private static final String DIM = "([a-z0-9_.\\-]+)";
    // метка на блок: @fp:x:y:z:dim
    private static final Pattern TOKEN_BLOCK = Pattern.compile("@fp:(-?\\d+):(-?\\d+):(-?\\d+):" + DIM);
    // метка на игрока: @fq:uuid:x:y:z:bodyYaw:headYaw:pitch:POSE:dim
    private static final Pattern TOKEN_PLAYER = Pattern.compile(
            "@fq:([0-9a-f\\-]{36}):" + NUM + ":" + NUM + ":" + NUM + ":" + NUM + ":" + NUM + ":" + NUM + ":([A-Z_]+):" + DIM);

    private record Ping(Vec3d pos, String dim, String sender, String key, long expires, Entity ghost) {}
    private static final List<Ping> PINGS = new ArrayList<>();
    private static long lastSent = 0;
    private static ClientWorld lastWorld = null;
    private static int nextGhostId = -1000;

    @Override
    public void onInitializeClient() {
        FriendList.load();

        // /fp add <ник>, /fp remove <ник>, /fp list
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

        // СКМ: забираем нажатие до ванильной обработки, "взять блок" больше не работает.
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.world != lastWorld) { // сменился мир/измерение: копии игроков исчезли вместе с ним
                PINGS.clear();
                lastWorld = client.world;
            }
            if (client.options != null) {
                while (client.options.pickItemKey.wasPressed()) {
                    if (client.player == null || client.world == null) continue;
                    long now = System.currentTimeMillis();
                    if (now - lastSent < COOLDOWN_MS) continue;
                    placeMarker(client);
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

        // Входящие сообщения с кодом метки: прячем из чата и добавляем метку
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> !handleIncoming(message.getString()));
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, ts) -> !handleIncoming(message.getString()));

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> renderHud(ctx));
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
            addPing(new Vec3d(x, y + 2.4, z), dim, "ты", "p:" + id, ghost);
            String code = String.format(Locale.ROOT, "@fq:%s:%.2f:%.2f:%.2f:%.2f:%.2f:%.2f:%s:%s",
                    id, x, y, z, by, hy, pitch, pose, dim);
            sendToFriends(client, code);
        } else if (bh.getType() == HitResult.Type.BLOCK) {
            lastSent = System.currentTimeMillis();
            BlockPos p = ((BlockHitResult) bh).getBlockPos();
            addPing(new Vec3d(p.getX() + 0.5, p.getY() + 1.5, p.getZ() + 0.5), dim, "ты",
                    "b:" + p.getX() + ":" + p.getY() + ":" + p.getZ(), null);
            sendToFriends(client, "@fp:" + p.getX() + ":" + p.getY() + ":" + p.getZ() + ":" + dim);
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

    // ---------- застывшая копия игрока ----------

    private static Entity spawnGhost(MinecraftClient mc, UUID target, double x, double y, double z,
                                     float bodyYaw, float headYaw, float pitch, String pose) {
        if (mc.world == null) return null;
        PlayerListEntry entry = mc.getNetworkHandler() != null ? mc.getNetworkHandler().getPlayerListEntry(target) : null;
        GameProfile profile = entry != null ? entry.getProfile() : new GameProfile(target, "Player");

        FrozenPlayer g = new FrozenPlayer(mc.world, profile);
        g.setUuid(UUID.randomUUID()); // чтобы не конфликтовать с настоящим игроком в мире
        g.setId(nextGhostId--);
        g.refreshPositionAndAngles(x, y, z, bodyYaw, pitch);
        g.bodyYaw = bodyYaw;
        g.prevBodyYaw = bodyYaw;
        g.headYaw = headYaw;
        g.prevHeadYaw = headYaw;
        try { g.setPose(EntityPose.valueOf(pose)); } catch (IllegalArgumentException ignored) {}
        g.setGlowing(true); // ванильное свечение контуром, видно сквозь стены
        g.noClip = true;
        g.setNoGravity(true);
        mc.world.addEntity(g);
        return g;
    }

    private static void removeGhost(Ping p) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (p.ghost() != null && mc.world != null) {
            mc.world.removeEntity(p.ghost().getId(), Entity.RemovalReason.DISCARDED);
        }
    }

    // ---------- приём ----------

    /** true = сообщение служебное, его надо скрыть из чата. */
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
                    addPing(new Vec3d(x, y + 2.4, z), dim, friend, "p:" + id, ghost);
                    beep(mc);
                });
            }
            return true;
        }

        Matcher mb = TOKEN_BLOCK.matcher(text);
        if (mb.find()) {
            String friend = FriendList.findIn(text);
            if (friend != null) {
                int x = Integer.parseInt(mb.group(1)), y = Integer.parseInt(mb.group(2)), z = Integer.parseInt(mb.group(3));
                String dim = mb.group(4);
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> {
                    addPing(new Vec3d(x + 0.5, y + 1.5, z + 0.5), dim, friend, "b:" + x + ":" + y + ":" + z, null);
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

    private static void addPing(Vec3d pos, String dim, String sender, String key, Entity ghost) {
        Iterator<Ping> it = PINGS.iterator();
        while (it.hasNext()) {
            Ping old = it.next();
            if (old.key().equals(key) && old.dim().equals(dim)) {
                removeGhost(old);
                it.remove();
            }
        }
        PINGS.add(new Ping(pos, dim, sender, key, System.currentTimeMillis() + LIFETIME_MS, ghost));
    }

    // ---------- HUD ----------

    private static void renderHud(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (PINGS.isEmpty() || mc.world == null || mc.player == null || mc.options.hudHidden) return;

        String dim = dimOf(mc);
        Camera cam = mc.gameRenderer.getCamera();
        Vec3d cpos = cam.getPos();
        float yaw = cam.getYaw(), pitch = cam.getPitch();
        Vec3d f = Vec3d.fromPolar(pitch, yaw);
        Vec3d u = Vec3d.fromPolar(pitch - 90f, yaw);
        Vec3d r = f.crossProduct(u);

        int W = ctx.getScaledWindowWidth();
        int H = ctx.getScaledWindowHeight();
        double fov = Math.toRadians(mc.options.getFov().getValue());
        double scale = (H / 2.0) / Math.tan(fov / 2.0);

        for (Ping p : new ArrayList<>(PINGS)) {
            if (!p.dim().equals(dim)) continue;

            Vec3d d = p.pos().subtract(cpos);
            double x = d.dotProduct(r);
            double y = d.dotProduct(u);
            double z = d.dotProduct(f);

            if (z > 0.05) {
                double sx = W / 2.0 + x / z * scale;
                double sy = H / 2.0 - y / z * scale;
                int m = 14;
                if (sx >= m && sx <= W - m && sy >= m && sy <= H - m) {
                    drawIcon(ctx, mc, (int) sx, (int) sy, p.sender());
                    continue;
                }
            }

            double dx = x, dy = -y;
            double len = Math.hypot(dx, dy);
            if (len < 1e-6) { dx = 0; dy = 1; len = 1; }
            dx /= len; dy /= len;
            double mx = W / 2.0 - 20, my = H / 2.0 - 20;
            double t = Math.min(Math.abs(dx) > 1e-6 ? mx / Math.abs(dx) : 1e9,
                                Math.abs(dy) > 1e-6 ? my / Math.abs(dy) : 1e9);
            double ex = W / 2.0 + dx * t;
            double ey = H / 2.0 + dy * t;
            drawArrow(ctx, ex, ey, Math.atan2(dy, dx));
            ctx.drawCenteredTextWithShadow(mc.textRenderer, p.sender(), (int) ex, (int) ey + 11, 0xFFE6ECF0);
        }
    }

    private static int argb(int a, int rgb) { return (a << 24) | rgb; }

    private static void diamond(DrawContext ctx, int cx, int cy, int rad, int color) {
        for (int i = -rad; i <= rad; i++) {
            int w = rad - Math.abs(i);
            ctx.fill(cx - w, cy + i, cx + w + 1, cy + i + 1, color);
        }
    }

    /** Серый неон: слои свечения с убывающей прозрачностью + яркое ядро. */
    private static void drawIcon(DrawContext ctx, MinecraftClient mc, int cx, int cy, String name) {
        diamond(ctx, cx, cy, 16, argb(0x12, NEON));
        diamond(ctx, cx, cy, 14, argb(0x1E, NEON));
        diamond(ctx, cx, cy, 12, argb(0x30, NEON));
        diamond(ctx, cx, cy, 10, argb(0x4A, NEON));
        diamond(ctx, cx, cy, 8, argb(0x70, NEON));
        diamond(ctx, cx, cy, 6, argb(0xFF, 0xDDE4EA));
        diamond(ctx, cx, cy, 3, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(mc.textRenderer, name, cx, cy + 19, 0xFFE6ECF0);
    }

    private static void drawArrow(DrawContext ctx, double ex, double ey, double angle) {
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate((float) ex, (float) ey, 0f);
        m.multiply(RotationAxis.POSITIVE_Z.rotation((float) angle));
        int[] pads = {5, 4, 3, 2, 1};
        int[] alphas = {0x14, 0x1E, 0x2E, 0x45, 0x66};
        for (int k = 0; k < pads.length; k++) { // свечение
            int pad = pads[k];
            for (int i = 0; i < 10; i++) {
                int hh = 5 - i / 2;
                ctx.fill(i - 5 - pad, -hh - pad, i - 4 + pad, hh + 1 + pad, argb(alphas[k], NEON));
            }
        }
        for (int i = 0; i < 10; i++) { // ядро
            int hh = 5 - i / 2;
            ctx.fill(i - 5, -hh, i - 4, hh + 1, argb(0xFF, 0xE6ECF0));
        }
        m.pop();
    }
}
