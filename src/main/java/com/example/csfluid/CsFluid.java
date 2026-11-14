package com.example.csfluid;

import java.lang.reflect.Field;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
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
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Аддон для Compact Steam Engine:
 *  1) приём лавы через трубы/помпы Create;
 *  2) автоматический режим нагрузки (см. mixin/EngineAutoLoadMixin).
 */
@Mod("csfluid")
public class CsFluid {
    public static final Logger LOG = LogUtils.getLogger();

    static final ResourceLocation ENGINE_ID =
            ResourceLocation.fromNamespaceAndPath("compactsteam", "compact_steam_engine");

    /** сколько тиков горения даёт 1 мБ лавы (ведро = 1000 мБ = 20000 тиков) */
    static final int TICKS_PER_MB = 20;
    /** максимум "запаса" лавы внутри двигателя, в мБ */
    static final int CAPACITY_MB = 4000;

    // ---------------- конфиг (config/csfluid-common.toml) ----------------
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue AUTO_ENABLED;
    public static final ModConfigSpec.IntValue CHECK_INTERVAL;
    public static final ModConfigSpec.IntValue LOWER_DELAY;
    public static final ModConfigSpec.IntValue MIN_STEP;
    public static final ModConfigSpec.DoubleValue MARGIN;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("autoLoad");
        AUTO_ENABLED = b.comment("Автоматический режим нагрузки. Слайдер мощности двигателя становится ЛИМИТОМ.",
                "false = двигатель работает как обычно (фиксированная ступень).")
                .define("enabled", true);
        CHECK_INTERVAL = b.comment("Как часто двигатель пересчитывает нужную мощность (в тиках, 20 тиков = 1 сек).")
                .defineInRange("checkIntervalTicks", 10, 1, 200);
        LOWER_DELAY = b.comment("Сколько тиков нагрузка должна быть ниже, прежде чем мощность снизится (защита от дёрганья).")
                .defineInRange("lowerDelayTicks", 100, 0, 6000);
        MARGIN = b.comment("Запас мощности сверх нагрузки (0.05 = +5%).")
                .defineInRange("safetyMargin", 0.05, 0.0, 1.0);
        MIN_STEP = b.comment("Минимальная ступень мощности на холостом ходу.")
                .defineInRange("minStep", 1, 1, 100);
        b.pop();
        SPEC = b.build();
    }

    public static boolean autoEnabled() {
        try { return AUTO_ENABLED.get(); } catch (Exception e) { return false; }
    }
    public static int interval() {
        try { return CHECK_INTERVAL.get(); } catch (Exception e) { return 10; }
    }
    public static int lowerDelay() {
        try { return LOWER_DELAY.get(); } catch (Exception e) { return 100; }
    }
    public static int minStep() {
        try { return MIN_STEP.get(); } catch (Exception e) { return 1; }
    }
    public static double margin() {
        try { return MARGIN.get(); } catch (Exception e) { return 0.05; }
    }

    public CsFluid(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC);
        modBus.addListener(this::registerCaps);
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

    /** Обработчик жидкости: принимает лаву и добавляет её в burnRemaining двигателя. */
    static class EngineLavaHandler implements IFluidHandler {
        private static Field REMAINING;
        private static Field TOTAL;
        private static boolean failed = false;

        private final BlockEntity be;

        EngineLavaHandler(BlockEntity be) {
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
                LOG.error("[csfluid] Не удалось получить поля двигателя (версия Compact Steam изменилась?)", e);
            }
        }

        private double getRemaining() {
            try { return REMAINING.getDouble(be); } catch (Exception e) { return 0; }
        }

        @Override public int getTanks() { return 1; }
        @Override public FluidStack getFluidInTank(int tank) { return FluidStack.EMPTY; }
        @Override public int getTankCapacity(int tank) { return CAPACITY_MB; }
        @Override public boolean isFluidValid(int tank, FluidStack stack) { return stack.is(FluidTags.LAVA); }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (failed || resource.isEmpty() || !resource.is(FluidTags.LAVA)) return 0;

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
