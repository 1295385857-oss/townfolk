package com.townfolk.town;

import com.townfolk.entity.Profession;
import com.townfolk.entity.ResidentStatus;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;

/**
 * 小镇名册里的一条居民记录。
 * 居民实体不在已加载区块里时，管理界面靠这份记录显示名单；
 * 镇长的安排（职业）和小镇分给他的东西（工作分区、床位）也记在这里，居民实体再来读取。
 */
public class ResidentRecord {
    public final UUID id;
    public String name = "";
    public boolean male;
    public int ageYears;
    public Profession job = Profession.UNEMPLOYED;
    public ResidentStatus status = ResidentStatus.IDLE;
    @Nullable public UUID spouse;
    @Nullable public UUID father;
    @Nullable public UUID mother;
    /** 正在哪个分区工作。 */
    @Nullable public UUID zoneId;
    /** 认领的床（床头方块的位置）。 */
    @Nullable public BlockPos bedPos;
    /** 最早什么时候可以再生一个孩子（游戏时间）。 */
    public long nextBirthTime;
    /** 钱包里的金币。 */
    public int money;
    /** 名下的存粮，单位是饱腹点数。一家人吃同一份，记在户主名下（见 Economy.pantryOwner）。 */
    public int pantry;
    /** 饱食度，0 到 20。由居民实体定期报上来，只用来显示。 */
    public int satiety = 14;
    /** 手里有没有合用的工具。由居民实体定期报上来。 */
    public boolean hasTool;

    public ResidentRecord(UUID id) {
        this.id = id;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Name", name);
        tag.putBoolean("Male", male);
        tag.putInt("Age", ageYears);
        tag.putInt("Job", job.ordinal());
        tag.putInt("Status", status.ordinal());
        if (spouse != null) tag.putUUID("Spouse", spouse);
        if (father != null) tag.putUUID("Father", father);
        if (mother != null) tag.putUUID("Mother", mother);
        if (zoneId != null) tag.putUUID("Zone", zoneId);
        if (bedPos != null) tag.put("Bed", NbtUtils.writeBlockPos(bedPos));
        tag.putLong("NextBirth", nextBirthTime);
        tag.putInt("Money", money);
        tag.putInt("Pantry", pantry);
        tag.putInt("Satiety", satiety);
        tag.putBoolean("HasTool", hasTool);
        return tag;
    }

    public static ResidentRecord load(CompoundTag tag) {
        ResidentRecord record = new ResidentRecord(tag.getUUID("Id"));
        record.name = tag.getString("Name");
        record.male = tag.getBoolean("Male");
        record.ageYears = tag.getInt("Age");
        record.job = Profession.byOrdinal(tag.getInt("Job"));
        record.status = ResidentStatus.byOrdinal(tag.getInt("Status"));
        record.spouse = tag.hasUUID("Spouse") ? tag.getUUID("Spouse") : null;
        record.father = tag.hasUUID("Father") ? tag.getUUID("Father") : null;
        record.mother = tag.hasUUID("Mother") ? tag.getUUID("Mother") : null;
        record.zoneId = tag.hasUUID("Zone") ? tag.getUUID("Zone") : null;
        record.bedPos = NbtUtils.readBlockPos(tag, "Bed").orElse(null);
        record.nextBirthTime = tag.getLong("NextBirth");
        record.money = tag.getInt("Money");
        record.pantry = tag.getInt("Pantry");
        if (tag.contains("Satiety")) record.satiety = tag.getInt("Satiety");
        record.hasTool = tag.getBoolean("HasTool");
        return record;
    }
}
