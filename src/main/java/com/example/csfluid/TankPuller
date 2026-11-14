package com.example.csfluid;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

public final class TankPuller {
    private TankPuller() {}

    public static void tickPull(BlockEntity engineBe, AutoToggle toggle) {
        if (!CsFluid.pullTanksEnabled()) return;

        Level level = engineBe.getLevel();
        if (level == null || level.isClientSide()) return;

        Set<BlockPos> allowed = toggle.csfGetAllowedTanks();
        if (allowed.isEmpty()) return;

        BlockPos enginePos = engineBe.getBlockPos();

        // 1. Ищем валидные соседние баки среди разрешенных
        List<IFluidHandler> validTanks = new ArrayList<>();
        for (BlockPos targetPos : allowed) {
            // Должен стоять вплотную (расстояние <= 1 блок)
            if (!targetPos.closerThan(enginePos, 2.0)) continue;

            Direction dirTowardsEngine = Direction.getNearest(
                    enginePos.getX() - targetPos.getX(),
                    enginePos.getY() - targetPos.getY(),
                    enginePos.getZ() - targetPos.getZ()
            );

            IFluidHandler tankHandler = level.getCapability(Capabilities.FluidHandler.BLOCK, targetPos, dirTowardsEngine);
            if (tankHandler == null) {
                tankHandler = level.getCapability(Capabilities.FluidHandler.BLOCK, targetPos, null);
            }

            if (tankHandler != null) {
                // Проверяем, есть ли там лава
                FluidStack simulated = tankHandler.drain(1, FluidAction.SIMULATE);
                if (!simulated.isEmpty() && simulated.is(FluidTags.LAVA)) {
                    validTanks.add(tankHandler);
                }
            }
        }

        if (validTanks.isEmpty()) return;

        // 2. Получаем обработчик самого двигателя
        IFluidHandler engineHandler = level.getCapability(Capabilities.FluidHandler.BLOCK, enginePos, null);
        if (engineHandler == null) {
            engineHandler = new CsFluid.EngineLavaHandler(engineBe);
        }

        // Проверяем свободное место в двигателе
        FluidStack probe = new FluidStack(Fluids.LAVA, CsFluid.pullMaxRate());
        int totalSpace = engineHandler.fill(probe, FluidAction.SIMULATE);
        if (totalSpace <= 0) return;

        // 3. Выкачиваем в соответствии с режимом
        if (CsFluid.pullDrainMode() == CsFluid.DrainMode.EVEN) {
            pullEven(engineHandler, validTanks, totalSpace);
        } else {
            pullSequential(engineHandler, validTanks, totalSpace);
        }
    }

    /** Режим SEQUENTIAL: опустошаем баки по очереди один за другим */
    private static void pullSequential(IFluidHandler engine, List<IFluidHandler> tanks, int totalSpace) {
        int remainingSpace = totalSpace;
        for (IFluidHandler tank : tanks) {
            if (remainingSpace <= 0) break;

            FluidStack simulated = tank.drain(remainingSpace, FluidAction.SIMULATE);
            if (!simulated.isEmpty() && simulated.is(FluidTags.LAVA)) {
                int accepted = engine.fill(simulated, FluidAction.EXECUTE);
                if (accepted > 0) {
                    tank.drain(new FluidStack(simulated.getFluid(), accepted), FluidAction.EXECUTE);
                    remainingSpace -= accepted;
                }
            }
        }
    }

    /** Режим EVEN: делим забор поровну между всеми доступными баками */
    private static void pullEven(IFluidHandler engine, List<IFluidHandler> tanks, int totalSpace) {
        int remainingSpace = totalSpace;
        int count = tanks.size();
        int share = Math.max(1, remainingSpace / count);

        for (IFluidHandler tank : tanks) {
            if (remainingSpace <= 0) break;
            int toDrain = Math.min(share, remainingSpace);

            FluidStack simulated = tank.drain(toDrain, FluidAction.SIMULATE);
            if (!simulated.isEmpty() && simulated.is(FluidTags.LAVA)) {
                int accepted = engine.fill(simulated, FluidAction.EXECUTE);
                if (accepted > 0) {
                    tank.drain(new FluidStack(simulated.getFluid(), accepted), FluidAction.EXECUTE);
                    remainingSpace -= accepted;
                }
            }
        }

        // Если в каких-то баках лавы было меньше нормы и место осталось — добираем остаток
        if (remainingSpace > 0) {
            pullSequential(engine, tanks, remainingSpace);
        }
    }
}
