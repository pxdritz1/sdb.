package sdb.px.dupe;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.List;
import java.util.UUID;

record TripwireFarm(
        Key key,
        Block firstHook,
        Block secondHook,
        BlockFace firstFacing,
        BlockFace secondFacing,
        List<Block> wires) {

    static TripwireFarm create(Block firstHook, Block secondHook, BlockFace firstFacing,
                               BlockFace secondFacing, List<Block> wires) {
        Block first = compare(firstHook, secondHook) <= 0 ? firstHook : secondHook;
        Block second = first == firstHook ? secondHook : firstHook;
        Key key = new Key(
                first.getWorld().getUID(),
                first.getX(), first.getY(), first.getZ(),
                second.getX(), second.getY(), second.getZ());
        return new TripwireFarm(
                key, firstHook, secondHook, firstFacing, secondFacing, List.copyOf(wires));
    }

    private static int compare(Block first, Block second) {
        int x = Integer.compare(first.getX(), second.getX());
        if (x != 0) {
            return x;
        }
        int y = Integer.compare(first.getY(), second.getY());
        return y != 0 ? y : Integer.compare(first.getZ(), second.getZ());
    }

    record Key(UUID worldId, int firstX, int firstY, int firstZ, int secondX, int secondY, int secondZ) {
    }
}
