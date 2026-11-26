package dev.quietframe.bench;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import java.util.Set;

final class Fixture {
    static final BlockPos MARKER = new BlockPos(0, 68, 2);

    static void build(ServerPlayer player) {
        var level = player.level();
        var server = level.getServer();
        var rules = level.getGameRules();
        rules.set(GameRules.ADVANCE_TIME, false, server);
        rules.set(GameRules.ADVANCE_WEATHER, false, server);
        rules.set(GameRules.SPAWN_MOBS, false, server);
        rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
        server.setWeatherParameters(1_000_000, 0, false, false);
        var clock = level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
        level.clockManager().setTotalTicks(clock, 6000);
        level.clockManager().setPaused(clock, true);
        // A tiled exhibition courtyard with bookcases, patterned columns, roofs and glass bays.
        Block[] tiles = {Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS, Blocks.SMOOTH_STONE};
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (int x = -36; x <= 36; x++) {
            for (int z = -38; z <= 33; z++) {
                int bay = Math.floorMod(x, 8), row = Math.floorMod(z, 10);
                level.setBlock(new BlockPos(x, 64, z), tiles[Math.floorMod(x / 3 + z / 3, 3)].defaultBlockState(), flags);
                if (z > 17 || bay > 5 || row > 5) continue;
                for (int y = 1; y <= 6; y++) {
                    Block material;
                    if (y == 6) material = Blocks.RED_NETHER_BRICKS;
                    else if ((bay == 0 || bay == 5) && (row == 0 || row == 5)) material = Blocks.QUARTZ_PILLAR;
                    else if (row == 0) material = Blocks.BOOKSHELF;
                    else if (bay == 0 || bay == 5) material = Blocks.GLASS;
                    else continue;
                    level.setBlock(new BlockPos(x, 64 + y, z), material.defaultBlockState(), flags);
                }
            }
        }
        level.setBlock(MARKER, Blocks.EMERALD_BLOCK.defaultBlockState(), flags);
        player.teleportTo(level, 0.5, 65, 28.5, Set.of(), 180, 15, true);
    }
}
