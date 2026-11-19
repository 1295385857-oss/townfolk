package com.townfolk.entity;

import net.minecraft.network.chat.Component;

/** 居民此刻在做什么，显示给镇长看。 */
public enum ResidentStatus {
    IDLE("idle"),
    SLEEPING("sleeping"),
    GOING_HOME("going_home"),
    NO_BED("no_bed"),
    NO_ZONE("no_zone"),
    RESTING("resting"),
    DIGGING_PIT("digging_pit"),
    TILLING("tilling"),
    PLANTING("planting"),
    HARVESTING("harvesting"),
    CHOPPING("chopping"),
    PLANTING_SAPLING("planting_sapling"),
    COLLECTING("collecting"),
    DEPOSITING("depositing"),
    STORAGE_FULL("storage_full"),
    EATING("eating"),
    BREAK("break"),
    CHATTING("chatting"),
    SHOPPING("shopping"),
    QUEUEING("queueing"),
    TENDING("tending"),
    NO_REGISTER("no_register"),
    HUNGRY("hungry");

    private static final ResidentStatus[] VALUES = values();

    public final String id;

    ResidentStatus(String id) {
        this.id = id;
    }

    public Component displayName() {
        return Component.translatable("townfolk.status." + id);
    }

    public static ResidentStatus byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : IDLE;
    }
}
