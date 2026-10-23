package com.theecho;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Genera un laberinto nuevo (aleatorio) de deepslate dentro de the_echo:mirror_realm. */
public final class RealmBuilder {

    public record Result(BlockPos start, BlockPos original, List<BlockPos> traps, List<BlockPos> cells) {}

    private RealmBuilder() {}

    public static Result build(ServerWorld dst, BlockPos origin, Random rand) {
        final int n = Math.max(5, Math.min(11, EchoConfig.INSTANCE.mazeSize));
        final int size = n * 3 + 1;           // celdas de 2x2 con muros de 1
        final int floorY = origin.getY() - 1;
        final int ceilY = origin.getY() + 3;
        final int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

        boolean[][] solid = new boolean[size][size];
        for (boolean[] row : solid) Arrays.fill(row, true);

        // --- laberinto por backtracking recursivo ---
        boolean[][] seen = new boolean[n][n];
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        seen[0][0] = true;
        carveCell(solid, 0, 0);
        stack.push(new int[]{0, 0});
        while (!stack.isEmpty()) {
            int[] c = stack.peek();
            List<int[]> opts = new ArrayList<>();
            for (int[] d : dirs) {
                int nx = c[0] + d[0], nz = c[1] + d[1];
                if (nx >= 0 && nz >= 0 && nx < n && nz < n && !seen[nx][nz]) opts.add(d);
            }
            if (opts.isEmpty()) { stack.pop(); continue; }
            int[] d = opts.get(rand.nextInt(opts.size()));
            int nx = c[0] + d[0], nz = c[1] + d[1];
            seen[nx][nz] = true;
            carveCell(solid, nx, nz);
            carvePassage(solid, c[0], c[1], d[0], d[1]);
            stack.push(new int[]{nx, nz});
        }
        // Algunos bucles extra para que los Ecos puedan rodearte
        for (int i = 0; i < n; i++) {
            int cx = rand.nextInt(n), cz = rand.nextInt(n);
            int[] d = dirs[rand.nextInt(4)];
            int nx = cx + d[0], nz = cz + d[1];
            if (nx >= 0 && nz >= 0 && nx < n && nz < n) carvePassage(solid, cx, cz, d[0], d[1]);
        }

        // --- el Original: la celda más lejana del inicio (BFS) ---
        int[][] dist = new int[size][size];
        for (int[] row : dist) Arrays.fill(row, -1);
        ArrayDeque<int[]> q = new ArrayDeque<>();
        dist[1][1] = 0;
        q.add(new int[]{1, 1});
        int ox = 1, oz = 1, best = 0;
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int[] d : dirs) {
                int nx = c[0] + d[0], nz = c[1] + d[1];
                if (nx < 0 || nz < 0 || nx >= size || nz >= size) continue;
                if (solid[nx][nz] || dist[nx][nz] >= 0) continue;
                dist[nx][nz] = dist[c[0]][c[1]] + 1;
                if (dist[nx][nz] > best) { best = dist[nx][nz]; ox = nx; oz = nz; }
                q.add(new int[]{nx, nz});
            }
        }

        // --- escribir bloques ---
        BlockPos.Mutable m = new BlockPos.Mutable();
        BlockState air = Blocks.AIR.getDefaultState();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                int wx = origin.getX() + x, wz = origin.getZ() + z;
                put(dst, m.set(wx, floorY, wz), floor(rand), flags);
                put(dst, m.set(wx, ceilY, wz), Blocks.DEEPSLATE_BRICKS.getDefaultState(), flags);
                for (int y = origin.getY(); y < ceilY; y++) {
                    BlockState out;
                    if (solid[x][z]) {
                        out = wall(rand);
                    } else {
                        out = air;
                        boolean nearStart = x <= 2 && z <= 2;
                        if (!nearStart) {
                            if (y == origin.getY() + 2 && rand.nextInt(40) == 0) out = Blocks.COBWEB.getDefaultState();
                            if (y == origin.getY() && rand.nextInt(60) == 0) out = Blocks.SOUL_TORCH.getDefaultState();
                        }
                    }
                    put(dst, m.set(wx, y, wz), out, flags);
                }
            }
        }

        BlockPos start = new BlockPos(origin.getX() + 1, origin.getY(), origin.getZ() + 1);
        BlockPos original = new BlockPos(origin.getX() + ox, origin.getY(), origin.getZ() + oz);
        dst.setBlockState(original, ModBlocks.ECHO_ORIGINAL.getDefaultState(), Block.NOTIFY_ALL);

        // --- celdas, callejones sin salida (trampas de susto) ---
        List<BlockPos> cells = new ArrayList<>();
        List<BlockPos> deadEnds = new ArrayList<>();
        for (int cx = 0; cx < n; cx++) {
            for (int cz = 0; cz < n; cz++) {
                if (cx == 0 && cz == 0) continue;
                int x0 = 1 + cx * 3, z0 = 1 + cz * 3;
                BlockPos cp = new BlockPos(origin.getX() + x0, origin.getY(), origin.getZ() + z0);
                if (cp.equals(original)) continue;
                cells.add(cp);
                int open = 0;
                if (!solid[x0 + 2][z0]) open++;
                if (!solid[x0 - 1][z0]) open++;
                if (!solid[x0][z0 + 2]) open++;
                if (!solid[x0][z0 - 1]) open++;
                if (open == 1) deadEnds.add(cp);
            }
        }
        Collections.shuffle(deadEnds, rand);
        List<BlockPos> traps = new ArrayList<>(deadEnds.subList(0, Math.min(6, deadEnds.size())));
        List<BlockPos> shuffled = new ArrayList<>(cells);
        Collections.shuffle(shuffled, rand);
        for (BlockPos c : shuffled) {
            if (traps.size() >= 5) break;
            if (!traps.contains(c)) traps.add(c);
        }
        return new Result(start, original, traps, cells);
    }

    private static void carveCell(boolean[][] solid, int cx, int cz) {
        int x0 = 1 + cx * 3, z0 = 1 + cz * 3;
        for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) solid[x0 + dx][z0 + dz] = false;
    }

    private static void carvePassage(boolean[][] solid, int cx, int cz, int dx, int dz) {
        int x0 = 1 + cx * 3, z0 = 1 + cz * 3;
        if (dx == 1) { solid[x0 + 2][z0] = false; solid[x0 + 2][z0 + 1] = false; }
        else if (dx == -1) { solid[x0 - 1][z0] = false; solid[x0 - 1][z0 + 1] = false; }
        else if (dz == 1) { solid[x0][z0 + 2] = false; solid[x0 + 1][z0 + 2] = false; }
        else { solid[x0][z0 - 1] = false; solid[x0 + 1][z0 - 1] = false; }
    }

    private static BlockState floor(Random r) {
        return (r.nextInt(5) == 0 ? Blocks.COBBLED_DEEPSLATE : Blocks.DEEPSLATE_TILES).getDefaultState();
    }

    private static BlockState wall(Random r) {
        int k = r.nextInt(100);
        if (k < 55) return Blocks.DEEPSLATE_BRICKS.getDefaultState();
        if (k < 75) return Blocks.COBBLED_DEEPSLATE.getDefaultState();
        if (k < 88) return Blocks.CRACKED_DEEPSLATE_BRICKS.getDefaultState();
        if (k < 96) return Blocks.DEEPSLATE.getDefaultState();
        if (k < 98) return Blocks.CHISELED_DEEPSLATE.getDefaultState();
        return Blocks.SCULK.getDefaultState();
    }

    /** Solo escribe si el bloque cambia (menos lag y permite reutilizar la zona). */
    private static void put(ServerWorld w, BlockPos pos, BlockState state, int flags) {
        if (w.getBlockState(pos) == state) return;
        w.setBlockState(pos, state, flags);
    }
}
