package sdb.px.dupe;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Tripwire;
import org.bukkit.block.data.type.TripwireHook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class TripwireLineFinder {
    private static final int MAX_TRIPWIRE_BLOCKS = 40;
    private static final List<BlockFace> AXES = List.of(BlockFace.NORTH, BlockFace.EAST);
    private static final BlockFace[] HORIZONTAL_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    Discovery discover(Block target) {
        if (target.getType() != Material.TRIPWIRE) {
            return new Discovery(null, Failure.NO_TRIPWIRE);
        }
        if (!(target.getBlockData() instanceof Tripwire tripwire)) {
            return new Discovery(null, Failure.NO_TRIPWIRE);
        }
        if (!tripwire.isAttached()) {
            return new Discovery(null, Failure.TRIPWIRE_NOT_ATTACHED);
        }

        Failure bestFailure = Failure.NO_HOOK;
        for (BlockFace axis : AXES) {
            EndpointResult first = findEndpoint(target, axis);
            EndpointResult second = findEndpoint(target, axis.getOppositeFace());
            if (first.failure() != Failure.NONE || second.failure() != Failure.NONE) {
                bestFailure = preferredFailure(bestFailure, first.failure());
                bestFailure = preferredFailure(bestFailure, second.failure());
                continue;
            }

            List<Block> wires = new ArrayList<>();
            List<Block> firstToTarget = new ArrayList<>(first.wires());
            Collections.reverse(firstToTarget);
            wires.addAll(firstToTarget);
            wires.add(target);
            wires.addAll(second.wires());
            if (wires.size() > MAX_TRIPWIRE_BLOCKS) {
                bestFailure = preferredFailure(bestFailure, Failure.MAX_LENGTH);
                continue;
            }
            TripwireFarm farm = TripwireFarm.create(
                    first.hook(), second.hook(),
                    first.facing(), second.facing(), wires);
            if (isValidLine(farm)) {
                return new Discovery(farm, Failure.NONE);
            }
            bestFailure = preferredFailure(bestFailure, Failure.INVALID_CONNECTION);
        }
        return new Discovery(null, bestFailure);
    }

    boolean isValidLine(TripwireFarm farm) {
        if (!isHookValid(farm.firstHook(), farm.firstFacing())
                || !isHookValid(farm.secondHook(), farm.secondFacing())) {
            return false;
        }
        for (int index = 0; index < farm.wires().size(); index++) {
            Block wire = farm.wires().get(index);
            if (!isLoaded(wire) || wire.getType() != Material.TRIPWIRE
                    || !(wire.getBlockData() instanceof Tripwire tripwire)
                    || !tripwire.isAttached()) {
                return false;
            }
            Block previous = index == 0 ? farm.firstHook() : farm.wires().get(index - 1);
            Block next = index == farm.wires().size() - 1
                    ? farm.secondHook() : farm.wires().get(index + 1);
            BlockFace towardPrevious = directionBetween(wire, previous);
            BlockFace towardNext = directionBetween(wire, next);
            if (towardPrevious == null || towardNext == null
                    || !tripwire.hasFace(towardPrevious) || !tripwire.hasFace(towardNext)) {
                return false;
            }
        }
        return true;
    }

    boolean hooksRemain(TripwireFarm farm) {
        return isHookValid(farm.firstHook(), farm.firstFacing())
                && isHookValid(farm.secondHook(), farm.secondFacing());
    }

    boolean otherWiresRemain(TripwireFarm farm, Block excluded) {
        for (Block wire : farm.wires()) {
            if (wire.equals(excluded)) {
                continue;
            }
            if (!isLoaded(wire) || wire.getType() != Material.TRIPWIRE
                    || !(wire.getBlockData() instanceof Tripwire tripwire)
                    || !tripwire.isAttached()) {
                return false;
            }
        }
        return true;
    }

    private EndpointResult findEndpoint(Block origin, BlockFace direction) {
        Block cursor = origin;
        int wireCount = 1;
        while (wireCount <= MAX_TRIPWIRE_BLOCKS) {
            int nextX = cursor.getX() + direction.getModX();
            int nextZ = cursor.getZ() + direction.getModZ();
            if (!cursor.getWorld().isChunkLoaded(nextX >> 4, nextZ >> 4)) {
                return EndpointResult.failure(Failure.UNLOADED_CHUNK);
            }
            Block next = cursor.getRelative(direction);
            if (!(cursor.getBlockData() instanceof Tripwire current) || !current.isAttached()) {
                return EndpointResult.failure(Failure.TRIPWIRE_NOT_ATTACHED);
            }

            if (next.getType() == Material.TRIPWIRE) {
                if (!(next.getBlockData() instanceof Tripwire nextWire) || !nextWire.isAttached()
                        || !current.hasFace(direction)
                        || !nextWire.hasFace(direction.getOppositeFace())) {
                    return EndpointResult.failure(Failure.INVALID_CONNECTION);
                }
                wireCount++;
                cursor = next;
                continue;
            }
            if (next.getType() == Material.TRIPWIRE_HOOK) {
                if (!current.hasFace(direction)
                        || !(next.getBlockData() instanceof TripwireHook hook)) {
                    return EndpointResult.failure(Failure.INVALID_CONNECTION);
                }
                if (!hook.isAttached()) {
                    return EndpointResult.failure(Failure.HOOK_NOT_ATTACHED);
                }
                if (hook.getFacing() != direction.getOppositeFace()) {
                    return EndpointResult.failure(Failure.INVALID_DIRECTION);
                }
                List<Block> wires = new ArrayList<>();
                Block wire = origin;
                int collectedWireCount = 0;
                while (!wire.equals(cursor) && collectedWireCount < MAX_TRIPWIRE_BLOCKS) {
                    wires.add(wire.getRelative(direction));
                    wire = wire.getRelative(direction);
                    collectedWireCount++;
                }
                if (!wire.equals(cursor)) {
                    return EndpointResult.failure(Failure.MAX_LENGTH);
                }
                return new EndpointResult(next, hook.getFacing(), List.copyOf(wires), Failure.NONE);
            }
            return EndpointResult.failure(Failure.NO_HOOK);
        }
        return EndpointResult.failure(Failure.MAX_LENGTH);
    }

    private boolean isHookValid(Block block, BlockFace expectedFacing) {
        return isLoaded(block)
                && block.getType() == Material.TRIPWIRE_HOOK
                && block.getBlockData() instanceof TripwireHook hook
                && hook.isAttached()
                && hook.getFacing() == expectedFacing;
    }

    private static boolean isLoaded(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    private static BlockFace directionBetween(Block from, Block to) {
        if (!from.getWorld().equals(to.getWorld()) || from.getY() != to.getY()) {
            return null;
        }
        int x = to.getX() - from.getX();
        int z = to.getZ() - from.getZ();
        for (BlockFace face : HORIZONTAL_FACES) {
            if (face.getModX() == x && face.getModZ() == z) {
                return face;
            }
        }
        return null;
    }

    private static Failure preferredFailure(Failure current, Failure candidate) {
        return candidate.priority > current.priority ? candidate : current;
    }

    enum Failure {
        NONE(0),
        NO_HOOK(1),
        NO_TRIPWIRE(2),
        TRIPWIRE_NOT_ATTACHED(3),
        INVALID_CONNECTION(4),
        INVALID_DIRECTION(5),
        HOOK_NOT_ATTACHED(5),
        UNLOADED_CHUNK(6),
        MAX_LENGTH(7),
        INVALID_SOURCE(8),
        MAX_ACTIVE_FARMS(9),
        FARM_CHANGED(10),
        RATE_LIMIT_ZERO(11);

        private final int priority;

        Failure(int priority) {
            this.priority = priority;
        }
    }

    record Discovery(TripwireFarm farm, Failure failure) {
    }

    private record EndpointResult(
            Block hook, BlockFace facing, List<Block> wires, Failure failure) {
        private static EndpointResult failure(Failure failure) {
            return new EndpointResult(null, null, List.of(), failure);
        }
    }
}
