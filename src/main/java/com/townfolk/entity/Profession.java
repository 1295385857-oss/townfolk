package com.townfolk.entity;

import net.minecraft.network.chat.Component;

/** 职业。新的只能加在最后，存档里记的是序号。 */
public enum Profession {
    UNEMPLOYED("unemployed"),
    FARMER("farmer"),
    LUMBERJACK("lumberjack"),
    CLERK("clerk");

    private static final Profession[] VALUES = values();

    public final String id;

    Profession(String id) {
        this.id = id;
    }

    public Component displayName() {
        return Component.translatable("profession.townfolk." + id);
    }

    public static Profession byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : UNEMPLOYED;
    }
}
