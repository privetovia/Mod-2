package com.example.csfluid;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Читает из кинетической сети Create текущую нагрузку и ёмкость (в SU).
 * Всё через рефлексию, чтобы не зависеть от Create при компиляции.
 * Если что-то не находится — возвращает null и один раз пишет причину в лог.
 */
public final class AutoLoad {
    private AutoLoad() {}

    private static Method HAS_NET, GET_NET;
    private static Field F_STRESS, F_CAP;
    private static Method M_STRESS, M_CAP;
    private static boolean bound = false;
    private static boolean broken = false;

    /** @return {нагрузка SU, суммарная ёмкость SU} или null, если прочитать не вышло */
    public static float[] read(BlockEntity be) {
        if (broken) return null;
        try {
            if (HAS_NET == null) {
                HAS_NET = be.getClass().getMethod("hasNetwork");
                GET_NET = be.getClass().getMethod("getOrCreateNetwork");
            }
            if (!((Boolean) HAS_NET.invoke(be))) return new float[] {0f, 0f};
            Object net = GET_NET.invoke(be);
            if (net == null) return new float[] {0f, 0f};
            if (!bound) bind(net.getClass());

            float stress = F_STRESS != null ? F_STRESS.getFloat(net) : (Float) M_STRESS.invoke(net);
            float cap = F_CAP != null ? F_CAP.getFloat(net) : (Float) M_CAP.invoke(net);
            return new float[] {stress, cap};
        } catch (Throwable t) {
            broken = true;
            CsFluid.LOG.error("[csfluid] Авто-режим отключён: не удалось прочитать сеть Create "
                    + "(другая версия Create?)", t);
            return null;
        }
    }

    private static synchronized void bind(Class<?> netClass) throws Exception {
        if (bound) return;
        try {
            F_STRESS = netClass.getDeclaredField("currentStress");
            F_CAP = netClass.getDeclaredField("currentCapacity");
            F_STRESS.setAccessible(true);
            F_CAP.setAccessible(true);
        } catch (NoSuchFieldException e) {
            F_STRESS = null;
            F_CAP = null;
            M_STRESS = netClass.getMethod("calculateStress");
            M_CAP = netClass.getMethod("calculateCapacity");
        }
        bound = true;
        CsFluid.LOG.info("[csfluid] Авто-режим: сеть Create найдена ({})", netClass.getName());
    }
}
