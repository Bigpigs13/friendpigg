package com.friendping;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FriendPingClient implements ClientModInitializer {
    /** Сколько живёт метка (мс). */
    private static final long LIFETIME_MS = 20_000;
    /** Пауза между метками, чтобы не словить антиспам сервера. */
    private static final long COOLDOWN_MS = 3_000;
    /** Команда личных сообщений на сервере (без слэша). */
    private static final String MSG_CMD = "msg";

    private static final Pattern TOKEN = Pattern.compile("@fp:(-?\\d+):(-?\\d+):(-?\\d+):([a-z_]+)");

    private record Ping(BlockPos pos, String dim, String sender, long expires) {}
    private static final List<Ping> PINGS = new ArrayList<>();
    private static long lastSent = 0;

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

        // СКМ (клавиша "взять блок"). Забираем нажатие в начале тика, до ванильной обработки,
        // поэтому взятие блока больше не срабатывает.
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.options != null) {
                while (client.options.pickItemKey.wasPressed()) {
                    if (client.player == null || client.world == null) continue;
                    long now = System.currentTimeMillis();
                    if (now - lastSent < COOLDOWN_MS) continue;
                    HitResult hit = client.player.raycast(256.0, 1.0f, false);
                    if (hit.getType() != HitResult.Type.BLOCK) continue;
                    BlockPos p = ((BlockHitResult) hit).getBlockPos();
                    lastSent = now;
                    String dim = dimOf(client);
                    addPing(p, dim, "ты");
                    String code = "@fp:" + p.getX() + ":" + p.getY() + ":" + p.getZ() + ":" + dim;
                    for (String friend : FriendList.all()) {
                        client.player.networkHandler.sendChatCommand(MSG_CMD + " " + friend + " " + code);
                    }
                }
            }
            long now = System.currentTimeMillis();
            PINGS.removeIf(pg -> pg.expires() < now);
        });

        // Входящие сообщения с кодом метки: прячем из чата и добавляем метку
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> !handleIncoming(message.getString()));
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, ts) -> !handleIncoming(message.getString()));

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> renderHud(ctx));
    }

    private static String dimOf(MinecraftClient mc) {
        return mc.world.getRegistryKey().getValue().getPath();
    }

    /** true = сообщение служебное, его надо скрыть из чата. */
    private static boolean handleIncoming(String text) {
        Matcher m = TOKEN.matcher(text);
        if (!m.find()) return false;
        String friend = FriendList.findIn(text);
        if (friend != null) {
            BlockPos pos = new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            String dim = m.group(4);
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                addPing(pos, dim, friend);
                if (mc.player != null) {
                    mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.0f, 1.5f);
                }
            });
        }
        return true;
    }

    private static void addPing(BlockPos pos, String dim, String sender) {
        PINGS.removeIf(p -> p.pos().equals(pos) && p.dim().equals(dim));
        PINGS.add(new Ping(pos, dim, sender, System.currentTimeMillis() + LIFETIME_MS));
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

            Vec3d d = new Vec3d(p.pos().getX() + 0.5 - cpos.x, p.pos().getY() + 1.5 - cpos.y, p.pos().getZ() + 0.5 - cpos.z);
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

            // метка вне экрана: стрелка у края в её сторону
            double dx = x, dy = -y;
            double len = Math.hypot(dx, dy);
            if (len < 1e-6) { dx = 0; dy = 1; len = 1; }
            dx /= len; dy /= len;
            double mx = W / 2.0 - 18, my = H / 2.0 - 18;
            double t = Math.min(Math.abs(dx) > 1e-6 ? mx / Math.abs(dx) : 1e9,
                                Math.abs(dy) > 1e-6 ? my / Math.abs(dy) : 1e9);
            double ex = W / 2.0 + dx * t;
            double ey = H / 2.0 + dy * t;
            drawArrow(ctx, ex, ey, Math.atan2(dy, dx));
            ctx.drawCenteredTextWithShadow(mc.textRenderer, p.sender(), (int) ex, (int) ey + 9, 0xFFFFFFFF);
        }
    }

    private static void diamond(DrawContext ctx, int cx, int cy, int rad, int color) {
        for (int i = -rad; i <= rad; i++) {
            int w = rad - Math.abs(i);
            ctx.fill(cx - w, cy + i, cx + w + 1, cy + i + 1, color);
        }
    }

    private static void drawIcon(DrawContext ctx, MinecraftClient mc, int cx, int cy, String name) {
        diamond(ctx, cx, cy, 8, 0xFF000000);
        diamond(ctx, cx, cy, 6, 0xFF00FFFF);
        ctx.drawCenteredTextWithShadow(mc.textRenderer, name, cx, cy + 11, 0xFFFFFFFF);
    }

    private static void drawArrow(DrawContext ctx, double ex, double ey, double angle) {
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate((float) ex, (float) ey, 0f);
        m.multiply(RotationAxis.POSITIVE_Z.rotation((float) angle));
        for (int i = 0; i < 10; i++) { // чёрная обводка
            int hh = 5 - i / 2;
            ctx.fill(i - 6, -hh - 1, i - 3, hh + 2, 0xFF000000);
        }
        for (int i = 0; i < 10; i++) {
            int hh = 5 - i / 2;
            ctx.fill(i - 5, -hh, i - 4, hh + 1, 0xFF00FFFF);
        }
        m.pop();
    }
}
