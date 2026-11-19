package com.townfolk.entity;

/** 居民正在做的动作，同步给客户端，由模型摆出对应的姿势。 */
public enum WorkAnim {
    NONE,
    /** 双手举起锄头往下刨。 */
    HOE,
    /** 弯腰，用手在地上忙活（播种、收割、捡东西、种树苗）。 */
    BEND,
    /** 双手横着抡斧头。 */
    CHOP,
    /** 弯腰用铲子挖。 */
    DIG,
    /** 双手往箱子里放东西。 */
    STORE,
    /** 把手里的东西送到嘴边。 */
    EAT,
    /** 坐在地上歇着。 */
    SIT,
    /** 站着比划着说话。 */
    TALK;

    private static final WorkAnim[] VALUES = values();

    public static WorkAnim byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : NONE;
    }
}
