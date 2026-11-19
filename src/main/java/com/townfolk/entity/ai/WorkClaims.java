package com.townfolk.entity.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * 居民之间互相打招呼：“这一格我来干”。
 * 没有这个的话，所有人都会挑离自己最近的那一格，结果一群人挤在同一格上。
 * 认领会自动过期，所以居民中途被打断也不会把一格地永远占住。
 */
public final class WorkClaims {
    /** 认领多久不续就作废（tick）。 */
    public static final int DURATION = 300;

    private record Claim(UUID owner, long expires) {}

    private static final Map<Level, Map<BlockPos, Claim>> CLAIMS = new WeakHashMap<>();

    public static boolean isClaimedByOther(Level level, BlockPos pos, UUID self) {
        Map<BlockPos, Claim> claims = CLAIMS.get(level);
        if (claims == null) return false;
        Claim claim = claims.get(pos);
        if (claim == null) return false;
        if (level.getGameTime() >= claim.expires) {
            claims.remove(pos);
            return false;
        }
        return !claim.owner.equals(self);
    }

    /** 认领，或者给自己已有的认领续期。 */
    public static void claim(Level level, BlockPos pos, UUID owner) {
        CLAIMS.computeIfAbsent(level, l -> new HashMap<>()).put(pos.immutable(), new Claim(owner, level.getGameTime() + DURATION));
    }

    public static void release(Level level, BlockPos pos, UUID owner) {
        Map<BlockPos, Claim> claims = CLAIMS.get(level);
        if (claims == null) return;
        Claim claim = claims.get(pos);
        if (claim != null && claim.owner.equals(owner)) claims.remove(pos);
    }

    private WorkClaims() {}
}
