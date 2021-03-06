package com.github.terminatornl.tiquality.world;

import com.github.terminatornl.tiquality.Tiquality;
import com.github.terminatornl.tiquality.concurrent.PausableThreadPoolExecutor;
import com.github.terminatornl.tiquality.interfaces.TiqualityChunk;
import com.github.terminatornl.tiquality.interfaces.TiqualityWorld;
import com.github.terminatornl.tiquality.interfaces.Tracker;
import com.github.terminatornl.tiquality.mixinhelper.MixinConfigPlugin;
import com.github.terminatornl.tiquality.util.FiFoQueue;
import com.github.terminatornl.tiquality.util.Utils;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;

public class WorldHelper {

    private static final FiFoQueue<ScheduledAction> TASKS = new FiFoQueue<>();

    public static List<ChunkPos> getAffectedChunksInCuboid(BlockPos corner_1, BlockPos corner_2) {
        BlockPos start = Utils.BlockPos.getMin(corner_1, corner_2);
        BlockPos end = Utils.BlockPos.getMax(corner_1, corner_2);

        ChunkPos startChunk = new ChunkPos(start);
        ChunkPos endChunk = new ChunkPos(end);

        ArrayList<ChunkPos> posList = new ArrayList<>();
        for (int x = startChunk.x; x <= endChunk.x; x++) {
            for (int z = startChunk.z; z <= endChunk.z; z++) {
                posList.add(new ChunkPos(x, z));
            }
        }
        return posList;
    }

    /**
     * Sets the tracker in a cuboid area
     *
     * @param corner_1  A corner
     * @param corner_2  The opposite corner
     * @param tracker   the tracker to add
     * @param callback  a task to run on completion. This will run in the main thread!
     * @param beforeRun a task to run before work starts, This runs in the main thread.
     */
    public static void setTrackerCuboid(TiqualityWorld world, BlockPos corner_1, BlockPos corner_2, Tracker tracker, Runnable callback, Runnable beforeRun) {
        BlockPos start = Utils.BlockPos.getMin(corner_1, corner_2);
        BlockPos end = Utils.BlockPos.getMax(corner_1, corner_2);

        ChunkPos startChunk = new ChunkPos(start);
        ChunkPos endChunk = new ChunkPos(end);

        int affectedChunks = 0;
        synchronized (TASKS) {
            if (beforeRun != null) {
                TASKS.addToQueue(new CallBack(beforeRun));
            }
            for (int x = startChunk.x; x <= endChunk.x; x++) {
                for (int z = startChunk.z; z <= endChunk.z; z++) {
                    TASKS.addToQueue(new SetTrackerTask(world, new ChunkPos(x, z), start, end, tracker));
                    affectedChunks++;
                    if (affectedChunks % 40 == 0) {
                        TASKS.addToQueue(new SaveWorldTask((World) world));
                    }
                }
            }
            if (callback != null) {
                TASKS.addToQueue(new CallBack(callback));
            }
        }
        if (affectedChunks == 0) {
            Tiquality.LOGGER.warn("Tried to set a tracker in an area, but no chunks are affected!");
            Tiquality.LOGGER.warn("Low: " + start);
            Tiquality.LOGGER.warn("High: " + end);
            new Exception().printStackTrace();
        }
    }

    public static int getQueuedTasks() {
        synchronized (TASKS) {
            return TASKS.size();
        }
    }

    interface ScheduledAction extends Runnable {
        boolean isCallback();

        default void loadChunk() {
        }
    }

    /**
     * Executes tasks in the main thread, but limits itself to 100 milliseconds.
     * Used for large tasks that must be done in the main thread.
     */
    public static class SmearedAction {

        public static final SmearedAction INSTANCE = new SmearedAction();
        private PausableThreadPoolExecutor threadPool = new PausableThreadPoolExecutor(16);

        private SmearedAction() {

        }

        /**
         * This loads all chunks in the main thread,
         * and starts all tasks on per-chunk basis in multiple threads.
         * <p>
         * The main thread will be frozen while these tasks run.
         * When 40 ms have been consumed, it will stop processing more tasks, and
         * waits until all currently running tasks have been processed.
         * After each task exited, the main server thread is continued.
         * <p>
         * This has multiple uses:
         * * The watchdog will not kill the server, as it still 'ticks'
         * * Large operations can be submit at once, and processed later.
         * * No concurrency errors because the main thread is frozen and therefore does not interact with the chunks
         * * Because we can assume there are no concurrency errors, we can remove synchronization overhead on per chunk basis, increasing performance.
         * * Chunks are loaded using the main thread, so Sponge doesn't complain.
         * <p>
         * There are however, downsides to this:
         * * This process takes a very long time to complete
         * * While it is processing, the TPS drops if the server was already having performance issues.
         *
         * @param event the event.
         */
        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.START) {
                return;
            }
            try {
                threadPool.resume();
                long maxTime = System.currentTimeMillis() + 40;
                while (System.currentTimeMillis() < maxTime) {
                    synchronized (TASKS) {
                        if (TASKS.size() == 0) {
                            return;
                        }
                        ScheduledAction action = TASKS.take();
                        action.loadChunk();
                        if (action.isCallback() == false) {
                            /* It's a task, we execute it straight away in the threadpool. */
                            threadPool.submit(action);
                        } else {
                            /* It's a callback, we wait for all Tasks to end, and then call it. */
                            threadPool.pause();
                            try {
                                action.run();
                            } catch (Throwable t) {
                                t.printStackTrace();
                            }
                            if (TASKS.size() == 0) {
                                break;
                            }
                            threadPool.resume();
                        }
                    }
                }
                threadPool.finish();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public static class CallBack implements ScheduledAction {

        private final Runnable runnable;

        public CallBack(Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public void run() {
            runnable.run();
        }

        @Override
        public boolean isCallback() {
            return true;
        }
    }

    public static class SaveWorldTask implements ScheduledAction {

        private final World world;

        public SaveWorldTask(World world) {
            this.world = world;
        }

        @Override
        public boolean isCallback() {
            return true;
        }

        @Override
        public void run() {
            world.getSaveHandler().flush();
        }
    }

    public static class SetTrackerTask implements ScheduledAction {

        private final TiqualityWorld world;
        private final ChunkPos chunkPos;
        private final BlockPos start;
        private final BlockPos end;
        private final Tracker tracker;
        private TiqualityChunk chunk = null;

        /**
         * Create a new set tracker task
         *
         * @param world    The world
         * @param chunkPos Any location in the chunk you're editing
         * @param start    The start position (all chunks)
         * @param end      The end position (all chunks)
         * @param tracker  The tracker
         */
        public SetTrackerTask(TiqualityWorld world, ChunkPos chunkPos, BlockPos start, BlockPos end, Tracker tracker) {
            this.world = world;
            this.chunkPos = chunkPos;
            this.start = start;
            this.end = end;
            this.tracker = tracker;
        }

        @Override
        public void run() {
            try {
                if (chunk == null) {
                    throw new IllegalStateException("loadChunk() not called.");
                }

                int chunkXstart = chunkPos.getXStart();
                int chunkZstart = chunkPos.getZStart();
                int chunkXend = chunkPos.getXEnd();
                int chunkZend = chunkPos.getZEnd();

                int low_x = Math.max(start.getX(), chunkXstart);
                int low_y = start.getY();
                int low_z = Math.max(start.getZ(), chunkZstart);

                int high_x = Math.min(end.getX(), chunkXend);
                int high_y = end.getY();
                int high_z = Math.min(end.getZ(), chunkZend);

                boolean isEntireChunk =
                        chunkPos.getXEnd() == high_x &&
                                chunkPos.getXStart() == low_x &&
                                chunkPos.getZEnd() == high_z &&
                                chunkPos.getZStart() == low_z &&
                                low_y == 0 &&
                                high_y == world.getMinecraftWorld().getHeight() - 1;

                if (isEntireChunk) {
                    chunk.tiquality_setTrackerForEntireChunk(tracker);
                } else {
                    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

                    for (int x = low_x; x <= high_x; x++) {
                        for (int z = low_z; z <= high_z; z++) {
                            for (int y = low_y; y <= high_y; y++) {
                                chunk.tiquality_setTrackedPosition(pos.setPos(x, y, z), tracker);

                            }
                        }
                    }
                }
            } catch (Throwable t) {
                /* Make sure an error is printed */
                t.printStackTrace();
                throw t;
            }
        }

        @Override
        public boolean isCallback() {
            return false;
        }

        @Override
        public void loadChunk() {
            chunk = MixinConfigPlugin.spongePresent ? SpongeChunkLoader.getChunkForced(world, chunkPos) : world.getTiqualityChunk(chunkPos);
        }
    }
}
