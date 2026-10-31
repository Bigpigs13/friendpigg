package com.friendping;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;

/** Застывшая клиентская копия игрока: не тикает, не двигается, не выбирается прицелом. */
public class FrozenPlayer extends OtherClientPlayerEntity {
    public FrozenPlayer(ClientWorld world, GameProfile profile) {
        super(world, profile);
    }

    @Override
    public void tick() {
        // ничего не делаем: поза и положение остаются как были
    }

    // без @Override: если в этой версии метод называется иначе, он просто не сработает
    public boolean canHit() { return false; }

    @Override
    public boolean isAttackable() { return false; }
}
