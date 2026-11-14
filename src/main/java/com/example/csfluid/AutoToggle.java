package com.example.csfluid;

import java.util.Set;
import net.minecraft.core.BlockPos;

/** Интерфейс, который миксин добавляет двигателю: авто-режим и список разрешённых баков. */
public interface AutoToggle {
    boolean csfIsAuto();
    void csfSetAuto(boolean value);

    boolean csfIsTankAllowed(BlockPos pos);
    boolean csfToggleTank(BlockPos pos);
    Set<BlockPos> csfGetAllowedTanks();
}
