package com.townfolk.town;

import com.townfolk.entity.Profession;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 分区里具体经营什么：农业区种什么，居民区盖哪种房。
 * 新的选项只能加在最后，存档里记的是序号。
 */
public enum Industry {
    NONE("none", null, "", Profession.UNEMPLOYED, 0),
    WHEAT("wheat", ZoneType.AGRICULTURE, "crop", Profession.FARMER, 0),
    CARROT("carrot", ZoneType.AGRICULTURE, "crop", Profession.FARMER, 0),
    POTATO("potato", ZoneType.AGRICULTURE, "crop", Profession.FARMER, 0),
    BEETROOT("beetroot", ZoneType.AGRICULTURE, "crop", Profession.FARMER, 0),
    OAK("oak", ZoneType.AGRICULTURE, "tree", Profession.LUMBERJACK, 2),
    BIRCH("birch", ZoneType.AGRICULTURE, "tree", Profession.LUMBERJACK, 2),
    SPRUCE("spruce", ZoneType.AGRICULTURE, "tree", Profession.LUMBERJACK, 3),
    /** 独栋：整个分区是一栋房子，住一户。 */
    DETACHED("detached", ZoneType.RESIDENTIAL, "housing", Profession.UNEMPLOYED, 0),
    /** 楼房：分区里按门分成很多户。 */
    APARTMENT("apartment", ZoneType.RESIDENTIAL, "housing", Profession.UNEMPLOYED, 0),
    /** 商店：货架上卖东西。 */
    SHOP("shop", ZoneType.COMMERCIAL, "commerce", Profession.CLERK, 0),
    /** 餐厅：居民来这里吃饭。 */
    RESTAURANT("restaurant", ZoneType.COMMERCIAL, "commerce", Profession.UNEMPLOYED, 0);

    private static final Industry[] VALUES = values();

    public final String id;
    /** 这个选项只能用在哪类分区里，NONE 为 null。 */
    @Nullable public final ZoneType zoneType;
    /** 菜单里归在哪一组：crop 种植、tree 林场、housing 房型。 */
    public final String group;
    /** 在这个产业里干活的职业。 */
    public final Profession profession;
    /** 林场：树苗和周围树干、树苗之间至少要空出的格数。 */
    public final int treeGap;

    Industry(String id, @Nullable ZoneType zoneType, String group, Profession profession, int treeGap) {
        this.id = id;
        this.zoneType = zoneType;
        this.group = group;
        this.profession = profession;
        this.treeGap = treeGap;
    }

    public boolean isCrop() {
        return profession == Profession.FARMER;
    }

    public boolean isTree() {
        return profession == Profession.LUMBERJACK;
    }

    public boolean isHousing() {
        return this == DETACHED || this == APARTMENT;
    }

    /** 这个产业需不需要工人。 */
    public boolean hasWorkers() {
        return profession != Profession.UNEMPLOYED;
    }

    public Component displayName() {
        return Component.translatable("industry.townfolk." + id);
    }

    /** 种植业种下的作物方块。 */
    public Block cropBlock() {
        return switch (this) {
            case CARROT -> Blocks.CARROTS;
            case POTATO -> Blocks.POTATOES;
            case BEETROOT -> Blocks.BEETROOTS;
            default -> Blocks.WHEAT;
        };
    }

    /** 播种或种树要消耗的物品。 */
    public Item plantingItem() {
        return switch (this) {
            case WHEAT -> Items.WHEAT_SEEDS;
            case CARROT -> Items.CARROT;
            case POTATO -> Items.POTATO;
            case BEETROOT -> Items.BEETROOT_SEEDS;
            case OAK -> Items.OAK_SAPLING;
            case BIRCH -> Items.BIRCH_SAPLING;
            case SPRUCE -> Items.SPRUCE_SAPLING;
            default -> Items.AIR;
        };
    }

    /** 林场种下的树苗方块。 */
    public Block saplingBlock() {
        return switch (this) {
            case BIRCH -> Blocks.BIRCH_SAPLING;
            case SPRUCE -> Blocks.SPRUCE_SAPLING;
            default -> Blocks.OAK_SAPLING;
        };
    }

    /** 某类分区里可以选的选项。 */
    public static List<Industry> optionsFor(ZoneType type) {
        List<Industry> list = new ArrayList<>();
        for (Industry industry : VALUES) {
            if (industry.zoneType == type) list.add(industry);
        }
        return list;
    }

    public static Industry byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : NONE;
    }
}
