package com.townfolk.town;

import net.minecraft.network.chat.Component;

/** 分区的五个大类。具体做什么在分区建好后再选。 */
public enum ZoneType {
    AGRICULTURE("agriculture", 0x6FD13F),
    LIGHT_INDUSTRY("light_industry", 0xF0C030),
    HEAVY_INDUSTRY("heavy_industry", 0xE0602D),
    RESIDENTIAL("residential", 0x4F9BF0),
    COMMERCIAL("commercial", 0xB868D8);

    private static final ZoneType[] VALUES = values();

    public final String id;
    /** 上帝视角里这类分区的颜色（RGB）。 */
    public final int color;

    ZoneType(String id, int color) {
        this.id = id;
        this.color = color;
    }

    public Component displayName() {
        return Component.translatable("zone.townfolk." + id);
    }

    public static ZoneType byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : AGRICULTURE;
    }
}
