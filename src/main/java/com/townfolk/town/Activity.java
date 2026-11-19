package com.townfolk.town;

import net.minecraft.network.chat.Component;

/** 时间表里每个钟点可以安排的活动。新的只能加在最后，存档里记的是序号。 */
public enum Activity {
    SLEEP("sleep", 0x3B4A7A),
    WORK("work", 0xA8642A),
    EAT("eat", 0xB5483A),
    REST("rest", 0x5F8F4A),
    FREE("free", 0xC9A227);

    private static final Activity[] VALUES = values();

    public final String id;
    /** 时间表界面里这种活动的颜色（RGB）。 */
    public final int color;

    Activity(String id, int color) {
        this.id = id;
        this.color = color;
    }

    public Component displayName() {
        return Component.translatable("townfolk.activity." + id);
    }

    /** 一个字的简称，写在时间表的格子里。 */
    public Component shortName() {
        return Component.translatable("townfolk.activity." + id + ".short");
    }

    public static Activity byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : FREE;
    }

    /** 把 24 个钟点的安排写成 24 个数字组成的字符串，方便发给客户端。 */
    public static String encode(byte[] schedule) {
        StringBuilder builder = new StringBuilder(schedule.length);
        for (byte value : schedule) {
            builder.append((char) ('0' + value));
        }
        return builder.toString();
    }

    /** 从 encode 得到的字符串里取某个钟点的活动。 */
    public static Activity at(String encoded, int hour) {
        if (hour < 0 || hour >= encoded.length()) return FREE;
        return byOrdinal(encoded.charAt(hour) - '0');
    }
}
