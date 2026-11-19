package com.townfolk.entity;

import com.townfolk.entity.ai.EatGoal;
import com.townfolk.entity.ai.RestGoal;
import com.townfolk.entity.ai.ShopGoal;
import com.townfolk.entity.ai.SleepGoal;
import com.townfolk.entity.ai.SocializeGoal;
import com.townfolk.entity.ai.WorkGoal;
import com.townfolk.town.Activity;
import com.townfolk.town.PriceTable;
import com.townfolk.town.ResidentRecord;
import com.townfolk.town.Town;
import com.townfolk.town.TownManager;
import com.townfolk.town.TownSavedData;
import com.townfolk.town.Zone;
import com.townfolk.town.ZoneRect;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/** 小镇居民。有名字、性别、年龄和职业，归属于某个小镇；白天在工作分区干活，晚上回床睡觉。 */
public class ResidentEntity extends PathfinderMob implements GeoEntity {
    /** 2 个游戏日长 1 岁。 */
    public static final long TICKS_PER_YEAR = 48000L;
    public static final int ADULT_AGE = 18;
    public static final int MIN_LIFESPAN = 70;
    public static final int MAX_LIFESPAN = 85;
    /** 每种衣服的外貌变体数量（肤色、发色不同），要和 tools/gen_textures.py 里一致。 */
    public static final int VARIANT_COUNT = 4;
    public static final int INVENTORY_SIZE = 18;
    public static final int MAX_SATIETY = 20;
    /** 饱食度每过这么久掉一点（tick）：两个钟点。一天掉 12 点。 */
    private static final int HUNGER_INTERVAL = 2000;

    /** 多久和小镇名册同步一次（tick）。 */
    private static final int SYNC_INTERVAL = 40;
    /** 没有床的时候，多久找一次床（tick）。 */
    private static final int BED_SEARCH_INTERVAL = 200;
    /** 种子或树苗用光后，要等多久才会再得到一批（tick）。 */
    private static final int SUPPLY_INTERVAL = 2400;

    private static final EntityDataAccessor<Boolean> DATA_MALE = SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_PROFESSION = SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_VARIANT = SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_AGE_YEARS = SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ANIM = SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.INT);

    // 动画的名字要和 tools/gen_animations.py 里生成的一致
    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation ANIM_WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation ANIM_SLEEP = RawAnimation.begin().thenLoop("sleep");
    /** 下标是 WorkAnim 的序号。 */
    private static final RawAnimation[] WORK_ANIMS = {
            ANIM_IDLE,
            RawAnimation.begin().thenLoop("hoe"),
            RawAnimation.begin().thenLoop("bend"),
            RawAnimation.begin().thenLoop("chop"),
            RawAnimation.begin().thenLoop("dig"),
            RawAnimation.begin().thenLoop("store"),
            RawAnimation.begin().thenLoop("eat"),
            RawAnimation.begin().thenLoop("sit"),
            RawAnimation.begin().thenLoop("talk"),
    };

    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    @Nullable private UUID townId;
    @Nullable private UUID spouse;
    @Nullable private UUID father;
    @Nullable private UUID mother;
    /** 已经活过的时间（tick）。 */
    private long ageTicks = ADULT_AGE * TICKS_PER_YEAR;
    /** 上一次计算年龄时的世界时间，-1 表示还没算过。 */
    private long lastDayTime = -1L;
    private long nextSupplyTime;
    /** 干活用的工具，要自己去商店买，会用坏。 */
    private ItemStack tool = ItemStack.EMPTY;
    /** 饱食度，0 到 20。每两个钟点掉一点，吃饭时靠家里的存粮补回来。 */
    private int satiety = 14;
    private long hungerClock;
    /** 活到多少岁会寿终正寝，每个人不一样。 */
    private int lifespanYears = MIN_LIFESPAN + random.nextInt(MAX_LIFESPAN - MIN_LIFESPAN + 1);
    private boolean diedOfOldAge;

    // 以下几项不存档，每次和小镇同步时重新取得
    private ResidentStatus status = ResidentStatus.IDLE;
    @Nullable private Zone workZone;
    @Nullable private BlockPos homeBed;
    @Nullable private BlockPos townCenter;
    private long storageFullUntil;
    /** 此刻正要去干活的那一格。 */
    @Nullable private BlockPos workTarget;
    /** 所在小镇的时间表。没有归属时用默认的。 */
    private byte[] schedule = Town.defaultSchedule();
    /** 正在一起聊天的人，以及这次聊天最晚到什么时候作废。 */
    @Nullable private ResidentEntity chatPartner;
    private int chatTicks;
    private long chatDeadline;
    private long nextBedSearchTime;

    public ResidentEntity(EntityType<? extends ResidentEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        if (getNavigation() instanceof GroundPathNavigation navigation) {
            navigation.setCanOpenDoors(true);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new PanicGoal(this, 1.4));
        goalSelector.addGoal(2, new OpenDoorGoal(this, true));
        goalSelector.addGoal(3, new SleepGoal(this));
        goalSelector.addGoal(4, new ShopGoal(this));           // 排在干活前面：钱够了第一时间去买工具
        goalSelector.addGoal(5, new WorkGoal(this));
        goalSelector.addGoal(6, new EatGoal(this));
        goalSelector.addGoal(6, new RestGoal(this));
        goalSelector.addGoal(7, new SocializeGoal(this));
        goalSelector.addGoal(8, new MoveTowardsRestrictionGoal(this, 0.9));
        goalSelector.addGoal(9, new WaterAvoidingRandomStrollGoal(this, 0.7));
        goalSelector.addGoal(10, new LookAtPlayerGoal(this, Player.class, 6.0F));
        goalSelector.addGoal(11, new RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_MALE, true);
        builder.define(DATA_PROFESSION, Profession.UNEMPLOYED.ordinal());
        builder.define(DATA_VARIANT, 0);
        builder.define(DATA_AGE_YEARS, ADULT_AGE);
        builder.define(DATA_ANIM, WorkAnim.NONE.ordinal());
    }

    /** 新居民出生或迁入时设定基本信息。 */
    public void initResident(String fullName, boolean male, int ageYears, int variant) {
        setCustomName(Component.literal(fullName));
        entityData.set(DATA_MALE, male);
        entityData.set(DATA_VARIANT, Math.floorMod(variant, VARIANT_COUNT));
        // 在这一岁里随机过了一段时间，免得所有人同一天长一岁
        ageTicks = ageYears * TICKS_PER_YEAR + random.nextInt((int) TICKS_PER_YEAR);
        entityData.set(DATA_AGE_YEARS, ageYears);
    }

    // ------------------------------------------------------------ 基本属性

    public String getFullName() {
        Component name = getCustomName();
        return name == null ? "" : name.getString();
    }

    public boolean isMale() {
        return entityData.get(DATA_MALE);
    }

    public int getVariant() {
        return entityData.get(DATA_VARIANT);
    }

    public int getAgeYears() {
        return entityData.get(DATA_AGE_YEARS);
    }

    public boolean isAdult() {
        return getAgeYears() >= ADULT_AGE;
    }

    public int getLifespanYears() {
        return lifespanYears;
    }

    /** 直接把年龄改成指定的岁数（给指令和自动测试用）。 */
    public void setAgeYears(int years) {
        ageTicks = years * TICKS_PER_YEAR;
        entityData.set(DATA_AGE_YEARS, years);
    }

    public void setParents(@Nullable UUID father, @Nullable UUID mother) {
        this.father = father;
        this.mother = mother;
    }

    @Nullable
    public UUID getSpouse() {
        return spouse;
    }

    public Profession getProfession() {
        return Profession.byOrdinal(entityData.get(DATA_PROFESSION));
    }

    public void setProfession(Profession profession) {
        if (!isAdult()) profession = Profession.UNEMPLOYED;
        if (profession == getProfession()) return;
        entityData.set(DATA_PROFESSION, profession.ordinal());
        showInHand(ItemStack.EMPTY);
    }

    @Nullable
    public UUID getTownId() {
        return townId;
    }

    public ResidentStatus getStatus() {
        return status;
    }

    public void setStatus(ResidentStatus status) {
        this.status = status;
    }

    // ------------------------------------------------------------ 动作

    public WorkAnim getWorkAnim() {
        return WorkAnim.byOrdinal(entityData.get(DATA_ANIM));
    }

    public void setWorkAnim(WorkAnim anim) {
        if (anim != getWorkAnim()) entityData.set(DATA_ANIM, anim.ordinal());
    }

    /** 交给 GeckoLib 的动画控制：睡觉、干活、走路、站着，四选一，切换时有 0.2 秒的过渡。 */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 4, this::pickAnimation));
    }

    private PlayState pickAnimation(AnimationState<ResidentEntity> state) {
        float speed = 1.0F;
        RawAnimation animation;
        WorkAnim work = getWorkAnim();
        if (isSleeping()) {
            animation = ANIM_SLEEP;
        } else if (work != WorkAnim.NONE) {
            animation = WORK_ANIMS[work.ordinal()];
        } else if (state.isMoving()) {
            animation = ANIM_WALK;
            speed = Mth.clamp(state.getLimbSwingAmount() * 1.3F, 0.6F, 1.6F);     // 走得快，步子也跟着快
        } else {
            animation = ANIM_IDLE;
        }
        state.setControllerSpeed(speed);
        return state.setAndContinue(animation);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    /** 手里换成这样东西。传空的表示换回本职业的工具。 */
    public void showInHand(ItemStack stack) {
        ItemStack wanted = stack.isEmpty() ? (hasTool() ? tool : ItemStack.EMPTY) : stack;
        if (!ItemStack.isSameItem(getMainHandItem(), wanted)) {
            setItemSlot(EquipmentSlot.MAINHAND, wanted);
            setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        }
    }

    // ------------------------------------------------------------ 工具和饱食度

    /** 手里有没有现在这个职业用得上的工具。 */
    public boolean hasTool() {
        return !tool.isEmpty() && PriceTable.isToolFor(getProfession(), tool.getItem());
    }

    public ItemStack getTool() {
        return tool;
    }

    public void giveTool(ItemStack stack) {
        tool = stack.copy();
        showInHand(ItemStack.EMPTY);
    }

    /** 工具用了 uses 次。用到头就坏掉，得再去买。 */
    public void useTool(int uses) {
        if (!hasTool()) return;
        tool.setDamageValue(tool.getDamageValue() + uses);
        if (tool.getDamageValue() >= tool.getMaxDamage()) {
            tool = ItemStack.EMPTY;
            playSound(SoundEvents.ITEM_BREAK, 0.8F, 1.0F);
            showInHand(ItemStack.EMPTY);
        }
    }

    public int getSatiety() {
        return satiety;
    }

    public void eat(int points) {
        satiety = Math.min(MAX_SATIETY, satiety + points);
    }

    /** 饿了：该吃东西了。 */
    public boolean isHungry() {
        return satiety <= 6;
    }

    /** 饿得厉害：干活都干不动了。 */
    public boolean isStarving() {
        return satiety <= 2;
    }

    @Nullable
    public BlockPos getWorkTarget() {
        return workTarget;
    }

    public void setWorkTarget(@Nullable BlockPos target) {
        workTarget = target;
    }

    // ------------------------------------------------------------ 作息

    /** 时间表上这个钟点写的是什么。 */
    public Activity getScheduledActivity() {
        return Activity.byOrdinal(schedule[Town.hourOf(level().getDayTime())]);
    }

    /** 这个居民现在实际该做什么。小孩和没工作的人，工作的钟点算自由活动。 */
    public Activity getRoutine() {
        Activity activity = getScheduledActivity();
        // 店员在别人自由活动的钟点也看店，不然大家有空的时候店是关着的
        if (activity == Activity.FREE && isAdult() && getProfession() == Profession.CLERK) return Activity.WORK;
        if (activity == Activity.WORK && (!isAdult() || getProfession() == Profession.UNEMPLOYED)) return Activity.FREE;
        return activity;
    }

    /** 现在是不是该上班：成年、有职业、时间表上是工作。 */
    public boolean isWorkTime() {
        return getRoutine() == Activity.WORK;
    }

    /** 这个钟点过了一半，而下一个钟点就不干活了：该把身上的收获存掉。 */
    public boolean isWorkDayEnding() {
        long dayTime = level().getDayTime();
        if (Math.floorMod(dayTime, 1000L) < 500L) return false;
        return Activity.byOrdinal(schedule[(Town.hourOf(dayTime) + 1) % 24]) != Activity.WORK;
    }

    public boolean isSleepTime() {
        return getRoutine() == Activity.SLEEP;
    }

    // ------------------------------------------------------------ 聊天

    @Nullable
    public ResidentEntity getChatPartner() {
        if (chatPartner != null && level().getGameTime() > chatDeadline) chatPartner = null;
        return chatPartner;
    }

    public void startChat(ResidentEntity partner, int ticks) {
        chatPartner = partner;
        chatTicks = ticks;
        chatDeadline = level().getGameTime() + ticks + 400L;
    }

    public void endChat() {
        chatPartner = null;
    }

    /** 面对面聊着的时候每 tick 调一次，聊够了返回 true。 */
    public boolean tickChat() {
        return --chatTicks <= 0;
    }

    /** 现在工作的分区，没有则为 null。 */
    @Nullable
    public Zone getWorkZone() {
        return workZone;
    }

    @Nullable
    public BlockPos getHomeBed() {
        return homeBed;
    }

    /** 床不能用了（被拆掉等），下次同步时会重新找一张。 */
    public void forgetHomeBed() {
        homeBed = null;
        nextBedSearchTime = 0L;
        if (level() instanceof ServerLevel serverLevel && townId != null) {
            Town town = TownSavedData.get(serverLevel).getTown(townId);
            ResidentRecord record = town == null ? null : town.residents.get(getUUID());
            if (record != null) record.bedPos = null;
        }
    }

    @Nullable
    public BlockPos getTownCenter() {
        return townCenter;
    }

    // ------------------------------------------------------------ 随身物品

    public SimpleContainer getInventory() {
        return inventory;
    }

    /** 放进随身物品，返回放不下的部分。 */
    public ItemStack addToInventory(ItemStack stack) {
        return inventory.addItem(stack);
    }

    public int countItem(Item item) {
        return inventory.countItem(item);
    }

    /** 消耗一个物品，身上没有时返回 false。 */
    public boolean consumeItem(Item item) {
        return !inventory.removeItemType(item, 1).isEmpty();
    }

    public int usedSlots() {
        int used = 0;
        for (ItemStack stack : inventory.getItems()) {
            if (!stack.isEmpty()) used++;
        }
        return used;
    }

    public boolean hasFreeSlot() {
        return usedSlots() < INVENTORY_SIZE;
    }

    /**
     * 播种用的种子或树苗一个都没有时补给一批，免得分区因为没有种子永远开不了工。
     * 两次补给之间要隔一段时间，平时还是靠收获来补种。
     */
    public void ensureSupply(Item item, int amount) {
        if (countItem(item) > 0) return;
        long now = level().getGameTime();
        if (now < nextSupplyTime) return;
        nextSupplyTime = now + SUPPLY_INTERVAL;
        addToInventory(new ItemStack(item, amount));
    }

    public boolean isStorageFull() {
        return level().getGameTime() < storageFullUntil;
    }

    /** 箱子都满了或者没有箱子：过 ticks 之后再去看。 */
    public void markStorageFull(int ticks) {
        storageFullUntil = level().getGameTime() + ticks;
    }

    // ------------------------------------------------------------ 体型随年龄变化

    /** 未成年人从 0.45 倍身高慢慢长到成年人的 1 倍。 */
    @Override
    public float getAgeScale() {
        int years = Math.min(getAgeYears(), ADULT_AGE);
        return 0.45F + 0.55F * years / ADULT_AGE;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_AGE_YEARS.equals(key)) {
            refreshDimensions();
        }
    }

    // ------------------------------------------------------------ 小镇归属

    public void joinTown(Town town) {
        townId = town.id;
        townCenter = town.center;
        restrictTo(town.center, Town.WANDER_RADIUS);
        ResidentRecord record = town.residents.computeIfAbsent(getUUID(), ResidentRecord::new);
        record.job = getProfession();
        writeRecord(town, record);
    }

    public void leaveTown() {
        townId = null;
        townCenter = null;
        workZone = null;
        homeBed = null;
        schedule = Town.defaultSchedule();
        clearRestriction();
        setProfession(Profession.UNEMPLOYED);
        setStatus(ResidentStatus.IDLE);
    }

    /** 定期和小镇名册对一次账：读取镇长的安排和小镇分配的分区、床位，写回自己的最新情况。 */
    public void syncWithTown(ServerLevel level) {
        if (townId == null) return;
        TownSavedData data = TownSavedData.get(level);
        Town town = data.getTown(townId);
        if (town == null) {             // 小镇已经解散
            leaveTown();
            return;
        }
        townCenter = town.center;
        schedule = town.schedule;
        boolean dirty = false;

        ResidentRecord record = town.residents.get(getUUID());
        if (record == null) {
            record = new ResidentRecord(getUUID());
            record.job = getProfession();
            town.residents.put(getUUID(), record);
            dirty = true;
        }
        if (record.job != getProfession()) {
            setProfession(record.job);
            record.job = getProfession();   // 未成年人会被改回无业
        }

        // 工作分区、住处、配偶都由小镇统一安排，这里只是读过来
        Zone zone = record.zoneId == null ? null : town.zones.get(record.zoneId);
        workZone = zone;
        homeBed = record.bedPos;
        spouse = record.spouse;
        record.hasTool = hasTool();
        record.satiety = satiety;

        updateRestriction(town, zone);
        updateIdleStatus(zone);
        if (writeRecord(town, record) || dirty) {
            data.setDirty();
        }
    }

    /** 干活和工间休息时留在工作分区一带；自由活动时在镇公所一带；其余时间在自己家附近，没有住处的人待在镇公所附近。 */
    private void updateRestriction(Town town, @Nullable Zone zone) {
        Activity routine = getRoutine();
        if (zone != null && (routine == Activity.WORK || routine == Activity.REST)) {
            ZoneRect rect = zone.rect;
            restrictTo(zone.center(blockPosition().getY()), Math.max(rect.width(), rect.depth()) / 2 + 6);
        } else if (routine == Activity.FREE) {
            restrictTo(town.center, Town.WANDER_RADIUS);
        } else if (homeBed != null) {
            restrictTo(homeBed, Town.HOME_RADIUS);
        } else {
            restrictTo(town.center, Town.WANDER_RADIUS);
        }
    }

    /** 不在干活也不在睡觉的时候，把显示给镇长看的状态整理一下。 */
    private void updateIdleStatus(@Nullable Zone zone) {
        Activity routine = getRoutine();
        if (routine == Activity.FREE) {
            if (status != ResidentStatus.CHATTING && status != ResidentStatus.DEPOSITING) status = ResidentStatus.IDLE;
        } else if (routine == Activity.WORK) {
            boolean notWorking = status == ResidentStatus.IDLE || status == ResidentStatus.NO_ZONE || status == ResidentStatus.EATING
                    || status == ResidentStatus.BREAK || status == ResidentStatus.CHATTING || status == ResidentStatus.SLEEPING;
            if (zone == null) status = ResidentStatus.NO_ZONE;
            else if (notWorking) status = ResidentStatus.RESTING;
        }
        // 睡觉、吃饭、休息时的状态由各自的行为负责
    }

    /** 把自己的情况写进名册，有变化时返回 true。 */
    private boolean writeRecord(Town town, ResidentRecord record) {
        String name = getFullName();
        boolean changed = !name.equals(record.name)
                || record.male != isMale()
                || record.ageYears != getAgeYears()
                || record.status != status
                || !Objects.equals(record.father, father)
                || !Objects.equals(record.mother, mother);
        record.name = name;
        record.male = isMale();
        record.ageYears = getAgeYears();
        record.status = status;
        record.father = father;
        record.mother = mother;
        if (!name.equals(town.knownNames.get(getUUID()))) {
            town.knownNames.put(getUUID(), name);
            changed = true;
        }
        return changed;
    }

    // ------------------------------------------------------------ 每 tick 逻辑

    @Override
    public void tick() {
        super.tick();
        // 去世的人不再往名册里写东西，不然倒地的那一秒里会把自己重新登记回去
        if (level() instanceof ServerLevel serverLevel && isAlive()) {
            updateAge(serverLevel);
            if ((tickCount + getId()) % SYNC_INTERVAL == 0) {
                syncWithTown(serverLevel);
            }
        }
    }

    /** 年龄跟着世界时间走，所以玩家睡觉跳过夜晚、居民所在区块没加载时，年龄照样增长。 */
    private void updateAge(ServerLevel level) {
        long dayTime = level.getDayTime();
        if (lastDayTime >= 0L) {
            long delta = dayTime - lastDayTime;
            if (delta < 0L) delta = 1L;      // 世界时间被指令往回调了
            ageTicks += delta;
            hungerClock += Math.min(delta, 24000L);
            while (hungerClock >= HUNGER_INTERVAL) {
                hungerClock -= HUNGER_INTERVAL;
                satiety = Math.max(0, satiety - 1);
            }
        }
        lastDayTime = dayTime;

        int years = (int) (ageTicks / TICKS_PER_YEAR);
        if (years != getAgeYears()) {
            entityData.set(DATA_AGE_YEARS, years);
        }
        if (years >= lifespanYears && isAlive()) {
            diedOfOldAge = true;
            kill();
        }
    }

    // ------------------------------------------------------------ 交互和其他

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer) {
            Component gender = Component.translatable(isMale() ? "townfolk.gender.male" : "townfolk.gender.female");
            Component job = isAdult() ? getProfession().displayName() : Component.translatable("townfolk.child");
            player.displayClientMessage(
                    Component.translatable("townfolk.resident.info", getFullName(), gender, getAgeYears(), job, status.displayName()), true);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (level() instanceof ServerLevel serverLevel && townId != null) {
            TownSavedData data = TownSavedData.get(serverLevel);
            Town town = data.getTown(townId);
            if (town != null && town.residents.remove(getUUID()) != null) {
                for (ResidentRecord other : town.residents.values()) {
                    if (getUUID().equals(other.spouse)) other.spouse = null;       // 配偶变回单身
                }
                data.setDirty();
                Component message = diedOfOldAge
                        ? Component.translatable("townfolk.message.died_old_age", getFullName(), town.name, getAgeYears())
                        : Component.translatable("townfolk.message.resident_died", getFullName(), town.name);
                TownManager.notifyMayor(serverLevel, town, message);
            }
        }
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        for (ItemStack stack : inventory.removeAllItems()) {
            spawnAtLocation(stack);
        }
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    /** 居民不会踩坏耕地。 */
    @Override
    public boolean canTrample(BlockState state, BlockPos pos, float fallDistance) {
        return false;
    }

    /** 睡着的人不会被挤下床。 */
    @Override
    public boolean isPushable() {
        // 睡着的人不会被挤下床；看店的店员也不会被客人从收银台后面挤走
        return !isSleeping() && status != ResidentStatus.TENDING && super.isPushable();
    }

    // ------------------------------------------------------------ 存档

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Male", isMale());
        tag.putInt("Profession", getProfession().ordinal());
        tag.putInt("Variant", getVariant());
        tag.putLong("AgeTicks", ageTicks);
        tag.putLong("LastDayTime", lastDayTime);
        tag.putLong("NextSupplyTime", nextSupplyTime);
        tag.putInt("Lifespan", lifespanYears);
        tag.putInt("Satiety", satiety);
        tag.putLong("HungerClock", hungerClock);
        if (!tool.isEmpty()) tag.put("Tool", tool.save(registryAccess()));
        tag.put("Inventory", inventory.createTag(registryAccess()));
        if (townId != null) tag.putUUID("Town", townId);
        if (father != null) tag.putUUID("Father", father);
        if (mother != null) tag.putUUID("Mother", mother);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(DATA_MALE, tag.getBoolean("Male"));
        entityData.set(DATA_VARIANT, Math.floorMod(tag.getInt("Variant"), VARIANT_COUNT));
        if (tag.contains("AgeTicks")) ageTicks = tag.getLong("AgeTicks");
        if (tag.contains("LastDayTime")) lastDayTime = tag.getLong("LastDayTime");
        nextSupplyTime = tag.getLong("NextSupplyTime");
        if (tag.contains("Lifespan")) lifespanYears = tag.getInt("Lifespan");
        if (tag.contains("Satiety")) satiety = tag.getInt("Satiety");
        hungerClock = tag.getLong("HungerClock");
        tool = tag.contains("Tool") ? ItemStack.parseOptional(registryAccess(), tag.getCompound("Tool")) : ItemStack.EMPTY;
        entityData.set(DATA_AGE_YEARS, (int) (ageTicks / TICKS_PER_YEAR));
        entityData.set(DATA_PROFESSION, Profession.byOrdinal(tag.getInt("Profession")).ordinal());
        if (tag.contains("Inventory", Tag.TAG_LIST)) {
            inventory.fromTag(tag.getList("Inventory", Tag.TAG_COMPOUND), registryAccess());
        }
        townId = tag.hasUUID("Town") ? tag.getUUID("Town") : null;
        father = tag.hasUUID("Father") ? tag.getUUID("Father") : null;
        mother = tag.hasUUID("Mother") ? tag.getUUID("Mother") : null;
    }
}
