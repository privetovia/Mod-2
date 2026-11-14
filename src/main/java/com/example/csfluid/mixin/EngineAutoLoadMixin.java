package com.example.csfluid.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.csfluid.AutoLoad;
import com.example.csfluid.CsFluid;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Авто-режим нагрузки для Compact Steam Engine.
 * Слайдер мощности = ЛИМИТ (csf$limit). Реальная ступень (suStep) подстраивается под нагрузку сети.
 */
@Mixin(targets = "de.genesis.compactsteam.CompactSteamEngineBlockEntity", remap = false)
public abstract class EngineAutoLoadMixin {

    @Shadow private int suStep;

    @Shadow public abstract int getStep();
    @Shadow public abstract int getStressCapacitySU();
    @Shadow public abstract boolean isBurning();
    @Shadow protected abstract void settingsChanged();

    @Unique private int csf$limit = 0;
    @Unique private int csf$timer = 0;
    @Unique private int csf$lowerFor = 0;
    @Unique private static boolean csf$errorLogged = false;

    /** Игрок двинул слайдер -> запоминаем как лимит. */
    @Inject(method = "setStep", at = @At("HEAD"), remap = false, require = 0)
    private void csf$onSetStep(int step, CallbackInfo ci) {
        csf$limit = Math.max(1, step);
    }

    @Inject(method = "write", at = @At("TAIL"), remap = false, require = 0)
    private void csf$write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        tag.putInt("CsfLimit", csf$limit > 0 ? csf$limit : suStep);
    }

    @Inject(method = "read", at = @At("TAIL"), remap = false, require = 0)
    private void csf$read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (tag.contains("CsfLimit")) csf$limit = Math.max(1, tag.getInt("CsfLimit"));
    }

    @Inject(method = "tick", at = @At("HEAD"), remap = false, require = 0)
    private void csf$autoLoad(CallbackInfo ci) {
        BlockEntity be = (BlockEntity) (Object) this;
        Level level = be.getLevel();
        if (level == null || level.isClientSide) return;

        try {
            if (csf$limit <= 0) csf$limit = Math.max(1, suStep);

            // авто-режим выключен в конфиге -> вернуть ступень на лимит
            if (!CsFluid.autoEnabled()) {
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
                csf$lowerFor = 0;
                suStep = want;
                settingsChanged();
            } else if (want < cur) {
                csf$lowerFor += interval;
                if (csf$lowerFor >= CsFluid.lowerDelay()) {
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
