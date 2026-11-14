package com.example.csfluid.mixin;

import java.util.HashSet;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.csfluid.AutoLoad;
import com.example.csfluid.AutoToggle;
import com.example.csfluid.CsFluid;
import com.example.csfluid.TankPuller;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Авто-режим нагрузки для Compact Steam Engine и забор лавы из соседних баков.
 */
@Mixin(targets = "de.genesis.compactsteam.CompactSteamEngineBlockEntity", remap = false)
public abstract class EngineAutoLoadMixin implements AutoToggle {

    @Shadow private int suStep;

    @Shadow public abstract int getStep();
    @Shadow public abstract int getStressCapacitySU();
    @Shadow public abstract boolean isBurning();
    @Shadow protected abstract void settingsChanged();

    @Unique private int csf$limit = 0;
    @Unique private int csf$timer = 0;
    @Unique private int csf$pullTimer = 0;
    @Unique private int csf$lowerFor = 0;
    @Unique private static boolean csf$errorLogged = false;
    @Unique private boolean csf$announced = false;
    @Unique private boolean csf$auto = true;
    @Unique private final Set<BlockPos> csf$allowedTanks = new HashSet<>();

    @Override public boolean csfIsAuto() { return csf$auto; }
    @Override public void csfSetAuto(boolean value) { csf$auto = value; }

    @Override public boolean csfIsTankAllowed(BlockPos pos) { return csf$allowedTanks.contains(pos); }

    @Override
    public boolean csfToggleTank(BlockPos pos) {
        if (csf$allowedTanks.contains(pos)) {
            csf$allowedTanks.remove(pos);
            return false;
        } else {
            csf$allowedTanks.add(pos.immutable());
            return true;
        }
    }

    @Override public Set<BlockPos> csfGetAllowedTanks() { return csf$allowedTanks; }

    /** Игрок двинул слайдер -> запоминаем как лимит. */
    @Inject(method = "setStep", at = @At("HEAD"), remap = false, require = 0)
    private void csf$onSetStep(int step, CallbackInfo ci) {
        csf$limit = Math.max(1, step);
    }

    @Inject(method = "write", at = @At("TAIL"), remap = false, require = 0)
    private void csf$write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        tag.putInt("CsfLimit", csf$limit > 0 ? csf$limit : suStep);
        tag.putBoolean("CsfAuto", csf$auto);

        long[] tankArr = csf$allowedTanks.stream().mapToLong(BlockPos::asLong).toArray();
        tag.putLongArray("CsfAllowedTanks", tankArr);
    }

    @Inject(method = "read", at = @At("TAIL"), remap = false, require = 0)
    private void csf$read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (tag.contains("CsfLimit")) csf$limit = Math.max(1, tag.getInt("CsfLimit"));
        if (tag.contains("CsfAuto")) csf$auto = tag.getBoolean("CsfAuto");

        if (tag.contains("CsfAllowedTanks")) {
            csf$allowedTanks.clear();
            for (long l : tag.getLongArray("CsfAllowedTanks")) {
                csf$allowedTanks.add(BlockPos.of(l));
            }
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), remap = false, require = 0)
    private void csf$autoLoad(CallbackInfo ci) {
        BlockEntity be = (BlockEntity) (Object) this;
        Level level = be.getLevel();
        if (level == null || level.isClientSide) return;

        if (!csf$announced) {
            csf$announced = true;
            CsFluid.LOG.info("[csfluid] Двигатель {} активен (авто={}, ступень={}, лимит={}, баков={})",
                    be.getBlockPos(), CsFluid.autoEnabled(), suStep, csf$limit, csf$allowedTanks.size());
        }

        // 1. Выкачивание лавы из разрешённых соседних баков
        if (CsFluid.pullTanksEnabled()) {
            if (++csf$pullTimer >= CsFluid.pullInterval()) {
                csf$pullTimer = 0;
                try {
                    TankPuller.tickPull(be, this);
                } catch (Throwable t) {
                    CsFluid.LOG.error("[csfluid] Ошибка при выкачке лавы из бака", t);
                }
            }
        }

        // 2. Логика авто-регулировки мощности
        try {
            if (csf$limit <= 0) csf$limit = Math.max(1, suStep);

            if (!CsFluid.autoEnabled() || !csf$auto) {
                if (suStep != csf$limit) {
                    suStep = csf$limit;
                    settingsChanged();
                }
                return;
            }

            int interval = CsFluid.interval();
            if (++csf$timer < interval) return;
            csf$timer = 0;

            int cur = Math.max(1, getStep());
            int capSu = getStressCapacitySU();
            int perStep = Math.max(1, capSu / cur);

            float[] net = AutoLoad.read(be);
            if (net == null) return;

            float own = isBurning() ? capSu : 0f;
            float othersCapacity = Math.max(0f, net[1] - own);
            float need = Math.max(0f, net[0] - othersCapacity);

            int want = (int) Math.ceil(need * (1.0 + CsFluid.margin()) / perStep);
            int min = CsFluid.minStep();
            int max = Math.max(min, csf$limit);
            want = Mth.clamp(want, min, max);

            if (want > cur) {
                CsFluid.LOG.info("[csfluid] {}: нагрузка={} SU, ёмкость={} SU, ступень {} -> {} (лимит {})",
                        be.getBlockPos(), net[0], net[1], cur, want, max);
                csf$lowerFor = 0;
                suStep = want;
                settingsChanged();
            } else if (want < cur) {
                csf$lowerFor += interval;
                if (csf$lowerFor >= CsFluid.lowerDelay()) {
                    CsFluid.LOG.info("[csfluid] {}: нагрузка={} SU, ёмкость={} SU, ступень {} -> {} (лимит {})",
                            be.getBlockPos(), net[0], net[1], cur, want, max);
                    csf$lowerFor = 0;
                    suStep = want;
                    settingsChanged();
                }
            } else {
                csf$lowerFor = 0;
            }
        } catch (Throwable t) {
            if (!csf$errorLogged) {
                csf$errorLogged = true;
                CsFluid.LOG.error("[csfluid] Ошибка в авто-режиме двигателя", t);
            }
        }
    }
}
