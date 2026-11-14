package com.example.csfluid;

import java.lang.reflect.Field;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.ICapabilityProvider;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

@Mod("csfluid")
public class CsFluid {
    public static final Logger LOG = LogUtils.getLogger();

    static final ResourceLocation ENGINE_ID =
            ResourceLocation.fromNamespaceAndPath("compactsteam", "compact_steam_engine");

    static final int TICKS_PER_MB = 20;
    static final int CAPACITY_MB = 4000;

    public enum DrainMode {
        SEQUENTIAL,
        EVEN
    }

    // ---------------- конфиг ----------------
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue AUTO_ENABLED;
    public static final ModConfigSpec.IntValue CHECK_INTERVAL;
    public static final ModConfigSpec.IntValue LOWER_DELAY;
    public static final ModConfigSpec.IntValue MIN_STEP;
    public static final ModConfigSpec.DoubleValue MARGIN;
    public static final ModConfigSpec.BooleanValue WRENCH_TOGGLE;

    public static final ModConfigSpec.BooleanValue PULL_TANKS_ENABLED;
    public static final ModConfigSpec.EnumValue<DrainMode> PULL_DRAIN_MODE;
    public static final ModConfigSpec.IntValue PULL_INTERVAL;
    public static final ModConfigSpec.IntValue PULL_MAX_RATE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("autoLoad");
        AUTO_ENABLED = b.comment("Автоматический режим нагрузки. Слайдер мощности двигателя становится ЛИМИТОМ.")
                .define("enabled", true);
        CHECK_INTERVAL = b.comment("Как часто двигатель пересчитывает нужную мощность (в тиках).")
                .defineInRange("checkIntervalTicks", 10, 1, 200);
        LOWER_DELAY = b.comment("Задержка снижения мощности (в тиках).")
                .defineInRange("lowerDelayTicks", 100, 0, 6000);
        MARGIN = b.comment("Запас мощности сверх нагрузки (0.05 = +5%).")
                .defineInRange("safetyMargin", 0.05, 0.0, 1.0);
        MIN_STEP = b.comment("Минимальная ступень мощности на холостом ходу.")
                .defineInRange("minStep", 1, 1, 100);
        WRENCH_TOGGLE = b.comment("ПКМ гаечным ключом Create переключает режимы.")
                .define("wrenchToggle", true);
        b.pop();

        b.push("tankPulling");
        PULL_TANKS_ENABLED = b.comment("Выкачивать ли лаву из соседних баков.")
                .define("enabled", true);
        PULL_DRAIN_MODE = b.comment("Режим выкачки (SEQUENTIAL или EVEN).")
                .defineEnum("drainMode", DrainMode.SEQUENTIAL);
        PULL_INTERVAL = b.comment("Интервал забора лавы (в тиках).")
                .defineInRange("intervalTicks", 10, 1, 200);
        PULL_MAX_RATE = b.comment("Максимум лавы за раз (мБ).")
                .defineInRange("maxRateMb", 1000, 1, 4000);
        b.pop();

        SPEC = b.build();
    }

    public static boolean autoEnabled() { try { return AUTO_ENABLED.get(); } catch (Exception e) { return false; } }
    public static int interval() { try { return CHECK_INTERVAL.get(); } catch (Exception e) { return 10; } }
    public static int lowerDelay() { try { return LOWER_DELAY.get(); } catch (Exception e) { return 100; } }
    public static int minStep() { try { return MIN_STEP.get(); } catch (Exception e) { return 1; } }
    public static boolean wrenchToggle() { try { return WRENCH_TOGGLE.get(); } catch (Exception e) { return true; } }
    public static double margin() { try { return MARGIN.get(); } catch (Exception e) { return 0.05; } }
    public static boolean pullTanksEnabled() { try { return PULL_TANKS_ENABLED.get(); } catch (Exception e) { return true; } }
    public static DrainMode pullDrainMode() { try { return PULL_DRAIN_MODE.get(); } catch (Exception e) { return DrainMode.SEQUENTIAL; } }
    public static int pullInterval() { try { return PULL_INTERVAL.get(); } catch (Exception e) { return 10; } }
    public static int pullMaxRate() { try { return PULL_MAX_RATE.get(); } catch (Exception e) { return 1000; } }

    public CsFluid(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC);
        modBus.addListener(this::registerCaps);
        NeoForge.EVENT_BUS.addListener(WrenchToggle::onRightClick);
    }

    /** Проверяет, лежит ли в двигателе твёрдый предмет топлива */
    public static boolean hasSolidFuel(BlockEntity be) {
        if (be == null) return false;
        Level level = be.getLevel();

        // 1. Проверяем через ItemHandler capability
        if (level != null) {
            IItemHandler cap = level.getCapability(Capabilities.ItemHandler.BLOCK, be.getBlockPos(), null);
            if (isHandlerNotEmpty(cap)) return true;
            for (Direction dir : Direction.values()) {
                cap = level.getCapability(Capabilities.ItemHandler.BLOCK, be.getBlockPos(), dir);
                if (isHandlerNotEmpty(cap)) return true;
            }
        }

        // 2. Проверяем стандартный Container
        if (be instanceof Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (!container.getItem(i).isEmpty()) return true;
            }
        }

        // 3. Запасной поиск через рефлексию по полям ItemStack
        return checkReflectionItem(be);
    }

    private static boolean isHandlerNotEmpty(IItemHandler handler) {
        if (handler != null) {
            for (int i = 0; i < handler.getSlots(); i++) {
                if (!handler.getStackInSlot(i).isEmpty()) return true;
            }
        }
        return false;
    }

    private static Field ITEM_FIELD = null;
    private static boolean itemFieldScanned = false;

    private static boolean checkReflectionItem(BlockEntity be) {
        try {
            Class<?> c = be.getClass();
            if (!itemFieldScanned) {
                itemFieldScanned = true;
                while (c != null && c != Object.class && c != BlockEntity.class) {
                    for (Field f : c.getDeclaredFields()) {
                        if (ItemStack.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            ITEM_FIELD = f;
                            break;
                        }
                    }
                    if (ITEM_FIELD != null) break;
                    c = c.getSuperclass();
                }
            }
            if (ITEM_FIELD != null) {
                Object val = ITEM_FIELD.get(be);
                if (val instanceof ItemStack stack) {
                    return !stack.isEmpty();
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @SuppressWarnings("unchecked")
    private void registerCaps(RegisterCapabilitiesEvent event) {
        BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(ENGINE_ID).orElse(null);
        if (type == null) {
            LOG.warn("[csfluid] Не найден {} — Compact Steam не установлен?", ENGINE_ID);
            return;
        }
        BlockEntityType<BlockEntity> t = (BlockEntityType<BlockEntity>) type;
        ICapabilityProvider<BlockEntity, Direction, IFluidHandler> provider =
                (be, side) -> new EngineLavaHandler(be);
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, t, provider);
        LOG.info("[csfluid] Fluid capability зарегистрирована для {}", ENGINE_ID);
    }

    public static class EngineLavaHandler implements IFluidHandler {
        private static Field REMAINING;
        private static Field TOTAL;
        private static boolean failed = false;

        private final BlockEntity be;

        public EngineLavaHandler(BlockEntity be) {
            this.be = be;
            init(be.getClass());
        }

        private static synchronized void init(Class<?> c) {
            if (REMAINING != null || failed) return;
            try {
                REMAINING = c.getDeclaredField("burnRemaining");
                REMAINING.setAccessible(true);
                TOTAL = c.getDeclaredField("burnTotal");
                TOTAL.setAccessible(true);
            } catch (Exception e) {
                failed = true;
                LOG.error("[csfluid] Не удалось получить поля двигателя", e);
            }
        }

        public double getRemaining() {
            try { return REMAINING.getDouble(be); } catch (Exception e) { return 0; }
        }

        @Override public int getTanks() { return 1; }
        @Override public FluidStack getFluidInTank(int tank) { return FluidStack.EMPTY; }
        @Override public int getTankCapacity(int tank) { return CAPACITY_MB; }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            // Если в двигателе лежит предмет — лава не принимается!
            return stack.is(FluidTags.LAVA) && !hasSolidFuel(be);
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (failed || resource.isEmpty() || !resource.is(FluidTags.LAVA)) return 0;

            // Блокируем прием лавы через трубы/помпы, если внутри уже есть твердое топливо
            if (hasSolidFuel(be)) return 0;

            double remaining = getRemaining();
            int space = (int) Math.floor((CAPACITY_MB * (double) TICKS_PER_MB - remaining) / TICKS_PER_MB);
            int accepted = Math.min(space, resource.getAmount());
            if (accepted <= 0) return 0;

            if (action.execute()) {
                try {
                    double newRemaining = remaining + (double) accepted * TICKS_PER_MB;
                    REMAINING.setDouble(be, newRemaining);
                    int total = TOTAL.getInt(be);
                    if (newRemaining > total) TOTAL.setInt(be, (int) Math.ceil(newRemaining));
                    be.setChanged();
                } catch (Exception e) {
                    LOG.error("[csfluid] Ошибка при добавлении топлива", e);
                    return 0;
                }
            }
            return accepted;
        }

        @Override public FluidStack drain(FluidStack resource, FluidAction action) { return FluidStack.EMPTY; }
        @Override public FluidStack drain(int maxDrain, FluidAction action) { return FluidStack.EMPTY; }
    }
}
